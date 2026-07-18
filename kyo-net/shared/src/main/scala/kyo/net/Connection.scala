package kyo.net

import kyo.*

/** A live network connection providing inbound and outbound byte channels.
  *
  * WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
  *
  * Obtain a connection via Transport.connect or from a Transport.listen handler. Read bytes from inbound, write bytes via outbound.put, close
  * via close().
  *
  * Lifecycle: the connection is active until close() is called or the remote end closes it. After closing, the inbound channel completes.
  */
abstract class Connection:

    /** Inbound channel delivering byte spans as they arrive from the network. Completes when the connection is closed. */
    def inbound: Channel.Unsafe[Span[Byte]]

    /** Outbound channel for buffering bytes to be sent to the network. The write pump drains it to the socket. */
    def outbound: Channel.Unsafe[Span[Byte]]

    /** Returns whether the connection is still open. Non-blocking. */
    def isOpen(using AllowUnsafe): Boolean

    /** Close the connection. Closes channels and the underlying socket. Synchronous, idempotent. */
    def close()(using AllowUnsafe, Frame): Unit

    /** Fiber that completes when this connection begins closing: `close()` wins the close, or a peer FIN / read-error / write-error teardown
      * reaches the connection's internal close. Never consumes inbound/outbound bytes, so an observer built on it steals no buffered data, and
      * completes immediately if the connection is already closing. Does NOT fire on [[detachForUpgrade]] (a STARTTLS detach leaves the fd open
      * for the in-place upgrade; the upgraded connection is a fresh [[Connection]] with its own signal). Internal: kyo-http's server dispatch
      * observes it to interrupt a handler parked on a foreign await when its connection closes, instead of leaking the fiber.
      */
    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any]

    /** Detach the underlying socket for reuse in a TLS upgrade, WITHOUT closing the socket.
      *
      * Closes the inbound/outbound channels (causing pumps to stop) and cancels the driver registration, but does not close the underlying
      * file descriptor. This leaves the socket open so the caller can drive a TLS handshake on the same fd and then create a new Connection
      * over it.
      *
      * Returns any bytes that the ReadPump had already placed in the inbound channel. These bytes are raw ciphertext that must be fed to the
      * TLS engine before registering for further socket reads, to avoid silently discarding data that was already consumed from the kernel
      * buffer. Returns Absent if the connection was already closed (idempotent).
      */
    def detachForUpgrade()(using AllowUnsafe, Frame): Maybe[Chunk[Span[Byte]]]

    /** Start the connection. Begins pumping data between socket and channels. Called by Transport after creating the connection. Returns true
      * when the Created -> Established CAS won and the pumps started; false when the connection had already raced to a terminal or Upgrading
      * state before start (a close or detachForUpgrade racing start), in which case the caller must not hand it out as open.
      */
    private[net] def start()(using AllowUnsafe, Frame): Boolean

    /** Returns the SHA-256 hash of the server's leaf certificate DER bytes (RFC 5929 tls-server-end-point), or Absent if the connection is
      * not TLS, has no peer certificate, or is already closed.
      *
      * A non-TLS connection, or a platform without TLS introspection support, returns Absent.
      */
    def serverCertificateHash: Maybe[Span[Byte]]

    /** How the inbound stream ended, observable after the [[inbound]] channel completes (RFC 8446 6.1 / RFC 5246 7.2.1 closure semantics).
      *
      * For a TLS connection this distinguishes an orderly close (the peer sent its authenticated close_notify before the TCP FIN) from a
      * truncation (the TCP connection ended without a close_notify, the truncation-attack condition the close_notify exchange exists to make
      * detectable). The default delivery of a record-boundary EOF is NOT rejected (a large population of real HTTP/1.1 servers close without a
      * close_notify), but the missing close_notify is made OBSERVABLE here so a length-aware caller can treat an unexpected drop as a
      * truncation. While the connection is still active this is [[Status.Active]].
      *
      * A non-TLS connection, which has no close_notify exchange, and a platform without TLS introspection support never report a truncation
      * distinction and return [[Status.Active]]; a TLS connection reports the observed close reason.
      */
    def status: Connection.Status
end Connection

object Connection:

    /** Why the connection's inbound stream is ending or has ended (see [[Connection.status]]).
      *
      * For a TLS connection this carries the security-relevant distinction RFC 8446 6.1 / RFC 5246 7.2.1 define: an orderly close terminated by
      * the peer's authenticated close_notify ([[CleanClose]]) versus a connection that ended without one ([[Truncated]]), which is the
      * truncation-attack condition. A length-aware caller (an HTTP layer that knows its framing) can treat a [[Truncated]] end after an
      * incomplete message as a truncation while still accepting a [[Truncated]] end after a complete length-framed message, the interop-safe
      * posture established stacks use (Go's `io.EOF` vs `io.ErrUnexpectedEOF`, OpenSSL's `ZERO_RETURN` vs `unexpected eof while reading`).
      *
      * This is the stream's close-reason, observable after [[Connection.status]] reports it: it is not a live open/closed connection status.
      */
    enum Status derives CanEqual:
        /** The connection is still open: no close has been observed yet. */
        case Active

        /** Closed locally via [[Connection.close]] before any peer-initiated close was observed. */
        case LocalClose

        /** The peer ended the stream cleanly: its authenticated TLS close_notify was received before the TCP FIN (RFC 8446 6.1 orderly close).
          * For a length-framed protocol this confirms the stream reached its end as the sender intended.
          */
        case CleanClose

        /** The peer's TCP connection ended WITHOUT a close_notify (a bare FIN). This is the truncation-attack condition: it is not rejected by
          * default (real HTTP/1.1 servers commonly close without a close_notify after a complete length-framed message), but it is surfaced here
          * so a length-aware caller can treat a drop mid-message as a truncation.
          */
        case Truncated
    end Status

end Connection
