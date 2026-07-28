package kyo.net.internal

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.Test

/** Deterministic structural test for NIO TLS read allocation reuse.
  *
  * Verifies that NioIoDriver.tryUnwrapBuffered writes decoded plaintext into the per-handle reused accumulator (NioTlsState.decryptAcc)
  * rather than a per-call local buffer. The pin: after a call that decodes K bytes, decryptAcc.size equals K, because tryUnwrapBuffered
  * resets the accumulator at the start and advances it by the decoded byte count without resetting at the end. A per-call local buffer
  * leaves decryptAcc.size at zero, making the assertion fail.
  *
  * This test is JVM-only because NioTlsState depends on javax.net.ssl.SSLEngine, which is absent on Scala Native and Scala.js.
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

    /** Drive a full TLS handshake between client and server engines in memory. Returns true when both engines report done. */
    private def driveHandshake(client: SSLEngine, server: SSLEngine): Boolean =
        var clientDone = false
        var serverDone = false
        var rounds     = 0
        while rounds < 200 && !(clientDone && serverDone) do
            rounds += 1
            if !clientDone then clientDone = stepEngine(client, server)
            if !serverDone then serverDone = stepEngine(server, client)
        end while
        clientDone && serverDone
    end driveHandshake

    /** Encrypt `plaintext` bytes using `sender` and return the resulting TLS ciphertext record. */
    private def encryptRecord(sender: SSLEngine, plaintext: Array[Byte])(using kyo.test.AssertScope, Frame): Array[Byte] =
        val src    = ByteBuffer.wrap(plaintext)
        val netBuf = ByteBuffer.allocate(sender.getSession.getPacketBufferSize + 512)
        val res    = sender.wrap(src, netBuf)
        assert(res.getStatus eq SSLEngineResult.Status.OK, s"wrap failed: $res")
        netBuf.flip()
        val out = new Array[Byte](netBuf.remaining())
        netBuf.get(out)
        out
    end encryptRecord

    /** tryUnwrapBuffered writes decoded plaintext into the per-handle reused accumulator.
      *
      * After each call that decodes K>0 bytes, decryptAcc.size equals K. If tryUnwrapBuffered used a fresh per-call buffer instead,
      * the field's size would remain 0 after each call because only the local buffer would be advanced.
      */
    "Nio TLS read decodes into the reused per-handle accumulator" in {
        val N = 16

        val serverConfig = kyo.net.NetTlsConfig(
            certChainPath = Present(kyo.net.internal.TlsTestCert.certPath),
            privateKeyPath = Present(kyo.net.internal.TlsTestCert.keyPath)
        )
        val clientConfig = kyo.net.NetTlsConfig(trustAll = true, hostnameVerification = false)

        val serverCtx = NioTransport.createSslContext(serverConfig, isServer = true)
        val clientCtx = NioTransport.createSslContext(clientConfig, isServer = false)

        val serverEngine = serverCtx.createSSLEngine("localhost", -1)
        serverEngine.setUseClientMode(false)
        serverEngine.beginHandshake()

        val clientEngine = clientCtx.createSSLEngine("localhost", -1)
        clientEngine.setUseClientMode(true)
        clientEngine.beginHandshake()

        assert(driveHandshake(clientEngine, serverEngine), "TLS handshake did not complete within 200 rounds")

        val session     = clientEngine.getSession
        val netInBuf    = ByteBuffer.allocate(session.getPacketBufferSize)
        val netOutBuf   = ByteBuffer.allocate(session.getPacketBufferSize)
        val appInBuf    = ByteBuffer.allocate(session.getApplicationBufferSize)
        val tlsState    = NioTlsState(clientEngine, netInBuf, netOutBuf, appInBuf)
        val capturedAcc = tlsState.decryptAcc

        val driver = NioIoDriver.init()

        try
            var i = 0
            while i < N do
                val plaintext  = Array.tabulate[Byte](i + 1)(j => (j + i).toByte)
                val ciphertext = encryptRecord(serverEngine, plaintext)

                // Write ciphertext into netInBuf in write mode; tryUnwrapBuffered does flip() itself.
                tlsState.netInBuf.clear()
                tlsState.netInBuf.put(ciphertext)

                val result = driver.tryUnwrapBuffered(tlsState)

                assert(result.isDefined, s"tryUnwrapBuffered returned Absent on iteration $i")
                val got = result.get.toArray
                assert(got.toList == plaintext.toList, s"iteration $i: expected ${plaintext.toList}, got ${got.toList}")

                // Identity check: the field must be the same instance (not reallocated).
                assert(tlsState.decryptAcc eq capturedAcc, s"decryptAcc was reallocated on iteration $i")

                // Size pin: tryUnwrapBuffered resets at start and advances by the decoded byte count
                // without resetting at the end. So decryptAcc.size must equal the plaintext length.
                // If tryUnwrapBuffered used a per-call local buffer instead of the field, the field's
                // size would remain 0 after the call, making this assertion fail.
                val accSize = tlsState.decryptAcc.size
                assert(
                    accSize == plaintext.length,
                    s"iteration $i: decryptAcc.size=$accSize but expected ${plaintext.length}; field unused or not advanced"
                )

                i += 1
            end while
            succeed
        finally
            driver.close()
        end try
    }

end NioTlsStateTest
