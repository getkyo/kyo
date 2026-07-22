package kyo.net.internal.posix

/** JVM stub: the JVM runtime globally installs SIG_IGN for SIGPIPE at startup, so no action is needed here. */
private[posix] object SigpipeInit:
    def install(): Unit = ()
end SigpipeInit
