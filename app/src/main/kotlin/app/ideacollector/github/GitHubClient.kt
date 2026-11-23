package app.ideacollector.github

import collector.data.DataBranch
import collector.data.InboxRequest
import collector.data.InboxResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

const val GITHUB_TOKEN_PERMISSIONS = "선택한 저장소만 · Contents 읽기/쓰기 · Actions 쓰기"
internal val contractJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val jsonMedia = "application/json".toMediaType()
private const val DATA_REF = "data"
private const val RAW = "application/vnd.github.raw+json"
private const val JSON = "application/vnd.github+json"

internal fun validRepository(repository: String) =
    Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+").matches(repository) &&
        repository.substringAfter('/') !in setOf(".", "..")

internal fun inboxPath(request: InboxRequest): String {
    require(request.schemaVersion == DataBranch.VERSION && UUID.fromString(request.requestId).toString() == request.requestId)
    // Match core's Windows-compatible inbox filenames. The JSON timestamp stays in ISO-8601.
    val timestamp = Instant.parse(request.createdAt).toString().replace(":", "")
    return "${DataBranch.INBOX}/$timestamp-${request.requestId}.json"
}

internal fun resultPath(id: String) = "${DataBranch.OUT}/${DataBranch.RESULTS}/$id.json"

internal fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

internal class GitHubFailure(val status: Int, val retryAfter: String? = null, val reset: String? = null,
    val rateLimited: Boolean = false) : IOException("GitHub 요청 실패 ($status)")
private fun Response.failure() = GitHubFailure(code, header("Retry-After"), header("X-RateLimit-Reset"),
    code == 403 && (header("X-RateLimit-Remaining") == "0" || header("Retry-After") != null ||
        peekBody(4096).string().contains("rate limit", ignoreCase = true)))

internal const val FILE_LIMIT = 5L * 1024 * 1024
internal const val SYNC_LIMIT = 50L * 1024 * 1024
internal class FileSizeExceeded : IOException("파일 크기 상한 초과")
internal class UnsupportedSchema : IllegalArgumentException("앱 업데이트 필요")

internal inline fun <reified T> decodeVersioned(body: String): T {
    val json = contractJson.parseToJsonElement(body)
    val version = ((json as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
    if (version != null && version > DataBranch.VERSION) throw UnsupportedSchema()
    return contractJson.decodeFromJsonElement(json)
}

internal data class ManifestResponse(val bytes: ByteArray?, val etag: String?)
internal class ArtifactMismatch : IOException("게시 파일이 manifest와 일치하지 않습니다")

internal class GitHubClient(
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
    private val base: HttpUrl = "https://api.github.com/".toHttpUrl()
) {
    fun dataHead(repo: String, token: String?): String {
        val url = repository(repo).addPathSegments("git/ref/heads/$DATA_REF").build()
        return execute(request(url, token, JSON)) { response ->
            val target = contractJson.parseToJsonElement(read(response).decodeToString()).jsonObject
                .getValue("object").jsonObject
            val sha = target.getValue("sha").jsonPrimitive.content
            require(target.getValue("type").jsonPrimitive.content == "commit" && sha.matches(Regex("[0-9a-f]{40}")))
            sha
        }
    }

    fun manifest(repo: String, token: String?, etag: String?, ref: String): ManifestResponse {
        val request = get(repo, DataBranch.MANIFEST, token, ref).apply {
            etag?.let { header("If-None-Match", it) }
        }
        return execute(request) { response ->
            if (response.code == 304) ManifestResponse(null, etag)
            else ManifestResponse(read(response), response.header("ETag"))
        }
    }

    fun file(repo: String, path: String, token: String?, target: File, size: Long, sha: String, ref: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val received = execute(get(repo, path, token, ref)) { response ->
            DigestOutputStream(target.outputStream(), digest).use { copy(response, size, it) }
        }
        if (received != size || digest.digest().hex() != sha)
            throw ArtifactMismatch()
    }

    fun upload(repo: String, path: String, token: String, payload: String, requestId: String) {
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val body = buildJsonObject {
            put("message", "inbox: $requestId")
            put("content", encoded)
            put("branch", DATA_REF)
        }
        val request = request(contents(repo, path), token, JSON).put(body.toString().toRequestBody(jsonMedia))
        val failure = execute(request) { if (it.code == 201) null else it.failure() } ?: return
        val status = failure.status
        if (status == 422 || status == 409) {
            // A response may be lost after GitHub stored the object. Verify the same bytes before advancing.
            val identical = execute(get(repo, path, token)) {
                if (it.code == 404) false else read(it).contentEquals(payload.toByteArray())
            }
            if (identical || result(repo, requestId, token) != null) return
        }
        throw failure
    }

    fun result(repo: String, id: String, token: String): InboxResult? =
        execute(get(repo, resultPath(id), token)) {
            if (it.code == 404) null else decodeVersioned<InboxResult>(read(it).decodeToString()).also { result ->
                require(result.requestId == id && result.schemaVersion == DataBranch.VERSION)
            }
        }

    enum class DispatchState { ACTIVE, RETRYABLE, UNKNOWN }

    fun analysisDispatchState(repo: String, id: String, token: String, dispatchedAt: Long): DispatchState {
        val since = Instant.ofEpochSecond(dispatchedAt)
        val url = repository(repo).addPathSegments("actions/workflows/analyze.yml/runs")
            .addQueryParameter("event", "workflow_dispatch")
            .addQueryParameter("branch", "main")
            .addQueryParameter("created", ">=" + since)
            .addQueryParameter("per_page", "100")
            .build()
        return execute(request(url, token, JSON)) { response ->
            val runs = contractJson.parseToJsonElement(read(response).decodeToString()).jsonObject
                .getValue("workflow_runs").jsonArray.map { it.jsonObject }
                .map { it to Instant.parse(it.getValue("created_at").jsonPrimitive.content) }
                .filter { (_, createdAt) -> createdAt >= since }
            val title = "Analyze " + id
            val active = setOf("queued", "in_progress", "waiting", "requested", "pending")
            fun JsonObject.title() = getValue("display_title").jsonPrimitive.content
            fun JsonObject.status() = getValue("status").jsonPrimitive.content
            val matching = runs.filter { (run, _) -> run.title() == title }
            val latest = matching.maxByOrNull { it.second }?.first
            when {
                matching.any { (run, _) -> run.status() in active } ->
                    DispatchState.ACTIVE
                response.header("Link")?.contains("rel=\"next\"") == true -> DispatchState.UNKNOWN
                latest?.status() == "completed" && latest["conclusion"]?.jsonPrimitive?.contentOrNull in setOf("cancelled", "failure") ->
                    DispatchState.RETRYABLE
                else -> DispatchState.UNKNOWN
            }
        }
    }

    fun dispatchAnalysis(repo: String, id: String, token: String) {
        val url = repository(repo).addPathSegments("actions/workflows/analyze.yml/dispatches").build()
        val body = buildJsonObject {
            put("ref", "main")
            put("inputs", buildJsonObject { put("requestId", id) })
        }
        execute(request(url, token, JSON).post(body.toString().toRequestBody(jsonMedia))) {
            if (it.code != 204) throw it.failure()
        }
    }

    private fun get(repo: String, path: String, token: String?, ref: String = DATA_REF) =
        request(contents(repo, path).newBuilder().addQueryParameter("ref", ref).build(), token, RAW)

    private fun contents(repo: String, path: String): HttpUrl {
        val segments = path.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it })
        return repository(repo).addPathSegment("contents").apply { segments.forEach { addPathSegment(it) } }.build()
    }

    private fun repository(repo: String): HttpUrl.Builder {
        require(validRepository(repo))
        return base.newBuilder().addPathSegments("repos/$repo")
    }

    private fun request(url: HttpUrl, token: String?, accept: String) = Request.Builder().url(url)
        .header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").apply {
            if (token != null) {
                require(token.all { it in '!'..'~' })
                header("Authorization", "Bearer $token")
            }
        }

    private fun read(response: Response): ByteArray = ByteArrayOutputStream().run {
        copy(response, FILE_LIMIT, this)
        toByteArray()
    }

    private fun copy(response: Response, limit: Long, output: OutputStream): Long {
        if (!response.isSuccessful) throw response.failure()
        val body = response.body ?: throw IOException("빈 응답")
        if (body.contentLength() > limit) throw FileSizeExceeded()
        val input = body.byteStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var received = 0L
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - received + 1).toInt())
            if (count == -1) return received
            received += count
            if (received > limit) throw FileSizeExceeded()
            output.write(buffer, 0, count)
        }
    }

    private fun <T> execute(request: Request.Builder, block: (Response) -> T): T =
        http.newCall(request.build()).execute().use(block)
}
