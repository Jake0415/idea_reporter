package app.ideacollector;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

// Run the R8 smoke tests without linking an unshrunk AndroidX runner to the app's Kotlin runtime.
public class ShrunkSdkInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        ShrunkSdkDeviceTest smoke = new ShrunkSdkDeviceTest(getTargetContext());
        boolean anthropic = test(1, "anthropicUsesShrunkSdk", smoke::anthropicUsesShrunkSdk);
        boolean openAi = test(2, "openAiUsesShrunkSdk", smoke::openAiUsesShrunkSdk);
        boolean smtp = test(3, "smtpInitializesShrunkTransportWithRequiredTls",
            smoke::smtpInitializesShrunkTransportWithRequiredTls);
        Bundle result = new Bundle();
        result.putString("stream", anthropic && openAi && smtp ? "\nOK (3 tests)\n" : "\nSDK smoke tests failed\n");
        finish(Activity.RESULT_OK, result);
    }

    private boolean test(int number, String name, TestCase check) {
        Bundle status = new Bundle();
        status.putString("id", "ShrunkSdkInstrumentation");
        status.putString("class", ShrunkSdkDeviceTest.class.getName());
        status.putString("test", name);
        status.putInt("current", number);
        status.putInt("numtests", 3);
        sendStatus(1, status);
        try {
            check.run();
            sendStatus(0, status);
            return true;
        } catch (Throwable failure) {
            status.putString("stack", Log.getStackTraceString(failure));
            sendStatus(-2, status);
            return false;
        }
    }

    private interface TestCase {
        void run() throws Exception;
    }
}
