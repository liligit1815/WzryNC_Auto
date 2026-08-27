package com.lispace.wzryncauto.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RootCommandExecutor {
    private val currentProcess = AtomicReference<Process?>(null)
    private val commandMutex = Mutex()

    suspend fun executeText(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): CommandResult {
        val result = execute(command, timeoutMs)
        return CommandResult(
            command = command,
            exitCode = result.exitCode,
            stdout = result.stdout.toString(Charsets.UTF_8),
            stderr = result.stderr,
            durationMs = result.durationMs,
            timedOut = result.timedOut,
        )
    }

    suspend fun executeBinary(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BinaryCommandResult = execute(command, timeoutMs)

    fun cancelCurrent() {
        currentProcess.getAndSet(null)?.let(::terminate)
    }

    private suspend fun execute(
        command: String,
        timeoutMs: Long,
    ): BinaryCommandResult = commandMutex.withLock {
        executeLocked(command, timeoutMs)
    }

    private suspend fun executeLocked(
        command: String,
        timeoutMs: Long,
    ): BinaryCommandResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "ROOT command must not be blank" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }

        val startedAt = System.nanoTime()
        val process = ProcessBuilder("su", "-c", command).start()
        check(currentProcess.compareAndSet(null, process))

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = streamCopyThread("wzry-root-stdout") {
            process.inputStream.use { it.copyTo(stdout) }
        }
        val stderrThread = streamCopyThread("wzry-root-stderr") {
            process.errorStream.use { it.copyTo(stderr) }
        }

        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) terminate(process)
            stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
            stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
            BinaryCommandResult(
                command = command,
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.toByteArray(),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                durationMs = elapsedMs(startedAt),
                timedOut = !finished,
            )
        } catch (error: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw CancellationException("ROOT command interrupted", error)
        } finally {
            currentProcess.compareAndSet(process, null)
        }
    }

    private fun streamCopyThread(name: String, action: () -> Unit) =
        Thread({
            // Destroying a process closes its pipes while these daemon readers
            // may still be blocked. That is an expected cancellation path and
            // must never escape as an uncaught exception that kills the app.
            runCatching(action)
        }, name).apply {
            isDaemon = true
            start()
        }

    private fun terminate(process: Process) {
        process.destroy()
        if (process.isAlive) {
            runCatching {
                process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }
        if (process.isAlive) process.destroyForcibly()
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val STREAM_JOIN_TIMEOUT_MS = 1_000L
        private const val DESTROY_GRACE_MS = 300L
    }
}
