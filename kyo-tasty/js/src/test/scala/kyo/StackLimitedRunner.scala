package kyo

/** JS implementation: runs the thunk directly (no thread, no stack limit). */
object StackLimitedRunner:

    def run(body: => Unit): Unit = body

end StackLimitedRunner
