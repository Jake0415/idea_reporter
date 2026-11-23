package app.ideacollector

import androidx.room.withTransaction
import app.ideacollector.data.Artifact
import app.ideacollector.data.CardRow
import app.ideacollector.data.CardSearch
import app.ideacollector.data.SyncState
import app.ideacollector.github.contractJson
import app.ideacollector.github.sha256
import collector.model.*
import collector.pipeline.PipelineResult
import collector.prompt.PromptCatalog
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal suspend fun seedSample(app: CollectorApp) {
    val dao = app.database.dao()
    val zone = ZoneId.of("Asia/Seoul")
    val date = LocalDate.now(zone)
    val now = date.atTime(7, 3).atZone(zone).toInstant()
    val samples = listOf(
        Triple("로컬 회의록 도우미", "회의 내용을 기기에서 정리하고 할 일을 찾아 줍니다.", "업무 자동화"),
        Triple("코드 리뷰 인수인계", "리뷰에서 결정한 내용을 다음 작업자에게 전달합니다.", "개발 도구"),
        Triple("상점 문의 정리함", "자주 오는 고객 문의를 모아 답변 초안을 준비합니다.", "고객 지원"),
        Triple("개인 문서 찾기", "여러 폴더에 흩어진 문서를 문장으로 찾습니다.", "개인 생산성"),
        Triple("학습 메모 복습", "수업 메모에서 이번 주 복습할 질문을 만듭니다.", "교육")
    )
    val cards = samples.mapIndexed { index, (title, summary, category) ->
        IdeaCard("sample-card-${index + 1}", if (index == 4) date.minusDays(1).toString() else date.toString(),
            title, summary, "관련 도구가 늘고 반복 작업을 줄여 달라는 요청이 이어집니다.",
            "자료를 여러 곳에서 찾아 옮기느라 중요한 결정을 놓치기 쉽습니다.",
            listOf(SignalRef("demo:tech", "https://example.invalid/sample/technology", "기기에서 처리하는 도구로 작은 시제품을 만들 수 있습니다.")),
            listOf(SignalRef("demo:demand", "https://example.invalid/sample/feedback", "반복 정리에 시간이 든다는 사용자 의견이 나왔습니다.")),
            listOf(SignalRef("demo:market", "https://example.invalid/sample/product", "기존 제품은 팀 단위 사용을 중심으로 지원합니다.")),
            listOf("지난주에도 수작업을 줄이는 비슷한 요청이 있었습니다."),
            "1인 4주", "초기 설정 없이 작은 팀이 쓰기 쉽도록 구성합니다.", "자동 결과를 신뢰하기 어렵다는 점",
            "사용자 5명이 실제 작업에 써 보고 시간을 비교합니다.", listOf("작은 표본의 의견입니다."),
            category, listOf(if (index == 0) "여러 곳에서 화제" else "급상승"), listOf("자동화"))
    }
    val tracks = cards.take(3).mapIndexed { index, card ->
        TrackedIdea("sample-track-${index + 1}", card.id, card.title, card.category, listOf(card.title, "자동화"),
            listOf("팀 문서 정리 도구"), if (index == 2) TrackedIdea.Status.QUIET else TrackedIdea.Status.TRACKING,
            now.minusSeconds(86_400 * 35), if (index == 2) null else now, (7 - index * 2).toDouble(), TrackedIdea.Trend.entries[index])
    }
    val updates = tracks.take(2).mapIndexed { index, track ->
        TrackingUpdate("sample-update-$index", track.id, date.toString(),
            "관련 프로젝트에서 새 기능을 공개했고 사용자 의견이 모이기 시작했습니다.",
            listOf(TrackingUpdate.Change("product", "새 기능 공개", null)), emptyList(), track.strength, track.trend)
    }
    val report = RunReport(date.toString(), now.toString(),
        listOf(SourceResult("GitHub", 240, millis = 320), SourceResult("Hacker News", 172, millis = 240),
            SourceResult("Reddit", 0, "시간 초과", 2000)),
        listOf(StageResult("collect", 0, 412, 2000), StageResult("select", 412, 18, 100)),
        emptyMap(), 2200, cardCount = 4, trackingUpdateCount = 2, issues = listOf("일부 출처에서 응답이 오지 않았습니다."),
        classificationBatchWaitMillis = 1200)
    suspend fun save(path: String, kind: String, json: String) {
        dao.saveArtifact(Artifact(SAMPLE_REPO, path, kind, json.toByteArray().sha256(), json))
    }
    app.database.withTransaction {
        dao.clearSampleRequests()
        dao.artifacts(SAMPLE_REPO).forEach { artifact ->
            dao.deleteCards(SAMPLE_REPO, artifact.path)
            dao.deleteIndex(SAMPLE_REPO, artifact.path)
            dao.deleteArtifact(SAMPLE_REPO, artifact.path)
        }
        cards.groupBy { it.runDate }.forEach { (day, group) ->
            val path = "out/$day.json"
            val startedAt = LocalDate.parse(day).atTime(7, 3).atZone(zone).toInstant().toString()
            val dayReport = report.copy(runDate = day, startedAt = startedAt, cardCount = group.size,
                trackingUpdateCount = updates.count { it.runDate == day })
            val daily = PipelineResult(emptyList(), emptyList(),
                dayReport, cards = group, trackingUpdates = updates.filter { it.runDate == day })
            save(path, "daily", contractJson.encodeToString(daily))
            save("out/runs/$day-${startedAt.replace(':', '-')}.json", "run", contractJson.encodeToString(dayReport))
            dao.saveCards(group.map { CardRow(SAMPLE_REPO, path, it.id, contractJson.encodeToString(it)) })
            dao.indexCards(group.map { CardSearch(SAMPLE_REPO, path, it.id, it.title, it.oneLiner, it.problem) })
        }
        save("out/tracked.json", "tracking", contractJson.encodeToString(tracks))
        save("config/prompts/track.md", "prompt", PromptCatalog.defaultText("track") + "\n매일 새 소식을 짧게 정리합니다.")
        dao.saveState(SyncState(SAMPLE_REPO, "", null, Instant.now().toString()))
        dao.saveSettings(app.settings().copy(repository = SAMPLE_REPO))
    }
}
