package com.nathan.twitchdropsminer.android.data.local

import app.twitchdockdrops.storage.AtomicFiles
import app.twitchdockdrops.security.SafeText
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LogRepository(
    dataDirectory: Path,
    private val maxLines: Int = 500,
    private val maxEntryCharacters: Int = 1_024,
    private val maxFileBytes: Int = 768 * 1_024,
) {
    private val logFile = dataDirectory.resolve("runtime.log")
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<LocalLogEntry>>(emptyList())

    val entries: StateFlow<List<LocalLogEntry>> = mutableEntries

    suspend fun load() = mutex.withLock {
        mutableEntries.value = withContext(Dispatchers.IO) {
            if (Files.exists(logFile)) {
                val loaded = readBoundedTail().map(LocalLogEntry::fromLine).map(::sanitize)
                val trimmed = trim(loaded)
                AtomicFiles.writeString(
                    logFile,
                    trimmed.joinToString("\n", transform = LocalLogEntry::toLine),
                    ownerOnly = true,
                )
                trimmed
            } else {
                emptyList()
            }
        }
    }

    suspend fun append(level: String, message: String) = mutex.withLock {
        val updated = trim(
            mutableEntries.value + sanitize(LocalLogEntry(Instant.now(), level, message)),
        )
        withContext(Dispatchers.IO) {
            AtomicFiles.writeString(
                logFile,
                updated.joinToString("\n", transform = LocalLogEntry::toLine),
                ownerOnly = true,
            )
        }
        mutableEntries.value = updated
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { Files.deleteIfExists(logFile) }
        mutableEntries.value = emptyList()
    }

    fun visibleText(): String = entries.value.joinToString("\n", transform = LocalLogEntry::toLine)

    private fun trim(values: List<LocalLogEntry>): List<LocalLogEntry> = when {
        maxLines <= 0 -> emptyList()
        values.size <= maxLines -> values
        else -> values.takeLast(maxLines)
    }

    private fun sanitize(entry: LocalLogEntry): LocalLogEntry = entry.copy(
        level = entry.level
            .uppercase()
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(12)
            .ifBlank { "INFO" },
        message = SafeText.diagnostic(entry.message, maxEntryCharacters),
    )

    private fun readBoundedTail(): List<String> {
        val fileSize = Files.size(logFile)
        val bytesToRead = minOf(fileSize, maxFileBytes.toLong()).toInt()
        if (bytesToRead <= 0) return emptyList()
        val start = fileSize - bytesToRead
        val buffer = ByteBuffer.allocate(bytesToRead)
        Files.newByteChannel(logFile, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        }
        val decoded = String(buffer.array(), StandardCharsets.UTF_8)
        val completeTail = if (start > 0L) decoded.substringAfter('\n', "") else decoded
        return completeTail.lineSequence().takeLastBounded(maxLines.coerceAtLeast(0))
    }
}

private fun Sequence<String>.takeLastBounded(maximumSize: Int): List<String> {
    if (maximumSize <= 0) return emptyList()
    val values = ArrayDeque<String>(maximumSize)
    for (value in this) {
        if (values.size == maximumSize) values.removeFirst()
        values.addLast(value)
    }
    return values.toList()
}
