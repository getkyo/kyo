package kyo.internal

/** Whether a test can serve a fake daemon on a Unix domain socket. Native runs only on POSIX hosts, which all bind AF_UNIX. */
private[kyo] object TestUnixSockets:
    val supported: Boolean = true
end TestUnixSockets
