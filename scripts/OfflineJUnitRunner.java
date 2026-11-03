import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Counts assumption skips separately: JUnit's ordinary OK total includes them. */
class OfflineJUnitRunner {
    public static void main(String[] args) throws Exception {
        Class<?>[] tests = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) tests[i] = Class.forName(args[i]);
        int[] assumptions = {0};
        JUnitCore core = new JUnitCore();
        core.addListener(new TextListener(System.out));
        core.addListener(new RunListener() {
            @Override public void testAssumptionFailure(Failure failure) {
                assumptions[0]++;
                System.out.println("\nSKIP " + failure.getDescription());
            }
        });
        Result result = core.run(tests);
        System.out.printf("TOTAL executed=%d passed=%d failed=%d skipped=%d (assumptions=%d ignored=%d)%n",
            result.getRunCount(), result.getRunCount() - result.getFailureCount() - assumptions[0],
            result.getFailureCount(), assumptions[0] + result.getIgnoreCount(), assumptions[0], result.getIgnoreCount());
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}
