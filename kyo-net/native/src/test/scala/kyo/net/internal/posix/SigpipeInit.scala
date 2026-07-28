package kyo.net.internal.posix

import scala.scalanative.libc.signal as csignal
import scala.scalanative.posix.signal as posixsignal

/** Scala Native SIGPIPE suppression for the test process.
  *
  * On the JVM, the runtime installs SIG_IGN for SIGPIPE globally at startup, so writes to a peer-closed socket return EPIPE instead of killing
  * the process. Scala Native does not install this handler, so the test process can be killed by SIGPIPE when a raw socket write hits a
  * peer-closed connection during test teardown. Production send paths suppress SIGPIPE via SO_NOSIGPIPE (macOS) or MSG_NOSIGNAL (Linux), but
  * the test helpers in PosixTestSockets use raw SocketBindings calls that do not apply those flags on every path.
  *
  * Calling install() once at test startup matches the JVM behavior: writes to a closed peer return EPIPE (errno) instead of raising the signal.
  * This is safe in a test process: SIGPIPE suppression is standard practice in servers and test harnesses that use raw sockets.
  */
private[posix] object SigpipeInit:
    def install(): Unit =
        csignal.signal(posixsignal.SIGPIPE, csignal.SIG_IGN)
        ()
end SigpipeInit
