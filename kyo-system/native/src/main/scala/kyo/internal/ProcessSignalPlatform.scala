package kyo.internal

import kyo.discard
import scala.scalanative.libc.signal as csignal
import scala.scalanative.meta.LinktimeInfo.isWindows
import scala.scalanative.posix.signal as posixsignal
import scala.scalanative.unsafe.CFuncPtr

/** Makes a write to a child process's pipe fail with an `IOException` instead of killing this process.
  *
  * A write to a pipe whose reader is gone (a child that exited, or closed its stdin) raises SIGPIPE, whose default action ends the writing
  * process. The JVM ignores SIGPIPE from startup, so there the write fails with EPIPE, which `java.io` reports as an `IOException`; that is
  * the contract kyo-system's `Process` gives its stdin on every platform, and Node gives it too. Scala Native installs nothing, so a write
  * to a dead child's stdin ended the whole program. A pipe has no per-descriptor opt-out (sockets have `SO_NOSIGPIPE` and `MSG_NOSIGNAL`),
  * so the disposition is set for the process, as the JVM does.
  *
  * A handler the application installed itself is left in place: SIGPIPE is ignored only when its disposition is still the default.
  */
private[kyo] object ProcessSignalPlatform:

    // Evaluated once, before the first child is spawned.
    private lazy val brokenPipesIgnored: Unit =
        if !isWindows then
            val previous = csignal.signal(posixsignal.SIGPIPE, csignal.SIG_IGN)
            if !isDefaultDisposition(previous) then
                discard(csignal.signal(posixsignal.SIGPIPE, previous))

    // SIG_DFL is the null function pointer on POSIX systems, and Scala Native represents a null function pointer as a null reference.
    private def isDefaultDisposition(handler: CFuncPtr): Boolean = (handler eq null) || {
        val address = CFuncPtr.toPtr(handler)
        (address eq null) || address.toLong == 0L
    }

    /** Ensures pipe writes to a child fail with EPIPE rather than raising SIGPIPE. Idempotent. */
    def ignoreBrokenPipes(): Unit = brokenPipesIgnored

end ProcessSignalPlatform
