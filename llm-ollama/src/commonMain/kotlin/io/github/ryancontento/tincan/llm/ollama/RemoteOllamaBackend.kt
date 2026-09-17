package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

class RemoteOllamaBackend internal constructor(
    override val id: BackendId,
    private val baseUrl: String,
    private val client: HttpClient,
    private val modelLoadingThresholdMillis: Long,
) : LlmBackend {

    /** @param modelLoadingThresholdMillis differs hugely between GPU and CPU inference. */
    constructor(
        id: BackendId,
        baseUrl: String,
        modelLoadingThresholdMillis: Long = 2_500,
    ) : this(id, baseUrl, OllamaHttpClient.create(), modelLoadingThresholdMillis)

    private val root = baseUrl.trimEnd('/')

    override suspend fun probe(): BackendHealth {
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val response = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) { client.get("$root/api/tags") }
                ?: return BackendHealth.Unavailable(LlmError.Unreachable)
            if (response.status.isSuccess()) {
                BackendHealth.Available(mark.elapsedNow().inWholeMilliseconds)
            } else {
                BackendHealth.Unavailable(LlmError.Server(response.status.value, null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BackendHealth.Unavailable(e.toLlmError())
        }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> = try {
        val response = client.get("$root/api/tags")
        if (!response.status.isSuccess()) {
            Result.failure(OllamaException(LlmError.Server(response.status.value, response.bodyAsText())))
        } else {
            val tags: OllamaTagsResponse = response.body()
            Result.success(
                tags.models.map { tag ->
                    ModelInfo(
                        id = tag.name,
                        displayName = tag.name,
                        sizeBytes = tag.size,
                        family = tag.details?.family,
                        quantization = tag.details?.quantizationLevel,
                        contextLength = tag.details?.contextLength,
                    )
                }.sortedBy { it.displayName },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(OllamaException(e.toLlmError()))
    }

    override fun chat(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        // channelFlow, not flow: emissions happen inside execute { }, a
        // different coroutine context than the collector.

        var sawFirstToken = false

        // Captured so the loading watcher can be launched from inside execute,
        // where `this` is the response rather than the producer scope.
        val producer = this

        try {
            val body = OllamaChatRequest(
                model = request.model,
                messages = buildList {
                    request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                        add(OllamaMessage(role = "system", content = it))
                    }
                    request.messages.forEach { add(OllamaMessage(role = it.role.wire(), content = it.content)) }
                },
                stream = true,
                options = OllamaOptions(
                    temperature = request.options.temperature,
                    numCtx = request.options.numCtx,
                ),
                keepAlive = request.options.keepAlive,
            )

            client.preparePost("$root/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = runCatching { response.bodyAsText() }.getOrNull()
                    send(ChatEvent.Failed(mapHttpError(response.status, text, request.model)))
                    return@execute
                }

                // Clock starts here, not at send: the server has accepted, so
                // waiting now means loading. Earlier, it also fired on errors.
                // Zero or less disables the inference.
                val loadingWatcher = if (modelLoadingThresholdMillis > 0) {
                    producer.launch {
                        delay(modelLoadingThresholdMillis)
                        if (!sawFirstToken) send(ChatEvent.ModelLoading)
                    }
                } else {
                    null
                }

                val channel = response.bodyAsChannel()
                var stats: GenerationStats? = null

                try {
                while (!channel.isClosedForRead && isActive) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue

                    val chunk = try {
                        OllamaHttpClient.json.decodeFromString(OllamaChatChunk.serializer(), line)
                    } catch (e: SerializationException) {
                        // A malformed line is not fatal; keep what already arrived.
                        continue
                    }

                    chunk.message?.thinking?.takeIf { it.isNotEmpty() }?.let {
                        send(ChatEvent.Thinking(it))
                    }
                    chunk.message?.content?.takeIf { it.isNotEmpty() }?.let {
                        sawFirstToken = true
                        send(ChatEvent.Token(it))
                    }

                    if (chunk.done) {
                        stats = GenerationStats(
                            promptTokens = chunk.promptEvalCount,
                            completionTokens = chunk.evalCount,
                            totalDurationNanos = chunk.totalDuration,
                            loadDurationNanos = chunk.loadDuration,
                            evalDurationNanos = chunk.evalDuration,
                            doneReason = chunk.doneReason,
                        )
                        break
                    }
                }

                send(
                    if (stats != null) ChatEvent.Completed(stats)
                    // Closed without done:true — the socket died partway.
                    else ChatEvent.Failed(LlmError.StreamInterrupted),
                )
                } finally {
                    loadingWatcher?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e   // user pressed stop; leave the partial message clean
        } catch (e: Throwable) {
            send(ChatEvent.Failed(e.toLlmError()))
        }
    }

    private fun mapHttpError(status: HttpStatusCode, body: String?, model: String): LlmError = when {
        status == HttpStatusCode.NotFound && body?.contains("model", ignoreCase = true) == true ->
            LlmError.ModelNotFound(model)
        else -> LlmError.Server(status.value, body)
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 2_000L
    }
}

class OllamaException(val error: LlmError) : Exception(error.toString())

private fun Role.wire(): String = when (this) {
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.SYSTEM -> "system"
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
