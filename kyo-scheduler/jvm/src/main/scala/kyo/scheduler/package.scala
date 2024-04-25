package kyo

import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.*

package object scheduler {

    private[scheduler] val log = Logger.getLogger("kyo.scheduler")

    private[scheduler] def statsScope(path: String*) =
        "kyo" :: "scheduler" :: path.toList

    private[scheduler] def bug(msg: String, ex: Throwable) =
        log.log(Level.SEVERE, s"🙈 !!Kyo Scheduler Bug!!", new Exception(msg, ex))

}
