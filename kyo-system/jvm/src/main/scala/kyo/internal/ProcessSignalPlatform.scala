package kyo.internal

/** Makes a write to a child process's pipe fail with an `IOException` instead of killing this process.
  *
  * Nothing to do on the JVM: it ignores SIGPIPE from startup, so a write to a pipe whose reader is gone fails with EPIPE, which `java.io`
  * reports as an `IOException`. The Scala Native counterpart installs the same disposition.
  */
private[kyo] object ProcessSignalPlatform:

    /** Ensures pipe writes to a child fail with EPIPE rather than raising SIGPIPE. Idempotent. */
    def ignoreBrokenPipes(): Unit = ()

end ProcessSignalPlatform
