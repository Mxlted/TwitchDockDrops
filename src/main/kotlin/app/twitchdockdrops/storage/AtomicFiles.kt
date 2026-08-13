package app.twitchdockdrops.storage

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

object AtomicFiles {
    fun writeString(path: Path, value: String, ownerOnly: Boolean = false) {
        writeBytes(path, value.toByteArray(StandardCharsets.UTF_8), ownerOnly)
    }

    fun writeBytes(path: Path, value: ByteArray, ownerOnly: Boolean = false) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, value)
            if (ownerOnly) {
                restrictToOwner(temporary)
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            if (ownerOnly) {
                restrictToOwner(path)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
