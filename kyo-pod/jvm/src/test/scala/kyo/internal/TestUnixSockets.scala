package kyo.internal

/** Whether a test can serve a fake daemon on a Unix domain socket. The JVM binds AF_UNIX on every host it runs on, Windows included. */
private[kyo] object TestUnixSockets:
    val supported: Boolean = true
end TestUnixSockets
