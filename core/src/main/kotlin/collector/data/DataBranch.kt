package collector.data

import collector.model.Feedback
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * App contract: write inbox entries as `<ISO timestamp>-<UUID>.json`.
 * For `internal_error` or 확인 필요, submit a new requestId.
 * The only `analyze.yml` input is `requestId`.
 */
object DataBranch {
    // Bump only for changes incompatible with existing data readers or writers.
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val OUT = "out"
    const val PROMPTS = "config/prompts"
    const val SETTINGS = "settings"
    const val INBOX = "inbox"
    const val RESULTS = "inbox-results"
    const val ANALYSIS_QUEUE = "analysis-queue"
}

@Serializable
data class DataManifest(
    val schemaVersion: Int = DataBranch.VERSION,
    val updatedAt: String,
    val latestDaily: String?,
    val latestTrend: String?,
    val files: List<DataFile>
)

@Serializable
data class DataFile(val path: String, val size: Long, val sha256: String)

@Serializable
sealed class InboxRequest {
    abstract val schemaVersion: Int
    abstract val requestId: String
    abstract val createdAt: String

    @Serializable @SerialName("feedback")
    data class Reaction(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val cardId: String, val kind: Feedback.Kind,
        val reason: String, val memo: String? = null) : InboxRequest()

    @Serializable @SerialName("track.start")
    data class TrackStart(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val cardId: String) : InboxRequest()

    @Serializable @SerialName("track.stop")
    data class TrackStop(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val trackId: String) : InboxRequest()

    @Serializable @SerialName("track.keywords")
    data class TrackKeywords(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val trackId: String, val keywords: List<String>) : InboxRequest()

    @Serializable @SerialName("prompt.set")
    data class PromptSet(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val promptId: String, val body: String, val baseHash: String) : InboxRequest()

    @Serializable @SerialName("prompt.reset")
    data class PromptReset(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val promptId: String, val baseHash: String) : InboxRequest()

    @Serializable @SerialName("settings.set")
    data class SettingsSet(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val budget: JsonObject? = null, val schedule: ScheduleSettings? = null) : InboxRequest()

    @Serializable @SerialName("analysis.request")
    data class Analysis(override val schemaVersion: Int, override val requestId: String,
        override val createdAt: String, val themeIds: List<String>, val mode: AnalysisMode,
        val maxUsd: Double) : InboxRequest()
}

@Serializable
enum class AnalysisMode { @SerialName("estimate") ESTIMATE, @SerialName("run") RUN }

@Serializable
data class ScheduleSettings(val enabled: Boolean = true)

@Serializable
data class InboxResult(
    val schemaVersion: Int = DataBranch.VERSION,
    val requestId: String,
    val ok: Boolean,
    val reason: String? = null,
    val output: List<String> = emptyList(),
    val dispatchRunId: String? = null,
    val code: String? = null,
    val processedAt: String? = null,
    val status: String? = null,
    val mailReportId: String? = null
)
