package app.ideacollector

import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 개인 빌드(`-PembedEnv`)에 들어간 `.env` 키를 첫 실행 때 Keystore로 옮긴다(2026-11-26 사용자 결정).
 * 자산이 없으면(일반 빌드) 아무것도 하지 않는다. 같은 빌드의 자산은 한 번만 옮기고, 새로 빌드한 자산이면 다시 덮어쓴다.
 * 값은 로그·화면에 남기지 않는다.
 */
internal object EnvSeed {
    const val ASSET = "env-seed.properties"
    private val keys = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "MAIL_TO", "SMTP_USERNAME",
        "SMTP_PASSWORD", "REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET", "PRODUCTHUNT_TOKEN")
    private val mailKeys = listOf("MAIL_TO", "SMTP_USERNAME", "SMTP_PASSWORD")

    /** 옮긴 키 개수. 자산이 없거나 이미 옮겼으면 0. */
    suspend fun import(app: CollectorApp, bytes: ByteArray? = null): Int = withContext(Dispatchers.IO) {
        val seed = bytes ?: try {
            app.assets.open(ASSET).use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return@withContext 0
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(seed).joinToString("") { "%02x".format(it) }
        val prefs = app.getSharedPreferences("env-seed", 0)
        if (prefs.getString("imported", null) == hash) return@withContext 0
        val properties = Properties().apply { seed.inputStream().reader(Charsets.UTF_8).use(::load) }
        val values = keys.mapNotNull { name -> properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it } }
        var imported = 0
        val confirmation = app.getSharedPreferences("key-confirmation", 0).edit()
        values.forEach { (name, value) ->
            if (app.secrets.set(name, value)) {
                imported++
                confirmation.remove("failed-$name")
            }
        }
        confirmation.apply()
        if (mailKeys.all { name -> values.any { it.first == name } }) app.mailEnabled = true
        if (imported == values.size) prefs.edit().putString("imported", hash).apply()
        imported
    }
}
