package collector.dev

import collector.port.SecretProvider
import java.io.File

/**
 * PC 실행 전용 .env 읽기. 형식: KEY=VALUE (한 줄에 하나, # 주석, 빈 줄 무시, 값의 바깥 따옴표 제거).
 * 빈 값은 "없음"으로 본다. 앱에서는 쓰지 않는다(Keystore 사용).
 */
object DotEnv {
    private val keyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(text: String): Map<String, String> = buildMap {
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!keyPattern.matches(key)) continue
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isNotEmpty()) put(key, value)
        }
    }

    fun load(file: File): Map<String, String> =
        if (file.isFile) parse(file.readText(Charsets.UTF_8)) else emptyMap()

    /** 환경 변수 우선, 없으면 .env 값. 값은 로그에 남기지 않는다. */
    fun secretProvider(dotenv: Map<String, String>, env: (String) -> String? = System::getenv): SecretProvider =
        SecretProvider { name -> env(name)?.takeIf { it.isNotBlank() } ?: dotenv[name] }
}
