package kyo.scheduler.top

import java.io.File
import java.io.FileWriter
import java.io.IOException
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
            // The atomic-unsupported exception type is absent from Scala Native's javalib, so the
            // fallback catches the platform-portable supertypes; a failed atomic move (unsupported
            // or e.g. cross-device) retries as a plain replace either way.
            try {
                val _ = Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch {
                case _: UnsupportedOperationException =>
                    val _ = Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                case _: IOException =>
                    val _ = Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch {
            case _: Throwable => ()
        }
}
