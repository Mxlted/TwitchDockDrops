package com.nathan.twitchdropsminer.android.data.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class LogRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `log entries normalize newlines bound length and redact labeled secrets`() = runBlocking {
        val repository = LogRepository(directory, maxLines = 4, maxEntryCharacters = 80)
        repository.append(
            "warn\r\nforged",
            "{\"accessToken\":\"json-token-secret\"} first\r\nsecond " +
                "Authorization: OAuth access-token-secret " + "x".repeat(500),
        )

        val entry = repository.entries.value.single()
        assertFalse(entry.level.contains('\n'))
        assertFalse(entry.message.contains('\n'))
        assertFalse(entry.message.contains('\r'))
        assertFalse(entry.message.contains("access-token-secret"))
        assertFalse(entry.message.contains("json-token-secret"))
        assertTrue(entry.message.length <= 80)
        assertEquals(1, Files.readAllLines(directory.resolve("runtime.log")).size)
    }

    @Test
    fun `loading a hostile file keeps only a bounded tail and rewrites physical size`() = runBlocking {
        val file = directory.resolve("runtime.log")
        Files.writeString(file, (1..2_000).joinToString("\n") { "$it ${"z".repeat(200)}" })
        val repository = LogRepository(
            directory,
            maxLines = 10,
            maxEntryCharacters = 64,
            maxFileBytes = 16 * 1_024,
        )

        repository.load()

        assertEquals(10, repository.entries.value.size)
        assertTrue(Files.size(file) < 2_000)
    }

    @Test
    fun `acknowledged clear preserves later entries`() = runBlocking {
        val repository = LogRepository(directory)
        repository.append("INFO", "before")
        repository.clear()
        repository.append("INFO", "after")

        assertEquals(listOf("after"), repository.entries.value.map { it.message })
    }

    @Test
    fun `append compacts entries and file to max lines`() = runBlocking {
        val maxLines = 5
        val repository = LogRepository(directory, maxLines = maxLines)

        repeat(maxLines + 5) { index -> repository.append("INFO", "entry-$index") }

        assertEquals(maxLines, repository.entries.value.size)
        assertEquals(maxLines, Files.readAllLines(directory.resolve("runtime.log")).size)
        assertEquals((5..9).map { "entry-$it" }, repository.entries.value.map { it.message })
        assertTrue(Files.readString(directory.resolve("runtime.log")).endsWith('\n'))
    }

    @Test
    fun `appended lines load back in order without a blank entry`() = runBlocking {
        val repository = LogRepository(directory)
        repository.append("INFO", "first")
        repository.append("WARN", "second")
        val appended = repository.entries.value

        val loaded = LogRepository(directory)
        loaded.load()

        assertEquals(appended, loaded.entries.value)
        assertEquals(2, Files.readAllLines(directory.resolve("runtime.log")).size)
        assertTrue(Files.readString(directory.resolve("runtime.log")).endsWith('\n'))
    }

    @Test
    fun `fresh log file created by append is owner only where posix permissions are supported`() = runBlocking {
        val repository = LogRepository(directory)
        repository.append("INFO", "permission check")
        val file = directory.resolve("runtime.log")
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
            )
        }
    }
}
