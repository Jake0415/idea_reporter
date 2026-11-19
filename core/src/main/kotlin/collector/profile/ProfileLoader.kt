package collector.profile

import collector.config.SourcesFile
import kotlinx.serialization.json.Json

/**
 * 리소스 profiles/ 아래 프로필을 읽고 검증한다.
 * 구조: profiles/index.json(목록·기본값) + profiles/<id>/{profile.json, match.md, analysis.md}
 */
object ProfileLoader {
    private val json = Json { ignoreUnknownKeys = false }   // 오타 난 필드는 거부
    private val idPattern = Regex("^[a-z][a-z0-9_-]*$")

    class ProfileSet(val profiles: List<Profile>, val defaultId: String) {
        private val byId = profiles.associateBy { it.id }
        val ids: List<String> get() = profiles.map { it.id }
        operator fun get(id: String): Profile? = byId[id]
        val default: Profile get() = byId.getValue(defaultId)
    }

    fun loadDefaults(read: (String) -> String? = ::readResource): ProfileSet {
        val indexText = read("profiles/index.json") ?: error("profiles/index.json 이 없습니다")
        val index = json.decodeFromString(ProfileIndex.serializer(), indexText)
        val profiles = index.profiles.map { id -> loadOne(id, read) }
        val set = ProfileSet(profiles, index.default)
        validate(set).takeIf { it.isNotEmpty() }?.let { error("프로필 설정 오류:\n- " + it.joinToString("\n- ")) }
        return set
    }

    private fun loadOne(id: String, read: (String) -> String?): Profile {
        fun file(name: String) = read("profiles/$id/$name") ?: error("profiles/$id/$name 이 없습니다")
        val config = json.decodeFromString(ProfileConfig.serializer(), file("profile.json"))
        return Profile(config, match = file("match.md"), analysis = file("analysis.md"))
    }

    /** 문제 목록(비어 있으면 정상) */
    fun validate(set: ProfileSet): List<String> = buildList {
        if (set.profiles.isEmpty()) add("프로필이 하나도 없습니다")
        val dup = set.profiles.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) add("중복 id: $dup")
        if (set[set.defaultId] == null) add("기본 프로필 '${set.defaultId}' 이 목록에 없습니다")
        for (p in set.profiles) {
            val c = p.config
            if (!idPattern.matches(c.id)) add("${c.id}: id는 소문자로 시작하고 소문자·숫자·_·- 만 쓸 수 있습니다")
            if (c.threshold !in 0.0..10.0) add("${c.id}: threshold ${c.threshold} 는 0~10 이어야 합니다")
            if (c.maxCandidates < 1) add("${c.id}: maxCandidates 는 1 이상이어야 합니다")
            for ((name, v) in listOf("classifyMaxChars" to c.content.classifyMaxChars, "generateMaxChars" to c.content.generateMaxChars)) {
                if (v !in 500..100_000) add("${c.id}: $name $v 는 500~100000 이어야 합니다")
            }
            if (p.match.isBlank()) add("${c.id}: match.md 가 비어 있습니다")
            if (p.analysis.isBlank()) add("${c.id}: analysis.md 가 비어 있습니다")
        }
    }

    /** 출처 설정의 profile 배정이 실제 프로필을 가리키는지. 문제 목록(비어 있으면 정상) */
    fun validateRouting(sources: SourcesFile, set: ProfileSet): List<String> = buildList {
        for (s in sources.sources) {
            if (s.profile.isEmpty()) add("${s.sourceId}: profile 이 비어 있습니다")
            val unknown = s.profile.filter { set[it] == null }
            if (unknown.isNotEmpty()) add("${s.sourceId}: 알 수 없는 프로필 $unknown")
            if (s.profile.size != s.profile.toSet().size) add("${s.sourceId}: profile 에 중복이 있습니다")
        }
    }

    private fun readResource(path: String): String? =
        ProfileLoader::class.java.getResourceAsStream("/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
}
