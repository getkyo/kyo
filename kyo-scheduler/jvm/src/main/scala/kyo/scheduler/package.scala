package kyo

package object scheduler:

    private[scheduler] def statsScope(path: String*) =
        "kyo" :: "scheduler" :: path.toList

    private[scheduler] def bug(msg: String, ex: Throwable) =
        (new Exception("🙈 !!Kyo Scheduler Bug!! " + msg, ex)).printStackTrace(System.err)
end scheduler
