# WorkManager constructs these workers by class name.
-keep class app.ideacollector.work.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Android never calls the core CLI's LLM providers or SMTP transport. Their SDKs are excluded from the APK.
-dontwarn com.openai.**
-dontwarn com.anthropic.**
-dontwarn jakarta.mail.**
-dontwarn org.eclipse.angus.mail.smtp.SMTPTransport
