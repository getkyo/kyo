package kyo

import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Logger

package object scheduler:

    private val log = Logger.getLogger("kyo.scheduler")

    private def statsScope(path: String*) =
        "kyo" :: "scheduler" :: path.toList

    private def bug(msg: String, ex: Throwable) =
        log.severe(s"🙈 !!Kyo Scheduler Bug!! $msg \n Caused by: ${stackTrace(ex)}")

    private def stackTrace(ex: Throwable) =
        val stackTrace = new StringWriter()
        val writer     = new PrintWriter(stackTrace)
        ex.printStackTrace(writer)
        writer.flush()
        s"${ex.getMessage()}\n$stackTrace"
    end stackTrace

end scheduler
