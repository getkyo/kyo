package kyo.internal.tui2

/** File-based debug logging for TUI — writes to /tmp/kyo-ui-debug.log to avoid corrupting the terminal. */
private[kyo] object DebugLog:
    private val file = new java.io.FileWriter("/tmp/kyo-ui-debug.log", true)

    def apply(msg: String): Unit =
        file.write(msg)
        file.write('\n')
        file.flush()
    end apply
end DebugLog
