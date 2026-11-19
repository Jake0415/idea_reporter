package collector.source

import collector.config.SourceConfig
import collector.model.Item
import java.time.Instant

/** 출처 하나에서 수집하고 공통 형식(Item)으로 바꾼다. */
interface SourceAdapter {
    val id: String

    /** [now] 기준 config.window() 안의 항목을 가져온다. 실패하면 예외를 던진다(파이프라인이 격리). */
    suspend fun fetch(config: SourceConfig, now: Instant): List<Item>
}
