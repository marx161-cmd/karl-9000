package com.inspiredandroid.kai

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.inspiredandroid.kai.sandbox.openFileWithIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"
private const val TERMUX_OUTPUT_LIMIT = 15_000
private const val TERMUX_MAX_TRANSCRIPT_LINES = 500
private val TERMUX_RS = 0x1e.toChar().toString()
private val TERMUX_US = 0x1f.toChar().toString()
private val TERMUX_PID_PREFIX = "${TERMUX_RS}KAIBASHPID$TERMUX_US"

internal class TermuxCommandHandle(
    private val process: Process,
    private val cancelled: AtomicBoolean,
    private val exit: CompletableDeferred<Int>,
) : CommandHandle {
    override fun cancel() {
        cancelled.set(true)
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
        exit.complete(-1)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override suspend fun writeInput(line: String) {
        if (cancelled.get()) return
        runCatching {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        }
    }

    override suspend fun awaitExit(): Int = exit.await()
}

internal class TermuxShellExecutor {
    fun isAvailable(): Boolean = File(TERMUX_BASH).canExecute()

    fun execute(command: String, timeoutSeconds: Long = 30L): Map<String, Any> {
        if (!isAvailable()) {
            return mapOf("success" to false, "error" to "Termux bash is not executable from this Kai build")
        }
        val process = Runtime.getRuntime().exec(buildArgs(command), buildEnv(), File(TERMUX_HOME))
        val stdoutFuture = CompletableFuture.supplyAsync { readBounded(process.inputStream.bufferedReader()) }
        val stderrFuture = CompletableFuture.supplyAsync { readBounded(process.errorStream.bufferedReader()) }
        val completed = process.waitFor(timeoutSeconds.coerceIn(1, 180), TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return mapOf(
                "success" to false,
                "stdout" to stdoutFuture.get(1, TimeUnit.SECONDS),
                "stderr" to stderrFuture.get(1, TimeUnit.SECONDS),
                "exit_code" to -1,
                "timed_out" to true,
                "backend" to "termux",
            )
        }
        val exit = process.exitValue()
        return mapOf(
            "success" to (exit == 0),
            "stdout" to stdoutFuture.get(),
            "stderr" to stderrFuture.get(),
            "exit_code" to exit,
            "timed_out" to false,
            "backend" to "termux",
        )
    }

    fun executeStreaming(
        command: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): CommandHandle {
        if (!isAvailable()) {
            onStderr("Termux bash is not executable from this Kai build")
            return NoOpCommandHandle
        }
        val process = Runtime.getRuntime().exec(buildArgs(command), buildEnv(), File(TERMUX_HOME))
        val cancelled = AtomicBoolean(false)
        val exit = CompletableDeferred<Int>()
        CompletableFuture.runAsync { streamLines(process.inputStream.bufferedReader(), cancelled, onStdout) }
        CompletableFuture.runAsync { streamLines(process.errorStream.bufferedReader(), cancelled, onStderr) }
        CompletableFuture.runAsync {
            exit.complete(runCatching { process.waitFor() }.getOrDefault(-1))
        }
        return TermuxCommandHandle(process, cancelled, exit)
    }

    private fun buildArgs(command: String): Array<String> = arrayOf(TERMUX_BASH, "-lc", termuxCommand(command))

    private fun termuxCommand(command: String): String {
        val quoted = shellSingleQuote(command)
        return """
            if command -v proot-distro >/dev/null 2>&1; then
              for distro in kai alpine; do
                if proot-distro login "${'$'}distro" -- true >/dev/null 2>&1; then
                  exec proot-distro login "${'$'}distro" -- bash -lc $quoted
                fi
              done
            fi
            exec bash -lc $quoted
        """.trimIndent()
    }

    private fun buildEnv(): Array<String> = arrayOf(
        "HOME=$TERMUX_HOME",
        "PREFIX=$TERMUX_PREFIX",
        "TMPDIR=$TERMUX_PREFIX/tmp",
        "PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin",
        "LANG=C.UTF-8",
        "TERM=xterm-256color",
    )

    private fun readBounded(reader: BufferedReader): String {
        val sb = StringBuilder()
        reader.use {
            var line = it.readLine()
            while (line != null) {
                if (sb.length < TERMUX_OUTPUT_LIMIT) {
                    sb.append(line).append('\n')
                }
                line = it.readLine()
            }
        }
        return sb.toString().take(TERMUX_OUTPUT_LIMIT)
    }

    private fun streamLines(reader: BufferedReader, cancelled: AtomicBoolean, onLine: (String) -> Unit) {
        reader.use {
            while (!cancelled.get()) {
                val line = it.readLine() ?: break
                onLine(line)
            }
        }
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

internal object TermuxShellSessions {
    private val executor = TermuxShellExecutor()
    private val shells = mutableMapOf<String, TermuxSessionShell>()

    @Synchronized
    fun shellFor(sessionId: String): TermuxSessionShell =
        shells.getOrPut(sessionId) {
            TermuxSessionShell(sessionId, TermuxPersistentShell(executor))
        }

    @Synchronized
    fun close(sessionId: String) {
        shells.remove(sessionId)?.reset()
    }

    @Synchronized
    fun resetAll() {
        val existing = shells.values.toList()
        shells.clear()
        existing.forEach { it.reset() }
    }
}

internal class TermuxSessionShell(
    val sessionId: String,
    private val inner: TermuxPersistentShell,
) {
    val transcript: SnapshotStateList<TerminalLine> = mutableStateListOf()

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        displayCommand: String = command,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
    ): Map<String, Any> {
        appendBounded(TerminalLine.Command(displayCommand))
        try {
            return inner.run(
                command = command,
                timeoutSeconds = timeoutSeconds,
                onStdout = { line ->
                    appendBounded(TerminalLine.Output(line))
                    onStdout?.invoke(line)
                },
                onStderr = { line ->
                    appendBounded(TerminalLine.Error(line))
                    onStderr?.invoke(line)
                },
            )
        } finally {
            // Transcript is intentionally in-memory for Termux shells for now.
        }
    }

    suspend fun writeInput(line: String) = inner.writeInput(line)
    fun cancelForeground() = inner.cancelForeground()
    fun reset() = inner.reset()

    fun clearTranscript() = transcript.clear()

    private fun appendBounded(line: TerminalLine) {
        Snapshot.withMutableSnapshot {
            transcript.add(line)
            val excess = transcript.size - TERMUX_MAX_TRANSCRIPT_LINES
            if (excess > 0) transcript.subList(0, excess).clear()
        }
    }
}

internal class TermuxPersistentShell(
    private val executor: TermuxShellExecutor,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var handle: CommandHandle? = null
    @Volatile private var bashPid: Int? = null
    private val currentSink = AtomicReference<CommandSink?>(null)

    private class CommandSink(
        val nonce: String,
        val stdoutBuf: StringBuilder = StringBuilder(),
        val stderrBuf: StringBuilder = StringBuilder(),
        val onStdout: ((String) -> Unit)? = null,
        val onStderr: ((String) -> Unit)? = null,
        val done: CompletableDeferred<Result> = CompletableDeferred(),
    )

    data class Result(
        val exitCode: Int,
        val cwd: String,
        val bashPid: Int,
        val shellDied: Boolean = false,
    )

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
    ): Map<String, Any> = mutex.withLock {
        ensureShell()
        val nonce = randomNonce()
        val sink = CommandSink(nonce = nonce, onStdout = onStdout, onStderr = onStderr)
        currentSink.set(sink)

        val line = "eval ${shellSingleQuote(command)}; __kai_st=${'$'}?; " +
            "printf '\\n\\036%s\\037%d\\037%d\\037%s\\036\\n' '$nonce' \"${'$'}__kai_st\" \"${'$'}${'$'}\" \"${'$'}PWD\" >&2"
        handle?.writeInput(line)

        val result = withTimeoutOrNull(timeoutSeconds.coerceIn(1, 24L * 60L * 60L).seconds) {
            sink.done.await()
        }
        currentSink.set(null)
        if (result == null) {
            cancelForeground()
            return@withLock mapOf(
                "success" to false,
                "stdout" to sink.stdoutBuf.toString().take(TERMUX_OUTPUT_LIMIT),
                "stderr" to (sink.stderrBuf.toString() + "\nCommand timed out and shell was reset").trim().take(TERMUX_OUTPUT_LIMIT),
                "exit_code" to -1,
                "timed_out" to true,
                "backend" to "termux",
                "cwd" to "/root",
            )
        }
        bashPid = result.bashPid
        mapOf(
            "success" to (!result.shellDied && result.exitCode == 0),
            "stdout" to sink.stdoutBuf.toString().take(TERMUX_OUTPUT_LIMIT),
            "stderr" to sink.stderrBuf.toString().take(TERMUX_OUTPUT_LIMIT),
            "exit_code" to result.exitCode,
            "timed_out" to false,
            "backend" to "termux",
            "cwd" to result.cwd,
            "shell_died" to result.shellDied,
        )
    }

    suspend fun writeInput(line: String) {
        handle?.writeInput(line)
    }

    fun cancelForeground() {
        reset()
    }

    fun reset() {
        handle?.cancel()
        handle = null
        bashPid = null
        currentSink.getAndSet(null)?.done?.complete(
            Result(exitCode = -1, cwd = "/root", bashPid = 0, shellDied = true),
        )
    }

    private fun ensureShell() {
        if (handle != null) return
        val h = executor.executeStreaming(
            command = "exec bash --noprofile --norc",
            onStdout = { line -> dispatchStdout(line) },
            onStderr = { line -> dispatchStderr(line) },
        )
        handle = h
        scope.launch {
            h.writeInput("printf '\\n\\036KAIBASHPID\\037%d\\036\\n' \"${'$'}${'$'}\" >&2")
        }
        scope.launch {
            h.awaitExit()
            currentSink.getAndSet(null)?.done?.complete(
                Result(exitCode = -1, cwd = "/root", bashPid = bashPid ?: 0, shellDied = true),
            )
            handle = null
            bashPid = null
        }
    }

    private fun dispatchStdout(line: String) {
        val sink = currentSink.get() ?: return
        appendBounded(sink.stdoutBuf, line)
        sink.onStdout?.invoke(line)
    }

    private fun dispatchStderr(line: String) {
        if (line.isEmpty()) return
        if (line.startsWith(TERMUX_PID_PREFIX) && line.endsWith(TERMUX_RS)) {
            val pidText = line.substring(TERMUX_PID_PREFIX.length, line.length - 1)
            pidText.toIntOrNull()?.let { bashPid = it }
            return
        }
        val sink = currentSink.get() ?: return
        if (line.length >= 2 && line.startsWith(TERMUX_RS) && line.endsWith(TERMUX_RS)) {
            val payload = line.substring(1, line.length - 1)
            val parts = payload.split(TERMUX_US)
            if (parts.size == 4 && parts[0] == sink.nonce) {
                val exit = parts[1].toIntOrNull() ?: -1
                val pid = parts[2].toIntOrNull() ?: 0
                val cwd = parts[3]
                sink.done.complete(Result(exitCode = exit, cwd = cwd, bashPid = pid))
                return
            }
        }
        appendBounded(sink.stderrBuf, line)
        sink.onStderr?.invoke(line)
    }

    private fun appendBounded(buf: StringBuilder, line: String) {
        if (buf.length >= TERMUX_OUTPUT_LIMIT) return
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(line)
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun randomNonce(): String = (0 until 16).map { "0123456789abcdef".random() }.joinToString("")
}

internal class TermuxSessionCommandHandle(
    private val shell: TermuxSessionShell,
    private val exit: CompletableDeferred<Map<String, Any>>,
    private val cancelled: AtomicBoolean,
) : CommandHandle {
    override fun cancel() {
        cancelled.set(true)
        shell.cancelForeground()
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override suspend fun writeInput(line: String) {
        if (!cancelled.get()) shell.writeInput(line)
    }

    override suspend fun awaitExit(): Int {
        val result = exit.await()
        return result["exit_code"] as? Int ?: -1
    }
}

class TermuxSandboxController : SandboxController {
    private val context: Context by inject(Context::class.java)
    private val executor = TermuxShellExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(
        if (executor.isAvailable()) {
            SandboxStatus(
                environmentName = "Termux Alpine",
                installed = true,
                ready = true,
                statusText = "Termux Alpine ready",
                packagesInstalled = true,
                resetAvailable = false,
                packageManagerAvailable = false,
            )
        } else {
            SandboxStatus(
                environmentName = "Termux Alpine",
                error = true,
                statusText = "Termux is not reachable",
                resetAvailable = false,
                packageManagerAvailable = false,
            )
        },
    )
    override val status: StateFlow<SandboxStatus> = _status
    override val sessions: StateFlow<List<String>> = MutableStateFlow(listOf(SandboxSessions.TERMINAL))
    private val terminalTranscript = mutableStateListOf<TerminalLine>()

    override fun setup() {
        _status.value = SandboxStatus(
            environmentName = "Termux Alpine",
            installed = true,
            ready = executor.isAvailable(),
            statusText = "Termux Alpine ready",
            packagesInstalled = true,
            resetAvailable = false,
            packageManagerAvailable = false,
        )
    }

    override fun cancel() {
        TermuxShellSessions.resetAll()
    }
    override fun reset() {
        TermuxShellSessions.resetAll()
    }
    override fun installPackages() {}
    override fun closeSession(sessionId: String) = TermuxShellSessions.close(sessionId)
    override fun transcriptFor(sessionId: String): SnapshotStateList<TerminalLine> =
        TermuxShellSessions.shellFor(sessionId).transcript
    override fun clearTranscript(sessionId: String) = TermuxShellSessions.shellFor(sessionId).clearTranscript()

    override suspend fun executeCommand(command: String, sessionId: String): String = withContext(Dispatchers.IO) {
        val result = TermuxShellSessions.shellFor(sessionId).run(command, timeoutSeconds = 30)
        val stdout = result["stdout"] as? String ?: ""
        val stderr = result["stderr"] as? String ?: ""
        buildString {
            append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }.ifBlank { "exit_code=${result["exit_code"]}" }
    }

    override suspend fun executeCommandStreaming(
        command: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
        sessionId: String,
    ): CommandHandle {
        val shell = TermuxShellSessions.shellFor(sessionId)
        val deferred = CompletableDeferred<Map<String, Any>>()
        val cancelled = AtomicBoolean(false)
        scope.launch {
            runCatching {
                shell.run(
                    command = command,
                    timeoutSeconds = 24L * 60L * 60L,
                    onStdout = onStdout,
                    onStderr = onStderr,
                )
            }.onSuccess { deferred.complete(it) }
                .onFailure { deferred.complete(mapOf("exit_code" to -1)) }
        }
        return TermuxSessionCommandHandle(shell, deferred, cancelled)
    }

    override suspend fun listDirectory(path: String): List<SandboxFileEntry> = withContext(Dispatchers.IO) {
        val dir = resolveTermuxPath(path) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles().orEmpty()
            .map { file ->
                SandboxFileEntry(
                    name = file.name,
                    path = toSandboxPath(file),
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0,
                    lastModifiedMs = file.lastModified(),
                )
            }
            .sortedWith(compareByDescending<SandboxFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun readTextFile(path: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        val file = resolveTermuxPath(path) ?: return@withContext null
        if (!file.isFile || file.length() > maxBytes) return@withContext null
        file.readText()
    }

    override suspend fun writeTextFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolveTermuxPath(path) ?: return@withContext false
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    }

    override suspend fun openFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val file = resolveTermuxPath(path) ?: return@withContext Result.failure(IllegalArgumentException("Invalid path"))
        if (!file.isFile) return@withContext Result.failure(IllegalArgumentException("Not a file"))
        val staged = File(context.cacheDir, "termux-open/${file.name}")
        staged.parentFile?.mkdirs()
        file.copyTo(staged, overwrite = true)
        val result = openFileWithIntent(context, staged)
        if (result.success) Result.success(Unit) else Result.failure(IllegalStateException(result.error ?: "Failed to open file"))
    }

    override suspend fun deleteEntry(path: String, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val file = resolveTermuxPath(path) ?: return@withContext false
        if (file.absolutePath == TERMUX_HOME) return@withContext false
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override suspend fun renameEntry(path: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            return@withContext Result.failure(IllegalArgumentException("Invalid name"))
        }
        val src = resolveTermuxPath(path) ?: return@withContext Result.failure(IllegalArgumentException("Invalid path"))
        val dest = File(src.parentFile, newName)
        if (!src.renameTo(dest)) return@withContext Result.failure(IllegalStateException("Rename failed"))
        Result.success(toSandboxPath(dest))
    }

    private fun resolveTermuxPath(path: String): File? {
        val normalized = path.trim().ifEmpty { "/root" }
        if (!normalized.startsWith("/")) return null
        val parts = normalized.split("/").filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) return null
        val rel = if (parts.firstOrNull() == "root") parts.drop(1) else parts
        val root = File(TERMUX_HOME)
        val candidate = if (rel.isEmpty()) root else File(root, rel.joinToString(File.separator))
        val rootCanon = root.canonicalPath
        val candidateCanon = candidate.canonicalPath
        if (candidateCanon != rootCanon && !candidateCanon.startsWith(rootCanon + File.separator)) return null
        return candidate
    }

    private fun toSandboxPath(file: File): String {
        val root = File(TERMUX_HOME).canonicalFile
        val rel = file.canonicalFile.relativeTo(root).path
        return if (rel.isBlank() || rel == ".") "/root" else "/root/${rel.replace(File.separatorChar, '/')}"
    }
}

internal fun shouldUseTermuxSandbox(context: Context): Boolean =
    context.packageName.startsWith("com.termux.") && File(TERMUX_BASH).canExecute()
