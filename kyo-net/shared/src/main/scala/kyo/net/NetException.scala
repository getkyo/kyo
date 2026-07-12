package kyo.net

import kyo.*
import scala.util.control.NoStackTrace

/** Typed failure hierarchy for [[Transport]]. Every backend (Posix io_uring/epoll/kqueue, the NIO floor, Node) produces the SAME leaf for the
  * same failure mode at the public seam, so a caller can tell a name-resolution failure from a connection refusal from a Unix-socket error
  * without string-matching a message.
  *
  * `NetException` extends [[Closed]], so `Abort[NetException]` is a strict subtype of the historical `Abort[Closed]`: every existing
  * `Abort.recover[Closed]` keeps catching every transport failure, while new code can recover a specific leaf (`Abort.recover[NetDnsResolutionException]`).
  * The leaves carry the structured fields a caller needs (`host`, `port`, `path`, and an optional underlying `cause`); the rendered message embeds
  * a short description for logs.
  *
  * Every failure message lives in this file. A backend constructs a leaf from structured data alone (a host, a port, and the underlying cause it
  * already holds: a captured `Throwable`, or a [[NetErrno]] for a raw error number) and never authors failure prose at the call site.
  */
sealed abstract class NetException(detail: String)(using frame: Frame) extends Closed("Transport", frame, detail)

object NetException:
    /** Renders a cause to a non-empty description, or the empty string when there is no cause. Reads the cause's own message; authors no prose. */
    private[net] def show(cause: String | Throwable): String =
        cause match
            case t: Throwable =>
                val message = t.getMessage
                if message != null && message.nonEmpty then message else t.toString
            case s: String => s

    /** The `": <cause>"` suffix a leaf appends to its message, empty when there is no cause. */
    private[net] def suffix(cause: String | Throwable): String =
        val rendered = show(cause)
        if rendered.isEmpty then "" else s": $rendered"
end NetException

/** A native error number (errno), used as the `cause` of a transport leaf when the underlying failure is a raw OS error code rather than a
  * `Throwable`. The message is rendered here so a backend passes only the number.
  */
final class NetErrno(val code: Int) extends RuntimeException(s"errno=$code") with NoStackTrace

/** A TCP connect to `host:port` failed (connection refused, host or network unreachable, reset, ...). */
final case class NetConnectException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"connect to $host:$port failed${NetException.suffix(cause)}")

/** Name resolution for `host` failed (no such host, no address, temporary resolver failure, ...) before any socket could be created. */
final case class NetDnsResolutionException(host: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"DNS resolution failed for '$host'${NetException.suffix(cause)}")

/** A connect to the Unix-domain socket at `path` failed (no such file, connection refused, permission denied, ...). */
final case class NetUnixConnectException(path: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"connect to Unix socket '$path' failed${NetException.suffix(cause)}")

/** A TCP connect to `host:port` did not complete within `timeout`. */
final case class NetConnectTimeoutException(host: String, port: Int, timeout: Duration)(using Frame)
    extends NetException(s"connect to $host:$port timed out after $timeout")

/** Binding/listening on `host:port` (or the bind step of a Unix listener) failed (address already in use, permission denied, ...). */
final case class NetBindException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"bind/listen on $host:$port failed${NetException.suffix(cause)}")

/** The TLS handshake with `host:port` failed (untrusted chain, hostname mismatch, no common protocol version, malformed record, ...). For a
  * STARTTLS upgrade over an established connection there is no fresh port, so `port` is `-1`.
  */
final case class NetTlsHandshakeException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"TLS handshake with $host:$port failed${NetException.suffix(cause)}")

/** stdio is not supported by this transport (the default for transports without a stdio stream). */
final case class NetStdioUnsupportedException()(using Frame)
    extends NetException("stdio is not supported by this transport")

/** A stdio connection is already open (fds 0/1 are process-global, so only one can exist at a time). */
final case class NetStdioAlreadyOpenException()(using Frame)
    extends NetException("a stdio connection is already open")

/** The connection cannot be upgraded to TLS (an in-memory connection, or one without an upgradable handle). */
final case class NetNotUpgradableException()(using Frame)
    extends NetException("the connection is not upgradable to TLS")

/** The connection has already been detached for a TLS upgrade (a second upgrade was attempted on the same connection). */
final case class NetAlreadyDetachedException()(using Frame)
    extends NetException("the connection is already detached for upgrade")
