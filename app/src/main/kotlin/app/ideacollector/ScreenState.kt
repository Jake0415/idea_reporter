package app.ideacollector

import app.ideacollector.data.Artifact
import app.ideacollector.data.RequestRow
import app.ideacollector.data.AppDao
import androidx.lifecycle.ViewModel
import app.ideacollector.github.contractJson
import collector.data.InboxRequest
import collector.model.Feedback
import collector.model.IdeaCard
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

internal class ScreensViewModel(private val dao: AppDao) : ViewModel() {
    fun observe(repository: String) = combine(dao.observeArtifacts(repository), dao.observeRequests(repository),
        ::ScreenState).flowOn(Dispatchers.Default)
}

internal enum class RequestStatus(val label: Int) {
    PENDING(R.string.pending), DISPATCHING(R.string.pending), SENT(R.string.sent),
    APPLIED(R.string.applied), NEEDS_AUTH(R.string.needs_token), CANCELLED(R.string.cancelled),
    REJECTED(R.string.rejected), INTERNAL_ERROR(R.string.rejected), FAILED(R.string.request_failed),
    NEEDS_CONFIRMATION(R.string.request_check), APP_UPDATE_REQUIRED(R.string.update_required);

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: FAILED
    }
}

internal fun requestLabel(status: String) = RequestStatus.from(status).label
private val visibleStatuses = setOf("PENDING", "DISPATCHING", "NEEDS_AUTH", "SENT", "APPLIED")
internal val optimisticStatuses = visibleStatuses - "APPLIED"

internal fun visibleReaction(rows: List<RequestRow>, card: String, reaction: String, remote: Feedback? = null): Boolean {
    val local = rows.firstOrNull { it.cardId == card && it.reaction != null && it.status in visibleStatuses }
    val useLocal = local != null && (local.status != "APPLIED" ||
        remote?.at?.let { it < Instant.parse(local.createdAt) } != false)
    return (if (useLocal) local.reaction else remote?.kind?.name) == reaction
}

internal fun ftsQuery(text: String): String? =
    Regex("[\\p{L}\\p{N}_]+").findAll(text).map { "\"${it.value}\"*" }.toList()
        .takeIf { it.isNotEmpty() }?.joinToString(" AND ")

internal inline fun <reified T> Artifact.decode(): T = contractJson.decodeFromString(json)
internal fun RequestRow.payload(): InboxRequest = contractJson.decodeFromString(json)
internal fun requestId(): String = UUID.randomUUID().toString()

internal class ScreenState(artifacts: List<Artifact>, val requests: List<RequestRow>) {
    private val payloads = requests.associate { it.requestId to runCatching { it.payload() }.getOrNull() }
    fun payload(row: RequestRow) = payloads[row.requestId]
    val daily = artifacts.filter { it.kind == "daily" }.sortedByDescending { it.path }.map { it.decode<PipelineResult>() }
    val cards = daily.flatMap { it.cards }.distinctBy { it.id }
    val tracks = artifacts.firstOrNull { it.kind == "tracking" }?.decode<List<TrackedIdea>>().orEmpty()
    val updates = daily.flatMap { it.trackingUpdates }.distinctBy { it.id }
    val runs = artifacts.filter { it.kind == "run" }.map {
        val json = it.decode<JsonObject>()
        it.path to if ("report" in json) it.decode<PipelineResult>().report else it.decode<RunReport>()
    }.sortedByDescending { it.second.startedAt }
    private val feedback = artifacts.firstOrNull { it.kind == "feedback" }?.decode<List<Feedback>>()
        .orEmpty().distinctBy { it.cardId }.associateBy { it.cardId }
    private val byPath = artifacts.associateBy { it.path }
    val hasBatch: Boolean get() = byPath["out/batch/public/${today()}-classify.json"]?.let { batch ->
        runCatching {
            val checkpoint = batch.decode<JsonObject>()
            checkpoint["rejected"]?.jsonPrimitive?.booleanOrNull != true &&
                checkpoint["settled"]?.jsonPrimitive?.booleanOrNull != true
        }.getOrDefault(false)
    } ?: false
    fun file(path: String) = byPath[path]?.json
    fun prompt(id: String) = file("config/prompts/$id.md") ?: collector.prompt.PromptCatalog.defaultText(id)
    inline fun <reified T : InboxRequest> requestsOf() = requests.filter { payload(it) is T }

    fun cardRequests(id: String) = requests.filter { row -> when (val request = payload(row)) {
        is InboxRequest.Reaction -> request.cardId == id
        is InboxRequest.FeedbackUndo -> request.cardId == id
        is InboxRequest.TrackStart -> request.cardId == id
        is InboxRequest.TrackStop -> tracks.any { it.cardId == id && it.id == request.trackId }
        is InboxRequest.TrackKeywords -> tracks.any { it.cardId == id && it.id == request.trackId }
        else -> false
    } }.distinctBy { payload(it)?.javaClass }

    fun trackRequests(track: TrackedIdea) = requests.filter { row -> when (val request = payload(row)) {
        is InboxRequest.TrackStart -> request.cardId == track.cardId
        is InboxRequest.TrackStop -> request.trackId == track.id
        is InboxRequest.TrackKeywords -> request.trackId == track.id
        else -> false
    } }.distinctBy { payload(it)?.javaClass }

    fun keywords(track: TrackedIdea) = trackRequests(track).firstNotNullOfOrNull {
        if (it.status in optimisticStatuses) (payload(it) as? InboxRequest.TrackKeywords)?.keywords else null
    } ?: track.keywords

    fun activeTrack(card: IdeaCard) = tracks.firstOrNull { it.cardId == card.id && isTracking(it) }
    fun isTracking(track: TrackedIdea): Boolean {
        val newest = trackRequests(track).firstOrNull { row ->
            row.status in visibleStatuses && (payload(row) is InboxRequest.TrackStart || payload(row) is InboxRequest.TrackStop)
        }?.let(::payload)
        return when (newest) {
            is InboxRequest.TrackStart -> true
            is InboxRequest.TrackStop -> false
            else -> track.status != TrackedIdea.Status.STOPPED
        }
    }

    fun starred(card: IdeaCard): Boolean {
        val start = cardRequests(card.id).firstOrNull { payload(it) is InboxRequest.TrackStart }
        return activeTrack(card) != null || (tracks.none { it.cardId == card.id } && start?.status in visibleStatuses)
    }

    val startingCards = cards.filter { card ->
        tracks.none { it.cardId == card.id } &&
            cardRequests(card.id).any { payload(it) is InboxRequest.TrackStart && it.status in visibleStatuses }
    }

    fun hidden(card: IdeaCard) = visibleReaction(requests.filter { it.status == "APPLIED" },
        card.id, "NOT_INTERESTED", feedback[card.id])

    fun dismissed(card: IdeaCard) = visibleReaction(requests, card.id, "NOT_INTERESTED", feedback[card.id])

    fun undoableDismissal(card: IdeaCard): RequestRow? = if (dismissed(card)) {
        cardRequests(card.id).firstOrNull { it.reaction == "NOT_INTERESTED" && it.status in visibleStatuses }
    } else null

    fun filtered(starOnly: Boolean, hide: Boolean, category: String?, matches: Set<String>?): List<IdeaCard> =
        cards.filter {
            (!starOnly || starred(it)) && (!hide || !hidden(it)) &&
                (category == null || it.category == category) && (matches == null || it.id in matches)
        }
}
