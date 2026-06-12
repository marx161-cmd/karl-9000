package com.inspiredandroid.kai.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

val MODEL_CATALOG = listOf(
    LocalModel(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B IT",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_580_000_000L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        gpuMemoryMb = 676,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 50_000,
        isRecommended = true,
    ),
    LocalModel(
        id = "gemma-4-e4b-it",
        displayName = "Gemma 4 E4B IT",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_650_000_000L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        gpuMemoryMb = 710,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 75_000,
    ),
    LocalModel(
        id = "qwen3-0.6b",
        displayName = "Qwen3 0.6B",
        fileName = "Qwen3-0.6B.litertlm",
        sizeBytes = 614_236_160L,
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        gpuMemoryMb = 300,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 35_000,
    ),
    LocalModel(
        id = "gemma-3-270m-it-q8",
        displayName = "Gemma 3 270M IT Q8",
        fileName = "gemma3-270m-it-q8.litertlm",
        sizeBytes = 304_005_120L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm",
        gpuMemoryMb = 160,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 20_000,
    ),
    LocalModel(
        id = "gemma-3-1b-it-int4",
        displayName = "Gemma 3 1B IT Int4",
        fileName = "gemma3-1b-it-int4.litertlm",
        sizeBytes = 584_417_280L,
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        gpuMemoryMb = 260,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 35_000,
    ),
    LocalModel(
        id = "qwen2.5-1.5b-instruct-q8",
        displayName = "Qwen2.5 1.5B Instruct Q8",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeBytes = 1_597_931_520L,
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        gpuMemoryMb = 430,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 45_000,
    ),
    LocalModel(
        id = "deepseek-r1-distill-qwen-1.5b-q8",
        displayName = "DeepSeek R1 Distill Qwen 1.5B Q8",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeBytes = 1_833_451_520L,
        downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        gpuMemoryMb = 460,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 45_000,
    ),
    LocalModel(
        id = "phi-4-mini-instruct-q8",
        displayName = "Phi 4 Mini Instruct Q8",
        fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeBytes = 3_910_090_752L,
        downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        gpuMemoryMb = 760,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 70_000,
    ),
)

class LiteRTInferenceEngine : LocalInferenceEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logLock = Any()
    private var downloadJob: Job? = null
    private var idleReleaseJob: Job? = null

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    override var currentModelId: String? = null
        private set
    private var currentContextTokens: Int = 0
    private var currentBackendMode: LocalInferenceBackendMode? = null

    private val _engineState = MutableStateFlow(EngineState.UNINITIALIZED)
    override val engineState: StateFlow<EngineState> = _engineState

    private val _downloadingModelId = MutableStateFlow<String?>(null)
    override val downloadingModelId: StateFlow<String?> = _downloadingModelId

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _downloadError = MutableStateFlow<DownloadError?>(null)
    override val downloadError: StateFlow<DownloadError?> = _downloadError

    override suspend fun initialize(
        model: DownloadedModel,
        contextTokens: Int,
        backendMode: LocalInferenceBackendMode,
    ) {
        withContext(Dispatchers.IO) {
            idleReleaseJob?.cancel()
            if (
                currentModelId == model.id &&
                currentContextTokens == contextTokens &&
                currentBackendMode == backendMode &&
                _engineState.value == EngineState.READY
            ) return@withContext
            _engineState.value = EngineState.INITIALIZING
            try {
                val modelFile = File(model.filePath)
                logLiteRT("initialize requested model=${model.id} path=${model.filePath} size=${modelFile.length()} contextTokens=$contextTokens backendMode=$backendMode")
                if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                    throw IllegalStateException("Model file missing or too small: ${model.filePath}")
                }

                // Release any currently-loaded engine before measuring available memory,
                // otherwise its GPU/CPU working set counts against the headroom check and
                // switching between models spuriously fails (e.g. Qwen -> Gemma 4).
                val hadExistingEngine = engine != null
                release()
                _engineState.value = EngineState.INITIALIZING
                logLiteRT("previous engine released hadExistingEngine=$hadExistingEngine")

                if (hadExistingEngine) {
                    // engine.close() returns before the OpenCL driver actually reclaims the
                    // previous model's GPU buffers, so loading a second model on top would
                    // briefly hold both resident and trip Android's LMK. Give the driver a
                    // beat to drain before allocating ~GB of new GPU buffers.
                    System.gc()
                    delay(GPU_DRAIN_DELAY_MS.milliseconds)
                    logLiteRT("GPU drain delay complete delayMs=$GPU_DRAIN_DELAY_MS")
                }

                val availMem = getAvailableMemoryBytes()
                logLiteRT("memory check availableBytes=$availMem minRequiredBytes=$MIN_MEMORY_HEADROOM_BYTES")
                if (availMem < MIN_MEMORY_HEADROOM_BYTES) {
                    throw InsufficientMemoryException()
                }

                fun initWithBackend(backend: Backend, maxTokens: Int?): Engine {
                    val backendName = backend.toString()
                    val startedAt = System.currentTimeMillis()
                    logLiteRT("engine config start backend=$backendName maxNumTokens=$maxTokens cacheDir=${getModelCacheDirectory()}")
                    val config = EngineConfig(
                        modelPath = model.filePath,
                        backend = backend,
                        cacheDir = getModelCacheDirectory(),
                        maxNumTokens = maxTokens,
                    )
                    logLiteRT("engine object create start backend=$backendName")
                    val e = Engine(config)
                    logLiteRT("engine initialize start backend=$backendName")
                    try {
                        e.initialize()
                        logLiteRT("engine initialize done backend=$backendName elapsedMs=${System.currentTimeMillis() - startedAt}")
                    } catch (t: Throwable) {
                        logLiteRT("engine initialize failed backend=$backendName elapsedMs=${System.currentTimeMillis() - startedAt}: ${t.message}", t)
                        runCatching { e.close() }
                        throw t
                    }
                    return e
                }

                val requestedTokens = if (contextTokens > 0) contextTokens else null
                logLiteRT("initializing model=${model.id} maxNumTokens=$requestedTokens backendMode=$backendMode")

                val newEngine = try {
                    when (backendMode) {
                        LocalInferenceBackendMode.GPU -> initWithBackend(Backend.GPU(), requestedTokens)
                        LocalInferenceBackendMode.CPU -> initWithBackend(Backend.CPU(), requestedTokens)
                        LocalInferenceBackendMode.AUTO -> try {
                            logLiteRT("trying GPU backend")
                            initWithBackend(Backend.GPU(), requestedTokens)
                        } catch (e: Exception) {
                            logLiteRT("GPU init failed (${e.message?.take(200)}), trying CPU backend", e)
                            initWithBackend(Backend.CPU(), requestedTokens)
                        }
                    }
                } catch (e: Exception) {
                    // Context size not supported — retry with model default
                    logLiteRT("init failed with maxNumTokens=$requestedTokens: ${e.message}", e)
                    if (requestedTokens != null && e.isContextTokenFailure()) {
                        logLiteRT("retrying with model default context")
                        when (backendMode) {
                            LocalInferenceBackendMode.GPU -> initWithBackend(Backend.GPU(), null)
                            LocalInferenceBackendMode.CPU -> initWithBackend(Backend.CPU(), null)
                            LocalInferenceBackendMode.AUTO -> try {
                                logLiteRT("trying GPU backend with default context")
                                initWithBackend(Backend.GPU(), null)
                            } catch (e2: Exception) {
                                logLiteRT("GPU init failed with default context (${e2.message?.take(200)}), trying CPU backend", e2)
                                initWithBackend(Backend.CPU(), null)
                            }
                        }
                    } else {
                        throw e
                    }
                }

                engine = newEngine
                conversation = newEngine.createConversation()
                currentModelId = model.id
                currentContextTokens = contextTokens
                currentBackendMode = backendMode
                _engineState.value = EngineState.READY
                logLiteRT("engine ready model=${model.id} contextTokens=$contextTokens backendMode=$backendMode")
            } catch (e: Exception) {
                _engineState.value = EngineState.ERROR
                logLiteRT("initialize failed model=${model.id}: ${e.message}", e)
                throw e
            }
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            // Null before close so a concurrent release() sees null and skips —
            // Conversation.close() / Engine.close() throw IllegalStateException on double-close.
            val convToClose = conversation
            val engineToClose = engine
            conversation = null
            engine = null
            currentModelId = null
            currentBackendMode = null
            _engineState.value = EngineState.UNINITIALIZED
            if (convToClose != null || engineToClose != null) {
                logLiteRT("release start hadConversation=${convToClose != null} hadEngine=${engineToClose != null}")
            }
            runCatching { convToClose?.close() }
            runCatching { engineToClose?.close() }
            if (convToClose != null || engineToClose != null) {
                logLiteRT("release done")
            }
        }
    }

    override fun releaseInBackground() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch { release() }
    }

    override suspend fun chat(
        messages: List<InferenceMessage>,
        systemPrompt: String?,
        tools: List<LocalTool>,
    ): String = withContext(Dispatchers.IO) {
        idleReleaseJob?.cancel()
        val currentEngine = engine ?: throw IllegalStateException("Engine not initialized")

        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) throw IllegalStateException("No user message found")

        val sanitizedSystemPrompt = sanitizeForLiteRt(systemPrompt)
        val initialMessages = messages.subList(0, lastUserIndex).map { msg ->
            val sanitized = sanitizeForLiteRt(msg.content) ?: ""
            when (msg.role) {
                "user" -> Message.user(sanitized)
                else -> Message.model(sanitized)
            }
        }

        val toolProviders = tools.map { tool(LocalToolOpenApiAdapter(it)) }
        val config = ConversationConfig(
            systemInstruction = sanitizedSystemPrompt?.let { Contents.of(it) },
            initialMessages = initialMessages,
            tools = toolProviders,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            // automaticToolCalling = true drives the parser; only enable when we
            // actually have tools, otherwise plain-text responses get parsed as FCs.
            automaticToolCalling = toolProviders.isNotEmpty(),
        )
        val prev = conversation
        conversation = null
        runCatching { prev?.close() }
        val conv = currentEngine.createConversation(config)
        conversation = conv

        val lastMessage = sanitizeForLiteRt(messages[lastUserIndex].content) ?: ""
        val response = try {
            logLiteRT("sendMessage start model=$currentModelId backendMode=$currentBackendMode chars=${lastMessage.length}")
            withTimeout(INFERENCE_TIMEOUT_MS.milliseconds) {
                conv.sendMessage(lastMessage)
            }.also {
                logLiteRT("sendMessage done model=$currentModelId backendMode=$currentBackendMode")
            }
        } catch (e: TimeoutCancellationException) {
            logLiteRT("sendMessage timeout model=$currentModelId backendMode=$currentBackendMode", e)
            throw InferenceTimeoutException()
        }
        stripThinkBlocks(response.toString())
    }

    /**
     * Adapter that exposes a Kai [LocalTool] (suspend execute) to litert-lm's [OpenApiTool]
     * (synchronous execute). The bridge uses [runBlocking] because the engine calls
     * [execute] on its own worker thread (we're already inside `Dispatchers.IO` from
     * [chat]) and waits for the result before continuing the tool loop.
     */
    private class LocalToolOpenApiAdapter(private val localTool: LocalTool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = localTool.descriptionJsonString
        override fun execute(paramsJsonString: String): String = runBlocking { localTool.execute(paramsJsonString) }
    }

    /**
     * Drops UTF-16 surrogate halves from the string. The litert-lm JNI layer passes
     * strings to the native runtime as *modified* UTF-8, which encodes supplementary-plane
     * characters (U+10000–U+10FFFF — most emoji like 🗺️, 🎉, 🔥) as surrogate-pair
     * sequences where each half becomes a 3-byte block. That is invalid as *standard*
     * UTF-8, and the native runtime's `nlohmann::json` parser crashes with "ill-formed
     * UTF-8 byte" the moment it hits one.
     *
     * Filtering surrogates drops every supplementary character (both halves are surrogate
     * code units in UTF-16) while leaving BMP characters — including BMP-only emoji like
     * ⚔️, ♻️, ❤️, and all CJK / extended Latin / accented characters — untouched.
     * No-op for strings that don't contain any supplementary character.
     */
    private fun sanitizeForLiteRt(s: String?): String? {
        if (s == null) return null
        if (s.none { it.isSurrogate() }) return s
        return s.filter { !it.isSurrogate() }
    }

    // Qwen3 emits a <think>…</think> block as part of its chat template; strip it before
    // the user sees it. Safe for Gemma 4, which never emits these tags.
    private fun stripThinkBlocks(s: String): String = THINK_BLOCK_REGEX.replace(s, "").trim()

    companion object {
        private const val INFERENCE_TIMEOUT_MS = 120_000L // 2 minutes
        private const val MIN_MEMORY_HEADROOM_BYTES = 512L * 1024 * 1024 // 512 MB
        private const val DOWNLOAD_SPACE_BUFFER_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val GPU_DRAIN_DELAY_MS = 750L
        private const val DIAGNOSTIC_LOG_FILE = "kai-litert.log"
        private const val DIAGNOSTIC_LOG_MAX_BYTES = 2L * 1024L * 1024L
        private val THINK_BLOCK_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    }

    private fun logLiteRT(message: String, throwable: Throwable? = null) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "LiteRT: $timestamp $message"
        println(line)
        synchronized(logLock) {
            runCatching {
                val logFile = File(File(getModelStorageDirectory()).parentFile, DIAGNOSTIC_LOG_FILE)
                logFile.parentFile?.mkdirs()
                if (logFile.exists() && logFile.length() > DIAGNOSTIC_LOG_MAX_BYTES) {
                    logFile.writeText("")
                }
                logFile.appendText(line + "\n")
                if (throwable != null) {
                    logFile.appendText(throwable.stackTraceToString() + "\n")
                }
            }
        }
    }

    private fun Throwable.isContextTokenFailure(): Boolean {
        val text = generateSequence(this as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        return listOf(
            "maxnumtokens",
            "max num tokens",
            "context",
            "token",
            "kv cache",
        ).any { it in text }
    }

    override fun getDownloadedModels(): List<DownloadedModel> {
        val modelsDir = File(getModelStorageDirectory())
        if (!modelsDir.exists()) return emptyList()
        val catalogModels = MODEL_CATALOG.mapNotNull { catalogModel ->
            val modelDir = File(modelsDir, catalogModel.id)
            val modelFile = File(modelDir, catalogModel.fileName)
            if (modelFile.exists()) {
                DownloadedModel(
                    id = catalogModel.id,
                    displayName = catalogModel.displayName,
                    filePath = modelFile.absolutePath,
                    sizeBytes = modelFile.length(),
                )
            } else {
                null
            }
        }
        val catalogPaths = catalogModels.map { it.filePath }.toSet()
        val sideLoadedModels = modelsDir
            .walkTopDown()
            .maxDepth(2)
            .filter { it.isFile && it.extension == "litertlm" && it.absolutePath !in catalogPaths }
            .map { file ->
                val id = file.parentFile?.name?.takeIf { it.isNotBlank() && it != modelsDir.name }
                    ?: file.nameWithoutExtension
                DownloadedModel(
                    id = id,
                    displayName = id.replace('-', ' ').replace('_', ' '),
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                )
            }
            .toList()
        return catalogModels + sideLoadedModels
    }

    override fun getAvailableModels(): List<LocalModel> = MODEL_CATALOG

    override fun getFreeSpaceBytes(): Long = getAvailableDiskSpaceBytes(getModelStorageDirectory())

    override fun startDownload(model: LocalModel) {
        cancelDownload()
        downloadJob = scope.launch {
            _downloadingModelId.value = model.id
            _downloadProgress.value = 0f
            _downloadError.value = null
            var tempFile: File? = null
            var notificationStarted = false

            try {
                val modelsDir = getModelStorageDirectory()
                val modelDir = File(modelsDir, model.id)
                modelDir.mkdirs()
                val targetFile = File(modelDir, model.fileName)
                tempFile = File(modelDir, "${model.fileName}.tmp")
                var lastNotifiedPercent = -1

                val freeSpace = getFreeSpaceBytes()
                if (freeSpace < model.sizeBytes + DOWNLOAD_SPACE_BUFFER_BYTES) {
                    _downloadError.value = DownloadError.NOT_ENOUGH_DISK_SPACE
                    return@launch
                }

                @Suppress("DEPRECATION")
                val connection = URL(model.downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw IOException("Download failed: HTTP $responseCode")
                }

                // Only start the foreground service once we have a live connection.
                // Starting it earlier risks ForegroundServiceDidNotStartInTimeException if
                // the connect() above fails fast (e.g. offline) before the service can run.
                startDownloadNotificationService()
                notificationStarted = true

                val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
                val buffer = ByteArray(65536)
                var totalBytesRead = 0L

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        while (true) {
                            ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead <= 0) break
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val percent = (totalBytesRead * 100 / contentLength).toInt().coerceIn(1, 100)
                            if (percent != lastNotifiedPercent) {
                                lastNotifiedPercent = percent
                                _downloadProgress.value = percent / 100f
                                updateDownloadNotificationProgress(percent)
                            }
                        }
                    }
                }
                connection.disconnect()

                val downloadedSize = tempFile.length()
                if (downloadedSize < contentLength * 0.95) {
                    tempFile.delete()
                    throw IOException("Download incomplete: got $downloadedSize bytes, expected ~$contentLength")
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Throwable) {
                if (tempFile?.exists() == true) tempFile.delete()
                if (e is CancellationException) throw e
                _downloadError.value = DownloadError.NETWORK_ERROR
            } finally {
                _downloadingModelId.value = null
                _downloadProgress.value = null
                if (notificationStarted) stopDownloadNotificationService()
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    override suspend fun deleteModel(modelId: String) {
        withContext(Dispatchers.IO) {
            // Wait for any in-flight idle release so its native teardown doesn't race with deleteRecursively().
            idleReleaseJob?.cancelAndJoin()
            idleReleaseJob = null
            if (currentModelId == modelId) {
                release()
            }
            val modelDir = File(getModelStorageDirectory(), modelId)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            } else {
                File(getModelStorageDirectory()).walkTopDown()
                    .firstOrNull { it.isFile && it.extension == "litertlm" && it.nameWithoutExtension == modelId }
                    ?.delete()
            }
        }
    }
}
