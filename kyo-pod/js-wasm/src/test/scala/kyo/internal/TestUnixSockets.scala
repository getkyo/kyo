package kyo.internal

/** Whether a test can serve a fake daemon on a Unix domain socket. Node has no AF_UNIX support on Windows: a filesystem listen path fails
  * to bind there.
  */
private[kyo] object TestUnixSockets:
    val supported: Boolean = !Platform.isWindows
end TestUnixSockets
