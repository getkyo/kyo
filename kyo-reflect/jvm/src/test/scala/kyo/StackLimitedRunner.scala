package kyo

/** JVM implementation: runs a thunk in a stack-limited thread (64KB) to guard against stack overflow. */
object StackLimitedRunner:

    def run(body: => Unit): Unit =
        var exception: Option[Throwable] = None
        val thread = new Thread(
            null,
            () =>
                try body
                catch case t: Throwable => exception = Some(t),
            "stack-limited",
            64 * 1024
        )
        thread.start()
        thread.join(5000)
        exception.foreach(e => throw e)
    end run

end StackLimitedRunner
