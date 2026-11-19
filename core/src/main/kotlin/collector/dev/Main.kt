package collector.dev

import collector.config.ConfigLoader
import collector.model.DroppedItem
import collector.model.Item
import collector.model.RunReport
import collector.pipeline.Pipeline
import collector.profile.ProfileLoader
import collector.source.GitHubAdapter
import collector.source.HackerNewsAdapter
import collector.source.Http
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * PC 실행 진입점(1단계 확인용).
 *   gradlew :core:run                     실행하고 결과를 '이미 본 글'로 기록
 *   gradlew :core:run --args="--keep-unseen"   기록하지 않음(같은 결과를 반복 확인)
 * 사용자 설정: config/sources.local.json 이 있으면 기본값 대신 사용.
 * 결과: out/<날짜>.json, 이미 본 글: out/seen.json
 */
fun main(args: Array<String>) = runBlocking {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    if ("--llm-smoke" in args) {
        val secrets = DotEnv.secretProvider(DotEnv.load(File(".env")))
        if (!llmSmoke(secrets)) exitProcess(1)
        return@runBlocking
    }
    val keepUnseen = "--keep-unseen" in args

    val sources = ConfigLoader.load(File("config/sources.local.json"))
    val profiles = ProfileLoader.loadDefaults()
    ProfileLoader.validateRouting(sources, profiles).takeIf { it.isNotEmpty() }?.let {
        println("출처 설정 오류:\n- " + it.joinToString("\n- "))
        return@runBlocking
    }
    println("· 프로필: " + profiles.profiles.joinToString { "${it.config.name}(임계값 ${it.config.threshold})" })
    val secrets = DotEnv.secretProvider(DotEnv.load(File(".env")))
    val keyStatus = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN")
        .joinToString { "$it ${if (secrets.get(it) != null) "있음" else "없음"}" }   // 값은 출력하지 않음
    println("· 비밀값: $keyStatus")
    val http = Http.client()
    val seen = JsonFileSeenStore(File("out/seen.json"))
    val pipeline = Pipeline(
        adapters = listOf(HackerNewsAdapter(http), GitHubAdapter(http, secrets)),
        seen = seen,
        onStage = { println("· 단계: $it") }
    )

    val result = pipeline.run(sources)
    printReport(result.report, result.items)

    val outFile = File("out/${result.report.runDate}.json")
    outFile.parentFile.mkdirs()
    val json = Json { prettyPrint = true; encodeDefaults = true }
    outFile.writeText(json.encodeToString(RunOutput.serializer(), RunOutput(result.report, result.items, result.dropped)), Charsets.UTF_8)
    println("\n결과 저장: ${outFile.path}")

    if (!keepUnseen) {
        seen.markSeen(result.items)
        println("이미 본 글로 기록: ${result.items.size}건 (out/seen.json)")
    }
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
}

@Serializable
private data class RunOutput(val report: RunReport, val items: List<Item>, val dropped: List<DroppedItem>)

private fun printReport(report: RunReport, items: List<Item>) {
    println("\n=== 실행 ${report.runDate} (${report.totalMillis}ms) ===")
    println("출처별 수집:")
    report.sources.forEach { s ->
        val status = s.error?.let { "실패 — $it" } ?: "${s.fetched}건"
        println("  ${s.source.padEnd(8)} $status (${s.millis}ms)")
    }
    val funnel = report.stages.joinToString(" → ") { "${it.stage} ${it.output}" }
    println("깔때기: $funnel")
    if (report.dropCounts.isNotEmpty()) {
        println("탈락 이유: " + report.dropCounts.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }
    println("\n상위 항목 (점수 · 출처 · 원점수 · 제목):")
    items.take(30).forEachIndexed { i, it ->
        val also = if (it.alsoSeenIn.isNotEmpty()) " [+${it.alsoSeenIn.joinToString(",")}]" else ""
        println("%2d. %3d · %-6s · %5d · %s%s".format(i + 1, it.score, it.source, it.rawScore, it.title.take(80), also))
        println("      ${it.url}")
    }
    if (items.size > 30) println("  … 외 ${items.size - 30}건")
}
