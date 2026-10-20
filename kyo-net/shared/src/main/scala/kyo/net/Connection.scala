package kyo.net

import kyo.*

/** A live network connection providing inbound and outbound byte channels.
  *
  * WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
  *
  * Obtain a connection via Transport.connect or from a Transport.listen handler. Read bytes from inbound, write bytes via outbound.put, close
  * via close().
  *
  * Lifecycle: the connection is active until close() is called or the remote end closes it. After closing, the inbound channel ends once
  * the spans it already holds are taken.
  */
abstract class Connection:

    /** Inbound channel delivering byte spans as they arrive from the network. Once the connection closes it accepts no more spans and ends after
      * the ones it holds are taken.
      */
    def inbound: Channel.Unsafe[Span[Byte]]

    /** Outbound channel for buffering bytes to be sent to the network. The write pump drains it to the socket. */
    def outbound: Channel.Unsafe[Span[Byte]]

    /** Returns whether the connection is still open. Non-blocking. */
    def isOpen(using AllowUnsafe): Boolean

    /** Close the connection. Idempotent and non-blocking: it returns at once, and every later call, as well as a peer FIN or a read error
      * arriving during the close, changes nothing.
      *
      * The outbound channel stops accepting writes, and the spans it already holds, together with the one being written, are sent to the
      * peer in order before the socket is released. The release is therefore asynchronous unless nothing is left to write. A peer that stops
      * reading holds it for at most [[NetConfig.closeFlushGrace]] without progress, after which the unsent tail is dropped. The inbound
      * channel accepts no more spans and ends once the spans it holds are taken.
      */
    def close()(using AllowUnsafe, Frame): Unit

    /** Completes once the OS has released this connection's handle, with what happened to the bytes accepted before the close.
      *
      * Pending until the connection closes, by [[close]], by a peer's end or by a failure; it completes exactly once and never fails. Every
      * accepted byte reaching the kernel before the release is [[Connection.CloseOutcome.Flushed]]; a release that had to drop bytes, because
      * the peer stopped reading for a whole [[NetConfig.closeFlushGrace]] or a write failed, is [[Connection.CloseOutcome.Dropped]] with the
      * count and the cause. It completes on the driver's carrier, so await it as an `Async` suspension. An in-memory connection completes it
      * with `Flushed` at `close()`: every span it accepted is already in the peer's channel.
      */
    def released(using AllowUnsafe): Fiber.Unsafe[Connection.CloseOutcome, Any]

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
      *
      * The bytes arrive through a handover rather than a return value because the driver is detached only after the inbound channel closes,
      * so a ReadPump offer carrying the peer's first handshake flight can still be committing at that moment. Losing one strands the
      * handshake waiting for data that was read and dropped. The handover settles inside this call whenever no offer is in flight.
      */
    def detachForUpgrade()(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[Chunk[Span[Byte]]], Any]

    /** Start the connection. Begins pumping data between socket and channels. Called by Transport after creating the connection. Returns true
      * when the Created -> Established CAS won and the pumps started; false when the connection had already raced to a terminal or Upgrading
      * state before start (a close or detachForUpgrade racing start), in which case the caller must not hand it out as open.
      */
    private[net] def start()(using AllowUnsafe, Frame): Boolean

    /** Returns the RFC 5929 tls-server-end-point hash of the server's leaf certificate, or Absent if the connection is not TLS, has no peer
      * certificate, or is already closed.
      *
      * The hash is the one RFC 5929 section 4.1 selects: the hash of the certificate's own signature algorithm, or SHA-256 when that is MD5
      * or SHA-1, so a certificate signed with `ecdsa-with-SHA384` yields 48 bytes and one signed with `sha512WithRSAEncryption` 64. A
      * certificate whose signature has no single hash (Ed25519, RSASSA-PSS), a non-TLS connection, or a platform without TLS introspection
      * support returns Absent.
      */
    def serverCertificateHash: Maybe[Span[Byte]]

    /** How the inbound stream ended, observable after the [[inbound]] channel completes (RFC 8446 6.1 / RFC 5246 7.2.1 closure semantics).
      *
      * For a TLS connection this distinguishes an orderly close (the peer sent its authenticated close_notify before the TCP FIN) from a
      * truncation (the TCP connection ended without a close_notify, the truncation-attack condition the close_notify exchange exists to make
      * detectable). The default delivery of a record-boundary EOF is NOT rejected (a large population of real HTTP/1.1 servers close without a
      * close_notify), but the missing close_notify is made OBSERVABLE here so a length-aware caller can treat an unexpected drop as a
      * truncation. While the connection is still active this is [[Connection.Status.Active]].
      *
      * A non-TLS connection, which has no close_notify exchange, and a platform without TLS introspection support never report a truncation
      * distinction and return [[Connection.Status.Active]]; a TLS connection reports the observed close reason. The status is the close
      * reason, and a plaintext close has none, so a plaintext connection reports [[Connection.Status.Active]] after the close as well, its own
      * and its peer's. Whether the transport observes the reason at all is [[Transport.reportsTlsCloseReason]]: on one that does not, an
      * `Active` status at the close means nothing was observed, not that the peer sent a close_notify.
      */
    def status: Connection.Status
end Connection

object Connection:

    /** Why the connection's inbound stream is ending or has ended (see [[Connection.status]]).
      *
      * For a TLS connection this carries the security-relevant distinction RFC 8446 6.1 / RFC 5246 7.2.1 define: an orderly close terminated by
      * the peer's authenticated close_notify ([[Status.CleanClose]]) versus a connection that ended without one ([[Status.Truncated]]), which is the
      * truncation-attack condition. A length-aware caller (an HTTP layer that knows its framing) can treat a [[Status.Truncated]] end after an
      * incomplete message as a truncation while still accepting a [[Status.Truncated]] end after a complete length-framed message, the interop-safe
      * posture established stacks use (Go's `io.EOF` vs `io.ErrUnexpectedEOF`, OpenSSL's `ZERO_RETURN` vs `unexpected eof while reading`).
      *
      * This is the stream's close-reason, observable after [[Connection.status]] reports it: it is not a live open/closed connection status.
      * Only a TLS connection of a transport whose [[Transport.reportsTlsCloseReason]] holds ever reports a value other than
      * [[Status.Active]].
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

    /** What a connection's release did with the bytes accepted before it (see [[Connection.released]]).
      *
      * A byte is accepted once [[Connection.outbound]] took it. The release either handed every accepted byte to the kernel before it closed
      * the handle, or it dropped the ones it still held and reset the connection. A dropped byte is one the kernel never received: bytes the
      * kernel took before an abortive release may still be lost to the reset, which no transport can observe, so `unsentBytes` is a lower
      * bound on what the peer missed.
      *
      * A caller that needs to know its last write left the process awaits `released` after `close()` and reads the outcome; a caller that
      * needs to know the peer read it relies on its protocol's own acknowledgement.
      */
    enum CloseOutcome derives CanEqual:
        /** Every accepted byte reached the kernel before the handle was released. */
        case Flushed

        /** The release dropped `unsentBytes` accepted bytes the kernel never received, and reset the connection, because of `cause`: the
          * close-flush grace elapsed with no byte leaving, or a write failed.
          */
        case Dropped(unsentBytes: Long, cause: NetException)
    end CloseOutcome

end Connection
