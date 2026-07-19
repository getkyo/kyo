package kyo.scheduler.top

import java.io.File
import java.io.FileWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private[top] object StatusFile {

    /** Best-effort atomic write of one status line: write to `<path>.tmp`, then move over `path` so a
      * concurrent reader (the CI resource monitor) never observes a half-written line. The move must
      * replace an existing destination: `File.renameTo` cannot do that on Windows, so the NIO move is
      * used with an atomic-move first and a plain replace as fallback where atomic replace is
      * unsupported. Diagnostics only, so any IO failure is swallowed rather than disrupting the
      * scheduler.
      */
    def write(path: String, line: String): Unit =
        try {
            val tmp    = new File(path + ".tmp")
            val writer = new FileWriter(tmp, false)
            try {
                writer.write(line)
                writer.write("\n")
            } finally
                writer.close()
            val source = tmp.toPath
            val target = new File(path).toPath
            try {
                val _ = Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch {
                case _: AtomicMoveNotSupportedException =>
                    val _ = Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                case _: UnsupportedOperationException =>
                    val _ = Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch {
            case _: Throwable => ()
        }
}
