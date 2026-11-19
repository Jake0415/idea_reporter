package collector.config

import kotlinx.serialization.json.Json
import java.io.File

/** 출처 설정 읽기: 사용자 값이 있으면 그것, 없으면 리소스 기본값. */
object ConfigLoader {
    const val DEFAULT_RESOURCE = "/sources.default.json"

    val json: Json = Json {
        classDiscriminator = "id"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun parse(text: String): SourcesFile = json.decodeFromString(SourcesFile.serializer(), text)

    fun loadDefault(): SourcesFile {
        val text = ConfigLoader::class.java.getResourceAsStream(DEFAULT_RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("리소스 $DEFAULT_RESOURCE 를 찾을 수 없습니다")
        return parse(text)
    }

    /** override 파일이 있으면 그것을, 없으면 기본값을 쓴다. (앱에서는 Room에 저장한 사용자 값을 넘긴다) */
    fun load(overrideFile: File?): SourcesFile =
        if (overrideFile != null && overrideFile.isFile) parse(overrideFile.readText(Charsets.UTF_8)) else loadDefault()
}
