package kyo.scheduler.top

/** Scala Native has no JMX; the scheduler top is surfaced via the console and status-file sinks instead. */
private[top] object TopJMX {
    def register(enable: Boolean, status: () => Status): () => Unit = {
        val _ = (enable, status)
        () => ()
    }
}
