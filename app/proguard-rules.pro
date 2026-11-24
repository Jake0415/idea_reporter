# WorkManager constructs these workers by class name.
-keep class app.ideacollector.work.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Angus discovers these providers and handlers via service files and mailcap.
-keep class org.eclipse.angus.mail.smtp.SMTPTransport { public <init>(...); }
-keep class org.eclipse.angus.mail.** extends jakarta.mail.Provider { public <init>(); }
-keep class org.eclipse.angus.mail.util.MailStreamProvider { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_plain { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_html { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.multipart_mixed { public <init>(); }
-keep class org.eclipse.angus.activation.MailcapRegistryProviderImpl { public <init>(); }
-keep class org.eclipse.angus.activation.MimeTypeRegistryProviderImpl { public <init>(); }

# Compile-time annotation and the optional SASL branch that SMTP never enables.
-dontwarn com.google.auto.value.AutoValue
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.sasl.**
