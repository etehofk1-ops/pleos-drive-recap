package lab.pleos.observe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.*
import android.os.Handler
import android.os.Looper
import lab.pleos.observe.logic.ProbeAreaState
import lab.pleos.observe.logic.ProbeResult
import lab.pleos.observe.logic.ProbeSignalState
import lab.pleos.observe.logic.probeRaw

private typealias Emit = (Any?) -> Unit
private typealias Fail = (Exception) -> Unit
private typealias Read = (Emit, Fail) -> Unit
private typealias Listen = (Emit, Fail) -> Unit

/** All calls/listener names verified from the cached Vehicle 2.3.2 classes.jar with javap.
 * AAR SHA-256 e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937.
 * Exact signatures, annotations and absent methods: docs/WEEK3_PROBE.md.
 * Borrows the existing Vehicle: never initializes/releases the game's SDK session.
 */
class SignalProbeClient(private val vehicle: Vehicle, private val onState: (List<ProbeSignalState>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var generation = 0
    private val signals = linkedMapOf<String, ProbeSignalState>()
    private val readers = linkedMapOf<String, () -> Unit>()
    private val readVersions = mutableMapOf<String, Int>()
    private val cleanup = mutableListOf<() -> Unit>()

    fun start() {
        if (running) return
        running = true
        ++generation
        listOf(
            ProbeSignalState("distanceDriven", "Odometer / Distance Driven", "Float; 단위 메타데이터 없음"),
            ProbeSignalState("remainingRange", "Odometer / Remaining Range", "Float; 단위 메타데이터 없음", capMissing = true),
            ProbeSignalState("batterySoc", "EV Battery / State Of Charge", "Int; 단위 메타데이터 없음"),
            ProbeSignalState("chargeSwitch", "EV Battery / Charge Switch", "Boolean", notifyMissing = true),
            ProbeSignalState("ambientBrightnessColor", "Light / Ambient Brightness Color", "Int; 색상 해석 안 함"),
            ProbeSignalState("ambientBrightness", "Light / Ambient Brightness", "Int; 단위 메타데이터 없음"),
            ProbeSignalState("ambientState", "Light / Ambient State", "LightState"),
            ProbeSignalState("doorPosition", "Door / Position", "Int; 단위 메타데이터 없음"),
            ProbeSignalState("seatOccupancy", "Seat / Occupancy", "Boolean"),
            ProbeSignalState("wiperSwitch", "Wiper / Switch", "WiperSwitch", notifyMissing = true),
            ProbeSignalState("wiperState", "Wiper / State", "WiperState"),
        ).forEach { signals[it.id] = it }
        signals.values.forEach { signal ->
            if (signal.capMissing) EvidenceLog.probe(signal.id, "cap", "SDK에 없음", "GLOBAL", EvidenceLog.now(), "sdk_absent")
            if (signal.notifyMissing) EvidenceLog.probe(signal.id, "notify", "SDK에 없음", "GLOBAL", EvidenceLog.now(), "sdk_absent")
        }
        publish()
        // A failed domain accessor must not stop the other domains being tested.
        domain("odometer") { configureOdometer() }
        domain("evBattery") { configureBattery() }
        domain("light") { configureLights() }
        domain("door") { configureDoors() }
        domain("seat") { configureSeats() }
        domain("wiper") { configureWipers() }
    }

    private fun configureOdometer() {
        val api = vehicle.getOdometer()
        scalar("distanceDriven", { ok, fail -> api.checkCurrentDistanceDrivenCapabilities(ok, fail) },
            { ok, fail -> api.getCurrentDistanceDriven({ ok(it) }, fail) },
            { ok, fail ->
                val listener = object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(value: Float?) = ok(value)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerDistanceDriven(listener) }, { api.unregisterDistanceDriven(listener) })
            })
        // No Remaining Range capability method exists in Odometer 2.3.2.
        // GET/register/unregister are deprecated but present; deliberately probe this SDK
        // path as requested instead of substituting AAOS RANGE_REMAINING.
        bind("remainingRange", "GLOBAL", { ok, fail -> api.getRangeRemainingDistance({ ok(it) }, fail) },
            { ok, fail ->
                val listener = object : RangeRemainingListener {
                    override fun onRangeRemainingChanged(rangeRemaining: Float?) = ok(rangeRemaining)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerRangeRemainingListener(listener) }, { api.unregisterRangeRemainingListener(listener) })
            })
    }

    private fun configureBattery() {
        val api = vehicle.getEvBattery()
        scalar("batterySoc", { ok, fail -> api.checkEvBatteryStateOfChargeCapabilities(ok, fail) },
            { ok, fail -> api.getEvBatteryStateOfCharge({ ok(it) }, fail) },
            { ok, fail ->
                val listener = object : EvBatteryStateOfChargeListener {
                    override fun onEvBatteryStateOfChargeUpdated(value: Int?) = ok(value)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerEvBatteryStateOfCharge(listener) }, { api.unregisterEvBatteryStateOfCharge(listener) })
            })
        scalar("chargeSwitch", { ok, fail -> api.checkEvChargeSwitchCapabilities(ok, fail) },
            { ok, fail -> api.getEvChargeSwitchState({ ok(it) }, fail) }, null)
    }

    private fun configureLights() {
        val api = vehicle.getLight()
        val fallback = SeatValue.values().map { SeatArea(it) }
        areas("ambientBrightnessColor", fallback, ::seatName,
            { ok, fail -> api.checkAmbientLightBrightnessColorCapabilities({ config, supported ->
                ok(config, supported.map { it to "" })
            }, fail) },
            { area, ok, fail -> api.getAmbientLightBrightnessColor(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : AmbientLightBrightnessColorListener {
                    override fun onBrightnessColorUpdated(argbColor: Int?) = ok(argbColor)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerAmbientLightBrightnessColorListener(area, listener) },
                    { api.unregisterAmbientLightBrightnessColorListener(area, listener) })
            })
        areas("ambientBrightness", fallback, ::seatName,
            { ok, fail -> api.checkAmbientLightBrightnessCapabilities({ config, supported ->
                ok(config, supported.map { (area, bounds) -> area to "minValue=${bounds.minValue},maxValue=${bounds.maxValue}" })
            }, fail) },
            { area, ok, fail -> api.getAmbientLightBrightness(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : AmbientLightBrightnessListener {
                    override fun onBrightnessUpdated(brightness: Int?) = ok(brightness)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerAmbientLightBrightnessListener(area, listener) },
                    { api.unregisterAmbientLightBrightnessListener(area, listener) })
            })
        areas("ambientState", fallback, ::seatName,
            { ok, fail -> api.checkAmbientLightStateCapabilities({ config, supported ->
                ok(config, supported.map { (area, states) -> area to states.joinToString(",") { it.name } })
            }, fail) },
            { area, ok, fail -> api.getAmbientLightState(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : LightStateListener {
                    override fun onLightStateUpdated(state: LightState?) = ok(state)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerAmbientLightStateListener(area, listener) },
                    { api.unregisterAmbientLightStateListener(area, listener) })
            })
    }

    private fun configureDoors() {
        val api = vehicle.getDoor()
        val fallback = DoorValue.values().filter { it.name.startsWith("ROW_") }.map { DoorArea(it) }
        areas("doorPosition", fallback, ::doorName,
            { ok, fail -> api.checkDoorPositionCapabilities({ config, supported ->
                ok(config, supported.map { (area, bounds) -> area to "minValue=${bounds.minValue},maxValue=${bounds.maxValue}" })
            }, fail) },
            { area, ok, fail -> api.getCurrentDoorPosition(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = ok(pos)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerDoorPosition(area, listener) }, { api.unregisterDoorPosition(area, listener) })
            }, select = { area -> area.values.isNotEmpty() && area.values.all { it.name.startsWith("ROW_") } })
    }

    private fun configureSeats() {
        val api = vehicle.getSeat()
        areas("seatOccupancy", SeatValue.values().map { SeatArea(it) }, ::seatName,
            { ok, fail -> api.checkSeatOccupancyCapabilities({ config, supported -> ok(config, supported.map { it to "" }) }, fail) },
            { area, ok, fail -> api.getCurrentSeatOccupancy(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) = ok(occupancy)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerSeatOccupancy(area, listener) }, { api.unregisterSeatOccupancy(area, listener) })
            })
    }

    private fun configureWipers() {
        val api = vehicle.getWiper()
        val fallback = listOf(WindowArea(WindowValue.FRONT_WINDSHIELD), WindowArea(WindowValue.REAR_WINDSHIELD))
        areas("wiperSwitch", fallback, ::windowName,
            { ok, fail -> api.checkWiperSwitchCapabilities({ config, supported ->
                ok(config, supported.map { (area, states) -> area to states.joinToString(",") { it.name } })
            }, fail) },
            { area, ok, fail -> api.getCurrentWiperSwitch(area, { ok(it) }, fail) }, null)
        areas("wiperState", fallback, ::windowName,
            { ok, fail -> api.checkWiperStateCapabilities({ config, supported ->
                ok(config, supported.map { (area, states) -> area to states.joinToString(",") { it.name } })
            }, fail) },
            { area, ok, fail -> api.getCurrentWiperState(area, { ok(it) }, fail) },
            { area, ok, fail ->
                val listener = object : WiperStateListener {
                    override fun onWiperStateUpdated(state: WiperState?) = ok(state)
                    override fun onFailed(e: Exception) = fail(e)
                }
                arm({ api.registerWiperState(area, listener) }, { api.unregisterWiperState(area, listener) })
            })
    }

    private fun scalar(id: String, check: ((CapabilityConfig) -> Unit, Fail) -> Unit, read: Read, listen: Listen?) {
        signals[id] = signals.getValue(id).copy(areas = listOf(ProbeAreaState("GLOBAL")))
        val epoch = generation
        val failed: Fail = { error -> post(epoch) {
            cap(id, null, error)
            bind(id, "GLOBAL", read, listen) // A capability failure is not proof that GET is unsupported.
        } }
        try {
            check({ config -> post(epoch) {
                cap(id, "accessType=${config.accessType.name}", null)
                if (readable(config)) bind(id, "GLOBAL", read, listen)
                else updateArea(id, "GLOBAL") { it.copy(getStatus = "cap READ 아님", subscription = "cap READ 아님") }
            } }, failed)
        } catch (error: Exception) { failed(error) }
    }

    private fun <A> areas(id: String, fallback: List<A>, name: (A) -> String,
        check: ((CapabilityConfig, List<Pair<A, String>>) -> Unit, Fail) -> Unit,
        read: (A, Emit, Fail) -> Unit, listen: ((A, Emit, Fail) -> Unit)?, select: (A) -> Boolean = { true }) {
        signals[id] = signals.getValue(id).copy(areas = fallback.map { ProbeAreaState(name(it)) })
        val epoch = generation
        fun bindArea(area: A) = bind(id, name(area), { ok, fail -> read(area, ok, fail) },
            listen?.let { subscribe -> { ok, fail -> subscribe(area, ok, fail) } })
        val failed: Fail = { error -> post(epoch) {
            cap(id, null, error)
            fallback.filter(select).forEach { bindArea(it) }
        } }
        try {
            check({ config, supported -> post(epoch) {
                cap(id, "accessType=${config.accessType.name};areas=" + supported.joinToString(";") { (area, details) -> "${name(area)}{$details}" }, null)
                signals[id] = signals.getValue(id).copy(areas = fallback.map { ProbeAreaState(name(it),
                    getStatus = "미지원(cap 영역에 없음 또는 READ 아님)", subscription = "미지원(cap 영역에 없음 또는 READ 아님)") })
                if (readable(config)) supported.map { it.first }.distinct().filter(select).forEach { bindArea(it) }
                publish()
            } }, failed)
        } catch (error: Exception) { failed(error) }
        publish()
    }

    private fun bind(id: String, area: String, read: Read, listen: Listen?) {
        val key = "$id/$area"
        if (readers.containsKey(key)) return
        updateArea(id, area) { it.copy(getStatus = "조회 요청 / 미수신", subscription = if (listen == null) "SDK에 없음" else "등록 호출 중") }
        val epoch = generation
        readers[key] = {
            val ticket = (readVersions[key] ?: 0) + 1
            readVersions[key] = ticket
            try { read({ receive(epoch, id, area, "get", it, null, ticket) }, { receive(epoch, id, area, "get", null, it, ticket) }) }
            catch (error: Exception) { receive(epoch, id, area, "get", null, error, ticket) }
        }
        if (listen != null) {
            try {
                listen({ receive(epoch, id, area, "notify", it, null) }, { receive(epoch, id, area, "notify", null, it) })
                updateArea(id, area) { it.copy(subscription = "등록 호출 반환 / 콜백 미수신") }
            } catch (error: Exception) { receive(epoch, id, area, "notify", null, error) }
        }
        readers.getValue(key).invoke()
    }

    private fun receive(epoch: Int, id: String, area: String, phase: String, value: Any?, error: Exception?, ticket: Int? = null) {
        val at = EvidenceLog.now()
        val raw = probeRaw(value)
        post(epoch) {
            val stale = ticket != null && readVersions["$id/$area"] != ticket
            EvidenceLog.probe(id, phase, raw, area, at, if (stale) "stale" else if (error != null) "error" else "received")
            if (error != null) EvidenceLog.error("probe.$id.$phase.failed", error)
            if (!stale) updateArea(id, area) {
                val result = ProbeResult(raw, at, error?.javaClass?.simpleName)
                if (phase == "get") it.copy(get = result, getStatus = if (error == null) "수신" else "조회 오류")
                else it.copy(notify = result, subscription = if (error == null) "콜백 수신" else "구독 오류")
            }
        }
    }

    private fun cap(id: String, raw: String?, error: Exception?) {
        val at = EvidenceLog.now()
        EvidenceLog.probe(id, "cap", raw ?: "null", "GLOBAL", at, if (error == null) "received" else "error")
        if (error != null) EvidenceLog.error("probe.$id.cap.failed", error)
        signals[id] = signals.getValue(id).copy(capability = ProbeResult(raw ?: "null", at, error?.javaClass?.simpleName))
        publish()
    }

    private fun updateArea(id: String, area: String, update: (ProbeAreaState) -> ProbeAreaState) {
        val signal = signals.getValue(id)
        val before = signal.areas.firstOrNull { it.area == area } ?: ProbeAreaState(area)
        val next = update(before)
        signals[id] = signal.copy(areas = if (signal.areas.any { it.area == area }) signal.areas.map { if (it.area == area) next else it } else signal.areas + next)
        publish()
    }

    private fun arm(register: () -> Unit, unregister: () -> Unit) {
        cleanup += unregister // Preserve the exact area/listener even if registration partially fails.
        register()
    }

    fun readCurrent() { if (running) readers.values.toList().forEach { it() } }

    private fun domain(name: String, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            EvidenceLog.error("probe.$name.setup.failed", error)
            val ids = when (name) {
                "odometer" -> listOf("distanceDriven", "remainingRange")
                "evBattery" -> listOf("batterySoc", "chargeSwitch")
                "light" -> listOf("ambientBrightnessColor", "ambientBrightness", "ambientState")
                "door" -> listOf("doorPosition")
                "seat" -> listOf("seatOccupancy")
                else -> listOf("wiperSwitch", "wiperState")
            }
            ids.forEach { cap(it, null, error) }
        }
    }

    private fun readable(config: CapabilityConfig) = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
    private fun seatName(area: SeatArea) = area.values.joinToString(",") { it.name }
    private fun doorName(area: DoorArea) = area.values.joinToString(",") { it.name }
    private fun windowName(area: WindowArea) = area.values.joinToString(",") { it.name }
    private fun post(epoch: Int, action: () -> Unit) { main.post { if (running && generation == epoch) action() } }
    private fun publish() = onState(signals.values.toList())

    fun stop() {
        running = false
        ++generation
        cleanup.asReversed().forEach { cancel -> try { cancel() } catch (error: Exception) { EvidenceLog.error("probe.unregister.failed", error) } }
        cleanup.clear()
        readers.clear()
        readVersions.clear()
        signals.clear()
        // Borrowed Vehicle remains alive for the existing observation/game client.
    }
}
