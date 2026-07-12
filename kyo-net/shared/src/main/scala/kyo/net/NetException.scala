package kyo.net

import kyo.*
import scala.util.control.NoStackTrace

/** Typed failure hierarchy for [[Transport]]. Every backend (Posix io_uring/epoll/kqueue, the NIO floor, Node) produces the SAME leaf
  * for the same failure mode at the public seam, so a caller can tell a name-resolution failure from a connection refusal from a
  * Unix-socket error without string-matching a message.
  *
  * `NetException` roots at [[kyo.KyoException]] as a SIBLING of [[Closed]] (never a subtype), so `Abort[NetException]` is a distinct
  * error channel from `Abort[Closed]`: a transport-establishment failure is recovered by `Abort.recover[NetException]` and is never
  * swallowed by an `Abort[Closed]` handler meant for a closed channel. The leaves carry the structured fields a caller needs (`host`,
  * `port`, `path`, and an optional underlying `cause`); the rendered message embeds a short description for logs.
  *
  * Every failure message lives in this file. A backend constructs a leaf from structured data alone (a host, a port, and the
  * underlying cause it already holds: a captured `Throwable`, or a [[NetErrno]] for a raw error number) and never authors failure
  * prose at the call site. The cause is carried STRUCTURALLY (forwarded to [[kyo.KyoException]]), so `getCause` returns it, never a
  * message-only rendering.
  */
sealed abstract class NetException(detail: String, cause: String | Throwable = "")(using frame: Frame)
    extends KyoException(detail, cause)

/** A native error number (errno), used as the `cause` of a transport leaf when the underlying failure is a raw OS error code rather
  * than a `Throwable`. `code` is the structural source of truth a caller reads via [[code]]; the `errno=$code` message is a rendering
  * of it for logs.
  */
final class NetErrno(val code: Int)(using Frame) extends KyoException(s"errno=$code") with NoStackTrace

/** A TCP connect to `host:port` failed (connection refused, host or network unreachable, reset, ...). */
final case class NetConnectException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"connect to $host:$port failed", cause)

/** Name resolution for `host` failed (no such host, no address, temporary resolver failure, ...) before any socket could be created. */
final case class NetDnsResolutionException(host: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"DNS resolution failed for '$host'", cause)

/** A connect to the Unix-domain socket at `path` failed (no such file, connection refused, permission denied, ...). */
final case class NetUnixConnectException(path: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"connect to Unix socket '$path' failed", cause)

/** A TCP connect to `host:port` did not complete within `timeout`. */
final case class NetConnectTimeoutException(host: String, port: Int, timeout: Duration)(using Frame)
    extends NetException(s"connect to $host:$port timed out after $timeout")

/** Binding/listening on `host:port` (or the bind step of a Unix listener) failed (address already in use, permission denied, ...). */
final case class NetBindException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"bind/listen on $host:$port failed", cause)

/** The TLS handshake with `host:port` failed (untrusted chain, hostname mismatch, no common protocol version, malformed record, ...).
  * For a STARTTLS upgrade over an established connection there is no fresh port, so `port` is `-1`.
  */
final case class NetTlsHandshakeException(host: String, port: Int, cause: String | Throwable = "")(using Frame)
    extends NetException(s"TLS handshake with $host:$port failed", cause)

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

/** No usable io backend: a forced backend (`-Dkyo.net.backend`) was named but is unavailable (`forced = Present(name)`), or none of
  * the registered backends is available on this host (`forced = Absent`). One leaf, both cases (representable == legal).
  */
final case class NetBackendUnavailableException(forced: Maybe[String], cause: String | Throwable = "")(using Frame)
    extends NetException(NetBackendUnavailableException.message(forced), cause)

object NetBackendUnavailableException:
    private[net] def message(forced: Maybe[String]): String =
        forced match
            case Present(name) => s"forced io backend '$name' is unavailable"
            case Absent        => "no io backend is available on this transport"
end NetBackendUnavailableException

/** A selected io backend was available (its cheap probe passed) but failed to initialize its driver. `recoverable` is the runtime
  * predicate `IoBackend.buildFirst` keys on: `true` degrades to the next backend (io_uring queue_init, which is io_uring-specific),
  * `false` propagates (the wake eventfd / epoll registerWake, a shared liveness primitive no fallback can supply).
  */
final case class NetBackendInitException(backend: String, cause: String | Throwable, recoverable: Boolean)(using Frame)
    extends NetException(s"io backend '$backend' failed to initialize", cause)

/** The pinned TLS provider (`NetTlsConfig.tlsProvider`) is unavailable on this transport (registered-but-unavailable, or a provider
  * this transport cannot serve). One leaf for the provider-pin category across JVM/Native/JS.
  */
final case class NetTlsProviderUnavailableException(providerId: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"TLS provider '$providerId' is unavailable on this transport", cause)

/** A TLS configuration is fail-closed rejected: a verifying client has no reference identity, so the server certificate cannot be
  * checked. One fixed decision, one leaf across JVM/Native/JS; the message is assembled here, never passed in by the call site.
  */
final case class NetTlsConfigException()(using Frame)
    extends NetException(
        "verifying client has no reference identity: a hostname is required to verify the server certificate " +
            "(set trustAll or hostnameVerification = false to opt out of name verification)"
    )

/** A native TLS setup step failed (SSL_CTX_new / SSL_new allocation, or a configured PEM that could not be read). One leaf for the
  * native TLS-setup category (posix OpenSSL/BoringSSL backends).
  */
final case class NetTlsSetupException(operation: String, cause: String | Throwable = "")(using Frame)
    extends NetException(s"TLS setup step '$operation' failed", cause)
