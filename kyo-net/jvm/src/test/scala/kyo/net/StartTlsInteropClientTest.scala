package kyo.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.posix.PosixTestSockets

/** External TLS interoperability test: kyo-net io_uring client vs openssl s_server.
  *
  * Connects a kyo-net io_uring TLS client to an external openssl s_server process and exchanges app-data across 8
  * independent connections, asserting the TLS handshake and echo succeed every time. Any bad_record_mac from a
  * byte-shifted TLS record (e.g. caused by the cross-tail send race) would surface here as a handshake failure.
  *
  * The cross-tail mechanism is already covered by IoUringDriverCrossTailSendOrderTest (deterministic) and the CONC5 io_uring arm;
  * this test provides a supplementary external-interop check: kyo-net's BoringSSL TLS client vs the system OpenSSL
  * on the server side.
  *
  * Protocol: plain TLS from the first byte. The kyo-net client connects via TCP and immediately upgrades to TLS
  * (transport.upgradeToTls), matching the server which starts TLS on accept. After the handshake the client sends
  * "ping\n"; openssl s_server -rev reverses the line and echoes "gnip\n" back. A non-empty response asserts the
  * TLS session is functional.
  *
  * Server invocation uses minimal flags (-accept, -cert, -key, -rev) to avoid relying on container-specific OpenSSL
  * options such as -starttls or -no_dhe that may be absent in newer images. Stdout and stderr are captured into
  * serverOutput and included in failure messages for diagnostics.
  *
  * Gate: PosixTestSockets.assumeUring() and TlsRealEngines.assumeTlsReady() cancel off Linux / missing TLS provider.
  * probeOpenssl() cancels when the openssl binary is absent. s_server readiness timeout cancels (not fails) when
  * the process does not print ACCEPT within 15s.
  *
  * This file lives in jvm/src/test (JVM-only) because it spawns an external OS process; jvm-native/src/test would
  * compile for Native where openssl subprocess is not the right test strategy.
  */
class StartTlsInteropClientTest extends Test:

    import AllowUnsafe.embrace.danger

    private def probeOpenssl(): Boolean =
        try
            val p = new java.lang.ProcessBuilder("openssl", "version").start()
            p.waitFor() == 0
        catch case _: Exception => false

    /** Capture openssl output for diagnostics. */
    private def outputString(q: ConcurrentLinkedQueue[String]): String =
        val sb = new StringBuilder
        q.forEach(line => discard(sb.append(line).append('\n')))
        sb.toString
    end outputString

    "external TLS interop: kyo-net io_uring client vs openssl s_server, echo round-trip across 8 connections" in {
        PosixTestSockets.assumeUring()
        TlsRealEngines.assumeTlsReady()
        if !probeOpenssl() then cancel("openssl not available on this host")

        val maybeUring = TestBackends.all.find(_.name == "io_uring")
        if maybeUring.isEmpty then cancel("io_uring backend not registered on this host")
        val uringEntry = maybeUring.get
        if !uringEntry.isAvailable then cancel("io_uring not available on this host")

        TlsTestCertShared.writePems.flatMap { case (certPath, keyPath) =>
            Sync.defer(uringEntry.build(TransportConfig.default, summon[Frame])).flatMap { transport =>
                Sync.ensure(Sync.defer(transport.close())) {

                    val serverOutput = new ConcurrentLinkedQueue[String]()
                    val serverReady  = new AtomicBoolean(false)

                    // Pick an ephemeral port then start openssl s_server with minimal flags.
                    // -rev: server reverses each input line and echoes it back.
                    // No -starttls (removed in newer container images), no -no_dhe.
                    Sync.defer {
                        val s    = new ServerSocket(0)
                        val port = s.getLocalPort
                        s.close()
                        val pb = new java.lang.ProcessBuilder(
                            "openssl",
                            "s_server",
                            "-accept",
                            port.toString,
                            "-cert",
                            certPath,
                            "-key",
                            keyPath,
                            "-rev"
                        )
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        // Capture all s_server output in a daemon thread so it is visible on failure.
                        val readerThread = new Thread(
                            () =>
                                try
                                    val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
                                    var line   = reader.readLine()
                                    while line != null do
                                        discard(serverOutput.offer(line))
                                        if line.contains("ACCEPT") then serverReady.set(true)
                                        line = reader.readLine()
                                    end while
                                catch case _: Exception => (),
                            "openssl-reader"
                        )
                        readerThread.setDaemon(true)
                        readerThread.start()
                        (proc, port)
                    }.flatMap { case (proc, port) =>
                        Sync.ensure(Sync.defer { proc.destroy(); () }) {
                            // Poll serverReady up to 15s; cancel (not fail) on timeout since this
                            // is infrastructure, not a code defect.
                            Abort.run[Timeout](
                                Async.timeout(15.seconds) {
                                    Loop.foreach {
                                        if serverReady.get() then Loop.done(())
                                        else Async.sleep(100.millis).andThen(Loop.continue)
                                    }
                                }
                            ).flatMap {
                                case Result.Failure(_) =>
                                    Sync.defer(cancel(
                                        s"openssl s_server did not print ACCEPT within 15s on port $port " +
                                            s"(process alive=${proc.isAlive}). Output:\n${outputString(serverOutput)}"
                                    ))
                                case Result.Success(_) =>
                                    val clientTls =
                                        NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))

                                    // 8 iterations: each opens a new TCP connection, upgrades to TLS,
                                    // sends "ping\n", and reads the reversed echo from openssl -rev.
                                    // The TLS handshake would fail with bad_record_mac if the kyo-net
                                    // send-order mechanism were broken.
                                    Loop(0) { i =>
                                        if i >= 8 then Loop.done(())
                                        else
                                            Abort.run[Timeout | Closed](
                                                Async.timeout(15.seconds) {
                                                    for
                                                        conn    <- transport.connect("127.0.0.1", port).safe.get
                                                        tlsConn <- transport.upgradeToTls(conn, clientTls, 16).safe.get
                                                        payload = "ping\n".getBytes
                                                        _      <- tlsConn.outbound.safe.put(Span.fromUnsafe(payload))
                                                        echoed <- tlsConn.inbound.safe.take
                                                        _ = assert(
                                                            echoed.size > 0,
                                                            s"iteration $i: expected echo from openssl s_server -rev, got empty"
                                                        )
                                                    yield tlsConn.close()
                                                    end for
                                                }
                                            ).map {
                                                case Result.Failure(e) =>
                                                    fail(
                                                        s"iteration $i: TLS handshake or echo failed: $e. " +
                                                            s"openssl s_server output:\n${outputString(serverOutput)}"
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
    }

end StartTlsInteropClientTest
