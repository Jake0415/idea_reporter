package collector.llm

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest

internal class BatchMockServer : AutoCloseable {
    private val server = MockWebServer()
    @Volatile private var ids = emptyMap<String, String>()
    private val queue = object : QueueDispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "POST" && request.path == "/v1/messages/batches" && request.bodySize > 0) {
                val submitted = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject.getValue("requests").jsonArray
                ids = submitted.map { it.jsonObject.getValue("custom_id").jsonPrimitive.content }
                    .associateBy { it.replace(Regex("-[0-9a-f]{8}-(?=[0-9]+$)"), "-") }
            }
            val response = super.dispatch(request)
            if (request.path?.endsWith("/results") == true) {
                var body = response.getBody()?.readUtf8() ?: return response
                for ((fixtureId, actualId) in ids) {
                    body = body.replace(JsonPrimitive(fixtureId).toString(), JsonPrimitive(actualId).toString())
                }
                response.setBody(body)
            }
            return response
        }
    }

    init {
        queue.setFailFast(true)
        server.dispatcher = queue
    }

    fun enqueue(response: MockResponse) = queue.enqueueResponse(response)
    fun url(path: String) = server.url(path)
    fun takeRequest() = server.takeRequest()
    val requestCount get() = server.requestCount
    override fun close() = server.close()
}
