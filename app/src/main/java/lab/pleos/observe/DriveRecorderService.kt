package lab.pleos.observe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import lab.pleos.observe.logic.recap.DriveGear
import lab.pleos.observe.logic.recap.visibleReviewCards
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** User-started drive recap recorder; persists reduced evidence only, never raw event history.
 * API 34 specialUse + subtype: https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
 * Startup: https://developer.android.com/develop/background-work/services/fgs/launch
 * Runs in :drive_recorder to isolate ownership from the unchanged Activity/SDK clients.
 */
class DriveRecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val run = "${Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}"
    private val counts = ConcurrentHashMap<String, AtomicLong>().apply {
        listOf("turnSignal", "steeringAngle", "brakePedal", "gear", "gearSelection", "speed", "ux").forEach { put(it, AtomicLong()) }
    }
    private var started = false
    private var subscriptions: DriveSignalSubscriptions? = null
    private var recap: RecapRecordingSession? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!started) return
            if (BuildConfig.DEBUG) write("heartbeat", "alive", "timer", "counts=${counterText()}")
            RecapRepository.get(this@DriveRecorderService).flush()
            main.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        live = this
        write("service.onCreate", "called")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        write("service.onStartCommand", intent?.action ?: "null", details = "startId=$startId flags=$flags")
        if (intent?.action == ACTION_STOP) {
            if (!started || recap?.gear == DriveGear.PARK) {
                write("service.stopRequested", "parked_request")
                stopSelf()
            } else write("service.stopRejected", "park_required")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            write("service.startRejected", "unknown_action")
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            write("service.duplicateStart", "ignored")
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "오늘 운전 기록", NotificationManager.IMPORTANCE_LOW).apply {
                description = "차량 신호의 최소 구간 요약을 기록합니다"
                setSound(null, null)
            })
            val notification = recordingNotification()
            // Promote before initializing SDK/Car, within the framework startup deadline.
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            started = true
            val demo = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO_MISSING_SPEED, false)
            recap = RecapRecordingSession(this, demo, { recapReady() }, { updateRecordingNotification() }).also { it.start() }
            write("service.foreground", "returned", "framework_return", "type=specialUse notificationsEnabled=${manager.areNotificationsEnabled()}")
            subscriptions = DriveSignalSubscriptions(applicationContext) { signal, value, source, details, at ->
                if (BuildConfig.DEBUG) {
                    val count = if (source == "notify") counts[signal]?.incrementAndGet() else null
                    write(signal, value, source, "${if (count == null) "" else "n=$count "}$details".trimEnd(), at)
                }
                recap?.raw(signal, value, source, at)
            }.also { it.start() }
            main.postDelayed(heartbeat, 5_000L)
        } catch (error: Exception) {
            write("service.startError", error.javaClass.simpleName, "error")
            stopSelf()
        }
        // No sticky restart can masquerade as one uninterrupted recording run.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        write("service.onTaskRemoved", "called", details = "counts=${counterText()}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        write("service.onDestroy", "called", details = "counts=${counterText()}")
        started = false
        main.removeCallbacks(heartbeat)
        recap?.stop()
        recap = null
        subscriptions?.stop()
        subscriptions = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        write("service.cleanupComplete", "returned")
        counts.clear()
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun acceptDemoNavigation(json: String): Boolean = BuildConfig.DEBUG && started && recap?.acceptDemoNavigation(json) == true

    fun deleteUserRecords() {
        recap?.clearRecords() ?: RecapRepository.get(this).deleteRecords()
        counts.values.forEach { it.set(0) }
        getSystemService(NotificationManager::class.java).cancel(READY_NOTIFICATION_ID)
    }

    private fun recordingNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("오운복 · 기록 중")
            .setContentText("위치 없이 구간 요약만 저장합니다. 주차 후 복기를 확인하세요.")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
        if (recap?.gear == DriveGear.PARK) {
            val stop = PendingIntent.getService(this, 0, Intent(this, DriveRecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "기록 중지", stop).build())
        }
        return builder.build()
    }

    private fun updateRecordingNotification() {
        if (started) try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, recordingNotification()) }
        catch (error: Exception) { write("notification.error", error.javaClass.simpleName, "error") }
    }

    private fun recapReady() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recap_ready", "복기 준비", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("openRecap", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val count = visibleReviewCards(RecapRepository.get(this).day().cards).size
        try {
            manager.notify(READY_NOTIFICATION_ID, Notification.Builder(this, "recap_ready")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("오늘 복기가 준비됐어요")
                .setContentText("오늘의 후보 $count 개 · 정차 후 확인해 주세요.").setContentIntent(open).setAutoCancel(true).build())
        } catch (error: Exception) { write("notification.error", error.javaClass.simpleName, "error") }
    }

    private fun counterText() = counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.get()}" }

    private fun write(signal: String, value: Any?, source: String = "lifecycle", details: String = "",
        at: Long = SystemClock.elapsedRealtime()) = DriveTrialLog.write(signal, value, source, "run=$run $details".trimEnd(), at)

    companion object {
        const val ACTION_START = "lab.pleos.observe.drive.START"
        const val ACTION_STOP = "lab.pleos.observe.drive.STOP"
        const val EXTRA_DEMO_MISSING_SPEED = "demoMissingSpeed"
        private const val READY_NOTIFICATION_ID = 5002
        @Volatile var live: DriveRecorderService? = null
            private set
        private const val CHANNEL = "drive_recorder_trial"
        private const val NOTIFICATION_ID = 4001
    }
}
