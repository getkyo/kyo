package kyo.net.internal

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.Test

/** NioTlsState's read-side unwrap and its plaintext carry, against a real in-memory JDK TLS session, with the engine reached through a
  * [[NioGate]].
  *
  * JVM-only because NioTlsState depends on javax.net.ssl.SSLEngine, which is absent on Scala Native and Scala.js.
  */
class NioTlsStateTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val needWrap       = SSLEngineResult.HandshakeStatus.NEED_WRAP
    private val needTask       = SSLEngineResult.HandshakeStatus.NEED_TASK
    private val hsFinished     = SSLEngineResult.HandshakeStatus.FINISHED
    private val notHandshaking = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING

    /** Drive a single handshake step for one engine and pipe ciphertext to the peer. Returns true when the engine is done. */
    private def stepEngine(engine: SSLEngine, peer: SSLEngine): Boolean =
        val hs = engine.getHandshakeStatus
        if hs eq needWrap then
            val netBuf = ByteBuffer.allocate(engine.getSession.getPacketBufferSize + 512)
            val res    = engine.wrap(ByteBuffer.allocate(0), netBuf)
            netBuf.flip()
            if netBuf.hasRemaining then
                discard(peer.unwrap(netBuf, ByteBuffer.allocate(peer.getSession.getApplicationBufferSize + 512)))
            val afterStatus = res.getHandshakeStatus
            (afterStatus eq hsFinished) || (afterStatus eq notHandshaking)
        else if hs eq needTask then
            var t = engine.getDelegatedTask
            while t != null do
                t.run()
                t = engine.getDelegatedTask
            false
        else if (hs eq hsFinished) || (hs eq notHandshaking) then
            true
        else
            false // NEED_UNWRAP: waiting for peer to wrap first
        end if
    end stepEngine

    /** A client and a server engine with a completed TLS handshake, driven in memory. */
    private def handshakedPair()(using kyo.test.AssertScope): (SSLEngine, SSLEngine) =
        val serverConfig = kyo.net.NetTlsConfig(
            certChainPath = Present(kyo.net.internal.TlsTestCert.certPath),
            privateKeyPath = Present(kyo.net.internal.TlsTestCert.keyPath)
        )
        val clientConfig = kyo.net.NetTlsConfig(trustAll = true, hostnameVerification = false)
        val server       = NioTransport.createSslContext(serverConfig, isServer = true).createSSLEngine("localhost", -1)
        server.setUseClientMode(false)
        server.beginHandshake()
        val client = NioTransport.createSslContext(clientConfig, isServer = false).createSSLEngine("localhost", -1)
        client.setUseClientMode(true)
        client.beginHandshake()
        var clientDone = false
        var serverDone = false
        var rounds     = 0
        while rounds < 200 && !(clientDone && serverDone) do
            rounds += 1
            if !clientDone then clientDone = stepEngine(client, server)
            if !serverDone then serverDone = stepEngine(server, client)
        end while
        assert(clientDone && serverDone, "TLS handshake did not complete within 200 rounds")
        (client, server)
    end handshakedPair

    /** Encrypt `plaintext` bytes using `sender` and return the resulting TLS ciphertext record. */
    private def encryptRecord(sender: SSLEngine, plaintext: Array[Byte])(using kyo.test.AssertScope): Array[Byte] =
        val src    = ByteBuffer.wrap(plaintext)
        val netBuf = ByteBuffer.allocate(sender.getSession.getPacketBufferSize + 512)
        val res    = sender.wrap(src, netBuf)
        assert(res.getStatus eq SSLEngineResult.Status.OK, s"wrap failed: $res")
        netBuf.flip()
        val out = new Array[Byte](netBuf.remaining())
        netBuf.get(out)
        out
    end encryptRecord

    private def staged(records: Array[Byte]*): AtomicRef.Unsafe[Chunk[Array[Byte]]] =
        AtomicRef.Unsafe.init(Chunk.from(records))

    "each unwrap decodes into the reused per-session accumulator" in {
        val (client, server) = handshakedPair()
        val gate             = new NioGate
        val tls              = NioTlsState.init(client)
        (0 until 16).foreach { i =>
            val plaintext = Array.tabulate[Byte](i + 1)(j => (j + i).toByte)
            val result    = gate.locked(tls.unwrapBuffered(staged(encryptRecord(server, plaintext))))
            val taken     = tls.takePlaintext().map(_.toList)
            // The accumulator keeps the pass's byte count until the next pass resets it, so a per-call buffer would leave it at 0.
            assert(
                (result, taken, tls.lastDecryptSize) == (NioTlsState.Inbound.Decrypted, Present(plaintext.toList), plaintext.length),
                s"iteration $i"
            )
        }
        succeed
    }

    "an unwrap tried while another section holds the gate runs nothing and leaves its staged ciphertext staged" in {
        val (client, server) = handshakedPair()
        val engine           = new UnwrapCallbackEngine(client)
        val gate             = new NioGate
        val tls              = NioTlsState.init(engine)
        val first            = staged(encryptRecord(server, Array[Byte](1, 2, 3)))
        val second           = staged(encryptRecord(server, Array[Byte](4, 5, 6)))
        // The outer unwrap holds the gate while its engine.unwrap runs; the nested attempt comes from inside it.
        var nested: Maybe[NioTlsState.Inbound] = Absent
        engine.onNextUnwrap(() => nested = gate.tryLocked(tls.unwrapBuffered(second)))
        val outer      = gate.locked(tls.unwrapBuffered(first))
        val stillThere = second.get().size
        val firstText  = tls.takePlaintext().map(_.toList)
        val later      = gate.locked(tls.unwrapBuffered(second))
        val secondText = tls.takePlaintext().map(_.toList)
        assert(
            (nested, stillThere, outer, firstText, later, secondText) ==
                (
                    Absent,
                    1,
                    NioTlsState.Inbound.Decrypted,
                    Present(List[Byte](1, 2, 3)),
                    NioTlsState.Inbound.Decrypted,
                    Present(List[Byte](4, 5, 6))
                )
        )
    }

    "plaintext decrypted by two unwraps waits in decrypt order, and a returned take goes back ahead of it" in {
        val (client, server) = handshakedPair()
        val gate             = new NioGate
        val tls              = NioTlsState.init(client)
        discard(gate.locked(tls.unwrapBuffered(staged(encryptRecord(server, Array[Byte](1, 2))))))
        val taken = tls.takePlaintext()
        discard(gate.locked(tls.unwrapBuffered(staged(encryptRecord(server, Array[Byte](3, 4))))))
        taken.foreach(tls.returnPlaintext)
        assert(tls.takePlaintext().map(_.toList) == Present(List[Byte](1, 2, 3, 4)))
    }

    "an unwrap that throws on a bad record ends the read and every later one, and marks the stream truncated" in {
        val (client, server) = handshakedPair()
        val gate             = new NioGate
        val tls              = NioTlsState.init(client)
        val record           = encryptRecord(server, Array[Byte](1, 2, 3))
        // flip a byte inside the record's authenticated payload so its MAC check fails
        record(record.length - 1) = (record(record.length - 1) ^ 0x5a).toByte
        val first = gate.locked(tls.unwrapBuffered(staged(record)))
        val later = gate.locked(tls.unwrapBuffered(staged(encryptRecord(server, Array[Byte](4)))))
        assert((first, later, tls.peerEof, tls.peerCleanClose) == (NioTlsState.Inbound.PeerFin, NioTlsState.Inbound.PeerFin, true, false))
    }

end NioTlsStateTest
