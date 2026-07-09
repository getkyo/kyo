package kyo.scheduler.top

import java.io.File
import java.io.FileWriter

private[top] object StatusFile {

    /** Best-effort atomic write of one status line: write to `<path>.tmp`, then rename over `path` so a
      * concurrent reader (the CI resource monitor) never observes a half-written line. Diagnostics only,
      * so any IO failure is swallowed rather than disrupting the scheduler.
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
            val _ = tmp.renameTo(new File(path))
        } catch {
            case _: Throwable => ()
        }
}
