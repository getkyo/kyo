package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsProviderPlatform

/** A TLS session the test drives record by record over a [[RawPeer]], so a close-contract leaf can produce what a kyo connection never does
  * on cue: a close_notify with no FIN, records whose ciphertext the leaf holds back or splits, and a decrypt of exactly what reached the
  * wire, reporting whether the kyo side's close_notify arrived before its FIN.
  *
  * The engine is the cell provider's own in-process engine (BoringSSL, OpenSSL or the JDK SSLEngine), built through the same platform
  * registry the posix transport uses, so a leaf pins the peer to the provider under test. A provider with no in-process engine (Node's own
  * TLS) has no `PeerTls`; [[PeerTls.available]] says so. The engine is freed when the enclosing scope closes.
  */
final class PeerTls private (engine: TlsEngine, val peer: RawPeer):

    import AllowUnsafe.embrace.danger

    /** Runs the handshake to completion over the raw peer, sending what the engine produces and feeding what arrives. */
    def handshake()(using Frame): Unit < Async =
        Loop(()) { _ =>
            Sync.defer {
                val step = engine.handshakeStep()
                (step, PeerTls.drainAll(engine))
            }.map { (step, produced) =>
                val sent = if produced.isEmpty then Kyo.unit else peer.send(produced)
                sent.andThen {
                    if step == 1 then Loop.done(())
                    else if step == -2 then Abort.panic(new IllegalStateException("the peer's TLS handshake failed"))
                    else if !produced.isEmpty then Loop.continue(())
                    else
                        peer.recv().map {
                            case RawPeer.Read.Bytes(span) => Sync.defer(PeerTls.feed(engine, span)).andThen(Loop.continue(()))
                            case end => Abort.panic(new IllegalStateException(s"the stream ended during the peer's TLS handshake: $end"))
                        }
                }
            }
        }

    /** The ciphertext of `plain`, encrypted but not sent, so a leaf can send it whole, split or not at all. */
    def encrypt(plain: Span[Byte])(using Frame): Span[Byte] < Sync =
        Sync.defer {
            val bytes = plain.toArray
            var off   = 0
            while off < bytes.length do
                val n   = bytes.length - off
                val buf = Buffer.alloc[Byte](n)
                try
                    buf.copyFromArray(bytes, off, 0, n)
                    val consumed = engine.writePlain(buf, n)
                    if consumed <= 0 then throw new IllegalStateException(s"the peer's engine refused plaintext: $consumed")
                    off += consumed
                finally buf.close()
                end try
            end while
            PeerTls.drainAll(engine)
        }

    /** The ciphertext of this side's close_notify alert, not sent. */
    def closeNotify()(using Frame): Span[Byte] < Sync =
        Sync.defer {
            discard(engine.shutdownStep())
            PeerTls.drainAll(engine)
        }

    /** Encrypts and sends `plain`. */
    def send(plain: Span[Byte])(using Frame): Unit < Async = encrypt(plain).map(peer.send)

    /** Decrypts `ciphertext` as received, returning the plaintext it completes and whether a close_notify was among it. */
    def decrypt(ciphertext: Span[Byte])(using Frame): PeerTls.Decrypted < Sync =
        Sync.defer {
            PeerTls.feed(engine, ciphertext)
            val acc         = new java.io.ByteArrayOutputStream
            var closeNotify = false
            var more        = true
            while more do
                val out = Buffer.alloc[Byte](PeerTls.chunk)
                try
                    val n = engine.readPlain(out, PeerTls.chunk)
                    if n > 0 then acc.write(Buffer.copyToArray[Byte](out, 0, n))
                    else
                        if n == -3 then closeNotify = true
                        else if n == -2 then throw new IllegalStateException("the peer's engine rejected a record")
                        more = false
                    end if
                finally out.close()
                end try
            end while
            PeerTls.Decrypted(Span.fromUnsafe(acc.toByteArray), closeNotify)
        }

    /** Reads and decrypts until the stream ends: every plaintext byte, whether the kyo side's close_notify arrived, and how the stream ended. */
    def recvToEnd()(using Frame): PeerTls.Ended < Async =
        Loop(Chunk.empty[Array[Byte]], false) { (acc, closeNotify) =>
            peer.recv().map {
                case RawPeer.Read.Bytes(span) =>
                    decrypt(span).map(d => Loop.continue(acc.append(d.plain.toArray), closeNotify || d.closeNotify))
                case end => Loop.done(PeerTls.Ended(Span.fromUnsafe(acc.toArray.flatten), closeNotify, end))
            }
        }

end PeerTls

object PeerTls:

    import AllowUnsafe.embrace.danger

    private val chunk = 16 * 1024 + 512

    /** Plaintext decrypted from one batch of ciphertext, and whether a close_notify was in it. */
    final case class Decrypted(plain: Span[Byte], closeNotify: Boolean)

    /** Everything decrypted up to the stream's end, whether a close_notify preceded the end, and how the stream ended. */
    final case class Ended(plain: Span[Byte], closeNotify: Boolean, end: RawPeer.Read)

    /** Whether `provider` builds an in-process engine on this host. */
    def available(provider: String)(using Frame): Boolean =
        TlsProviderPlatform.engineProviderNames.contains(provider) &&
            Result.catching[Throwable](TlsProviderPlatform.engine(
                NetTlsConfig(trustAll = true, tlsProvider = Present(provider)),
                "localhost",
                isServer = false
            ))
                .map(_.free())
                .isSuccess

    /** The client side of a session over `peer`, trusting any certificate, on `provider`'s engine. Call [[handshake]] before sending. */
    def client(peer: RawPeer, provider: String)(using Frame): PeerTls < (Sync & Scope) =
        open(
            peer,
            TlsProviderPlatform.engine(NetTlsConfig(trustAll = true, tlsProvider = Present(provider)), "localhost", isServer = false)
        )

    /** The server side of a session over `peer`, presenting the shared test certificate, on `provider`'s engine. */
    def server(peer: RawPeer, provider: String)(using Frame): PeerTls < (Async & Scope) =
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val config = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath), tlsProvider = Present(provider))
            open(peer, TlsProviderPlatform.engine(config, "localhost", isServer = true))
        }

    private def open(peer: RawPeer, engine: => TlsEngine)(using Frame): PeerTls < (Sync & Scope) =
        Sync.defer(engine).map(e => Scope.ensure(Sync.defer(e.free())).andThen(new PeerTls(e, peer)))

    private def drainAll(engine: TlsEngine): Span[Byte] =
        val acc  = new java.io.ByteArrayOutputStream
        var more = true
        while more do
            val buf = Buffer.alloc[Byte](chunk)
            try
                val n = engine.drainCiphertext(buf, chunk)
                if n <= 0 then more = false
                else acc.write(Buffer.copyToArray[Byte](buf, 0, n))
            finally buf.close()
            end try
        end while
        Span.fromUnsafe(acc.toByteArray)
    end drainAll

    private def feed(engine: TlsEngine, ciphertext: Span[Byte]): Unit =
        if !ciphertext.isEmpty then
            val bytes = ciphertext.toArray
            val buf   = Buffer.fromArray[Byte](bytes)
            try
                if engine.feedCiphertext(buf, bytes.length) < 0 then throw new IllegalStateException("the peer's engine refused ciphertext")
            finally buf.close()
            end try
    end feed

end PeerTls
