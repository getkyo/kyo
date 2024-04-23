package kyo

package object scheduler:

    private def statsScope(path: String*) =
        "kyo" :: "scheduler" :: path.toList

    private def bug(msg: String, ex: Throwable) =
        (new Exception("🙈 !!Kyo Scheduler Bug!! " + msg, ex)).printStackTrace(System.err)
end scheduler
