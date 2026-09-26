package app.astiko.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write [target] through a temporary file and a rename, so a crash between
 * the two cannot leave a truncated file that later reads as valid data.
 *
 * The caller owns the temporary name. The offline cache inserts ".tmp"
 * before the ".json" suffix, and its directory scans have to keep ignoring
 * exactly those leftovers.
 */
fun writeAtomically(
    target: File,
    bytes: ByteArray,
    tmp: File = File(target.parentFile, "${target.name}.tmp"),
) {
    target.parentFile?.mkdirs()
    tmp.writeBytes(bytes)
    atomicMove(tmp, target)
}

/** The rename, without the atomic guarantee on filesystems that lack it. */
fun atomicMove(
    from: File,
    to: File,
) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
