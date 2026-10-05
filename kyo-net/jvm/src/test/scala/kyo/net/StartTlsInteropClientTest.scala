package kyo.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
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
  * The cross-tail mechanism is already covered by IoUringDriverCrossTailSendOrderTest (deterministic) and the concurrent STARTTLS cross-tail io_uring arm;
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

    /** An s_server that printed ACCEPT, the port it accepts on, and its output so far. */
    final private case class Server(proc: java.lang.Process, port: Int, output: ConcurrentLinkedQueue[String])

    /** Why one s_server launch did not reach ACCEPT. Only `PortTaken` is worth another port. */
    private enum ServeFailure derives CanEqual:
        case PortTaken(port: Int, output: String)
        case NotStarted(message: String)

    /** Starts `openssl s_server -rev` on 127.0.0.1 at a port outside the ephemeral range, choosing another while the one chosen is taken,
      * or answers why it could not. `choose` maps each port [[NonEphemeralPort]] picks to the one launched, so a leaf can hand it a port it
      * holds.
      *
      * Served on the loopback address the client connects to: with SO_REUSEADDR a wildcard bind can share a port another process
      * listens on at 127.0.0.1, and the client's connect then reaches that listener. No -starttls (removed in newer container images),
      * no -no_dhe.
      */
    private def serve(certPath: String, keyPath: String, choose: Int => Int = identity)(using Frame): Result[String, Server] < Async =
        val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
        Abort.run[ServeFailure](
            NonEphemeralPort.bind[Server, ServeFailure, Any] {
                case _: ServeFailure.PortTaken => true
                case _                         => false
            } { port =>
                discard(attempts.incrementAndGet())
                startOnce(certPath, keyPath, choose(port))
            }
        ).map {
            case Result.Success(server)                               => Result.succeed(server)
            case Result.Failure(ServeFailure.PortTaken(port, output)) =>
                Result.fail(s"openssl s_server found port $port taken after ${attempts.get()} attempts. Output:\n$output")
            case Result.Failure(ServeFailure.NotStarted(message)) =>
                Result.fail(s"$message after ${attempts.get()} attempts")
            case Result.Panic(e) => Result.panic(e)
        }
    end serve

    /** One s_server launch on `port`: waits for it to print ACCEPT or to exit. The bind failure is read from the output because OpenSSL 3
      * exits with status 0 after `BIO_bind`. It is matched on OpenSSL's own reason, "unable to bind socket", because the system error
      * before it is platform text: "Address already in use" on Linux and macOS, "Unknown error" for WSAEADDRINUSE on Windows.
      */
    private def startOnce(certPath: String, keyPath: String, port: Int)(using Frame): Server < (Async & Abort[ServeFailure]) =
        val output = new ConcurrentLinkedQueue[String]()
        val ready  = new AtomicBoolean(false)
        val ended  = new AtomicBoolean(false)
        Sync.defer {
            val pb = new java.lang.ProcessBuilder(
                "openssl",
                "s_server",
                "-accept",
                s"127.0.0.1:$port",
                "-cert",
                certPath,
                "-key",
                keyPath,
                "-rev"
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            // Capture all s_server output in a daemon thread so it is visible on failure; the end of the stream is the exit.
            val readerThread = new Thread(
                () =>
                    try
                        val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
                        var line   = reader.readLine()
                        while line != null do
                            discard(output.offer(line))
                            if line.contains("ACCEPT") then ready.set(true)
                            line = reader.readLine()
                        end while
                    catch case _: Exception => ()
                    finally ended.set(true),
                "openssl-reader"
            )
            readerThread.setDaemon(true)
            readerThread.start()
            proc
        }.map { proc =>
            Abort.run[Timeout](
                Async.timeout(15.seconds) {
                    Loop.foreach {
                        if ready.get() || ended.get() then Loop.done(())
                        else Async.sleep(10.millis).andThen(Loop.continue)
                    }
                }
            ).map { waited =>
                val text = outputString(output)
                if ready.get() then Server(proc, port, output)
                else
                    Sync.defer(proc.destroy()).andThen {
                        if waited.isSuccess && text.contains("unable to bind socket") then
                            Abort.fail(ServeFailure.PortTaken(port, text))
                        else
                            val why = if waited.isSuccess then "exited before printing ACCEPT" else "did not print ACCEPT within 15s"
                            Abort.fail(ServeFailure.NotStarted(s"openssl s_server $why on port $port. Output:\n$text"))
                    }
                end if
            }
        }
    end startOnce

    "external TLS interop: kyo-net io_uring client vs openssl s_server, echo round-trip across 8 connections" in {
        PosixTestSockets.assumeUring()
        TlsRealEngines.assumeTlsReady()
        if !probeOpenssl() then cancel("openssl not available on this host")

        val maybeUring = TestBackends.all.find(_.name == "io_uring")
        if maybeUring.isEmpty then cancel("io_uring backend not registered on this host")
        val uringEntry = maybeUring.get
        if !uringEntry.isAvailable then cancel("io_uring not available on this host")

        TlsTestCertShared.writePems.flatMap { case (certPath, keyPath) =>
            Sync.defer(uringEntry.transport).flatMap { transport =>
                // -rev: s_server reverses each input line and echoes it back.
                // A server that would not start is the host's openssl failing, not kyo-net, so the leaf cancels with its output.
                serve(certPath, keyPath).map {
                    case Result.Success(server) => server
                    case Result.Failure(why)    => cancel(why)
                    case Result.Panic(e)        => throw e
                }.flatMap { server =>
                    val port         = server.port
                    val serverOutput = server.output
                    Sync.ensure(Sync.defer { server.proc.destroy(); () }) {
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
                                        // Scoped per-iteration (not the leaf's Scope): 8 rounds run in this loop, and deferring
                                        // conn/tlsConn cleanup to the leaf's own Scope would hold every round's connection open
                                        // simultaneously until the whole leaf ends, matching the startTlsClient idiom in
                                        // TransportStartTlsTest.scala.
                                        Scope.run(
                                            for
                                                conn    <- transport.connect("127.0.0.1", port).safe.get
                                                _       <- Scope.ensure(Sync.defer(conn.close()))
                                                tlsConn <- transport.upgradeToTls(conn, clientTls, 16).safe.get
                                                _       <- Scope.ensure(Sync.defer(tlsConn.close()))
                                                payload = "ping\n".getBytes
                                                _      <- tlsConn.outbound.safe.put(Span.fromUnsafe(payload))
                                                echoed <- tlsConn.inbound.safe.take
                                                _ = assert(
                                                    echoed.size > 0,
                                                    s"iteration $i: expected echo from openssl s_server -rev, got empty"
                                                )
                                            yield ()
                                        )
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

    "s_server is started on another port when the one chosen is taken" in {
        if !probeOpenssl() then cancel("openssl not available on this host")
        TlsTestCertShared.writePems.flatMap { case (certPath, keyPath) =>
            // Holds a port from a probe socket, as another process that took it between the choice and s_server's bind does.
            NonEphemeralPort.bind[ServerSocket, java.io.IOException, Any](_ => true) { port =>
                Abort.catching[java.io.IOException](new ServerSocket(port, 50, InetAddress.getLoopbackAddress()))
            }.map { held =>
                // s_server's first launch gets the held port; every later one gets the port the helper chose.
                val first = new AtomicBoolean(true)
                Sync.ensure(Sync.defer(held.close())) {
                    serve(certPath, keyPath, port => if first.getAndSet(false) then held.getLocalPort else port).map {
                        case Result.Success(server) =>
                            Sync.ensure(Sync.defer(server.proc.destroy())) {
                                assert(server.port != held.getLocalPort && server.proc.isAlive, outputString(server.output))
                            }
                        case other => fail(s"s_server did not start on another port: $other")
                    }
                }
            }
        }
    }

end StartTlsInteropClientTest
