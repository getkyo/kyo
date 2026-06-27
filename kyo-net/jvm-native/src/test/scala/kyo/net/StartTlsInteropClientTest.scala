package kyo.net

import java.io.File
import kyo.*
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.tls.TlsRealEngines

/** STARTTLS interoperability test: kyo-net io_uring client vs an external openssl s_server.
  *
  * Validates that a kyo-net client performing a STARTTLS upgrade over the io_uring backend can exchange data with an external TLS server
  * without bad_record_mac. The cross-tail send wire-order mechanism ensures that the handshake's final raw flight (sent while handle.tls is
  * Absent) completes on the wire before the first post-upgrade TLS app-data flight. A collapsed or missing defer/kick lets the two send SQEs
  * race on the same fd; io_uring may reap them out of order, so the peer (openssl) reads a byte-shifted record and aborts with
  * bad_record_mac. 8 iterations on a single io_uring transport are enough to expose the race reliably.
  *
  * Protocol: PostgreSQL SSLRequest (int32 length=8, int32 80877103=0x04D2162F). The client sends the 8-byte startup packet, the openssl
  * server responds with 'S' (SSL supported), then both sides negotiate TLS. After the upgrade the test verifies the connection is
  * functional by checking the upgradeToTls call returns without error.
  *
  * Gate: PosixTestSockets.assumeUring() (cancels off Linux or where the ring cannot init), TlsRealEngines.assumeTlsReady() (cancels when
  * no BoringSSL provider is staged), and a live openssl binary probe (cancels when openssl is not on PATH). All three cancel gracefully.
  *
  * The openssl subprocess is started once per test and reused across all 8 iterations. Subprocess teardown is guarded by Sync.ensure so it
  * runs even if the test aborts. Server readiness is probed via transport.connect retry (catching Closed, retrying with Async.sleep) so no
  * thread is ever blocked.
  */
class StartTlsInteropClientTest extends Test:

    import AllowUnsafe.embrace.danger

    // PostgreSQL SSLRequest: int32(length=8) int32(80877103=0x04D2162F)
    private val sslRequest: Array[Byte] = Array[Byte](0, 0, 0, 8, 4, 0xd2.toByte, 0x16, 0x2f)

    private def runBlocking(cmd: String*): (Int, String) =
        val pb = new java.lang.ProcessBuilder(cmd*)
        pb.redirectErrorStream(true)
        val p   = pb.start()
        val out = new String(p.getInputStream.readAllBytes())
        (p.waitFor(), out)
    end runBlocking

    private def probeOpenssl(): Boolean =
        try runBlocking("openssl", "version")._1 == 0
        catch case _: Exception => false
    end probeOpenssl

    private def generateSelfSignedCert(): (String, String) =
        val certFile = File.createTempFile("kyo-starttls-interop-cert", ".pem")
        val keyFile  = File.createTempFile("kyo-starttls-interop-key", ".pem")
        certFile.deleteOnExit()
        keyFile.deleteOnExit()
        val (rc, out) = runBlocking(
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            keyFile.getAbsolutePath,
            "-out",
            certFile.getAbsolutePath,
            "-days",
            "1",
            "-nodes",
            "-subj",
            "/CN=localhost"
        )
        if rc != 0 then throw new RuntimeException(s"openssl req -x509 failed (rc=$rc): $out")
        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end generateSelfSignedCert

    /** Retry transport.connect until the server is accepting, then close the probe connection. Each retry waits 50ms via Async.sleep so
      * no thread is blocked between attempts. Throws if the server is still not ready after `maxAttempts`.
      */
    private def awaitServerReady(transport: Transport, port: Int, maxAttempts: Int)(using Frame): Unit < Async =
        Loop(0) { attempt =>
            if attempt >= maxAttempts then
                Sync.defer(throw new RuntimeException(
                    s"openssl s_server did not accept connections on port $port within ${maxAttempts * 50}ms"
                ))
            else
                Abort.run[Closed](
                    transport.connect("127.0.0.1", port).safe.get.map { conn => conn.close() }
                ).flatMap {
                    case Result.Success(_) => Loop.done(())
                    case Result.Failure(_) => Async.sleep(50.millis).andThen(Loop.continue(attempt + 1))
                }
        }
    end awaitServerReady

    "STARTTLS kyo-net io_uring client vs external openssl s_server: no bad_record_mac across 8 upgrade cycles" in {
        PosixTestSockets.assumeUring()
        TlsRealEngines.assumeTlsReady()
        if !Sync.Unsafe.evalOrThrow(Sync.defer(probeOpenssl())) then
            cancel("openssl not on PATH on this host")
        val maybeUring = TestBackends.all.find(_.name == "io_uring")
        if maybeUring.isEmpty then cancel("io_uring backend not registered on this host")
        val uringEntry = maybeUring.get
        if !uringEntry.isAvailable then cancel("io_uring not available on this host")

        Sync.defer {
            val (certPath, keyPath) = generateSelfSignedCert()
            // Probe for a free port before starting the server.
            val probe = new java.net.ServerSocket(0)
            val port  = probe.getLocalPort
            probe.close()
            val pb = new java.lang.ProcessBuilder(
                "openssl",
                "s_server",
                "-accept",
                port.toString,
                "-cert",
                certPath,
                "-key",
                keyPath,
                "-starttls",
                "postgres",
                "-quiet"
            )
            pb.redirectErrorStream(true)
            (port, pb.start())
        }.flatMap { case (port, proc) =>
            Sync.ensure(Sync.defer { proc.destroy(); discard(proc.waitFor()) }) {
                // Build the transport before the readiness probe so transport.connect can be used as the probe.
                Sync.defer(uringEntry.build(TransportConfig.default, summon[Frame])).flatMap { transport =>
                    Sync.ensure(Sync.defer(transport.close())) {
                        // Server readiness: retry transport.connect up to 40 times (max 2s), Async.sleep between attempts.
                        // A successful connect (then immediate close) proves openssl is accepting TCP connections.
                        awaitServerReady(transport, port, 40).andThen {
                            // trustAll because the cert is self-signed; sniHostname to pass SNI to openssl.
                            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
                            // 8 iterations: a collapsed-tails fold corrupts the stream on most iterations.
                            Loop(0) { i =>
                                if i >= 8 then Loop.done(())
                                else
                                    Abort.run[Timeout | Closed](
                                        Async.timeout(15.seconds) {
                                            for
                                                conn <- transport.connect("127.0.0.1", port).safe.get
                                                _    <- conn.outbound.safe.put(Span.fromUnsafe(sslRequest))
                                                resp <- conn.inbound.safe.take
                                                sChar = resp.toArray.headOption.getOrElse(0.toByte)
                                                _ = assert(
                                                    sChar == 'S'.toByte,
                                                    s"iteration $i: expected 'S' from openssl s_server after SSLRequest, got 0x${sChar.toInt.toHexString}"
                                                )
                                                tlsConn <- transport.upgradeToTls(conn, clientTls, 16).safe.get
                                            yield tlsConn.close()
                                            end for
                                        }
                                    ).map {
                                        case Result.Failure(e) =>
                                            fail(
                                                s"iteration $i: STARTTLS upgrade failed (bad_record_mac or Closed); " +
                                                    s"the cross-tail defer/kick mechanism must keep the raw and TLS sends in wire order: $e"
                                            )
                                            Loop.continue(i + 1)
                                        case Result.Success(_) =>
                                            Loop.continue(i + 1)
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

end StartTlsInteropClientTest
