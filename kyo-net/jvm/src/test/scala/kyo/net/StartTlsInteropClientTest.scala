package kyo.net

import java.net.InetAddress
import java.net.ServerSocket
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
  * the server's output and included in failure messages for diagnostics.
  *
  * Gate: PosixTestSockets.assumeUring() and TlsRealEngines.assumeTlsReady() cancel off Linux / missing TLS provider.
  * opensslAvailable cancels when the openssl binary is absent, and an s_server that exits without printing ACCEPT cancels
  * with its output.
  *
  * This file lives in jvm/src/test (JVM-only) because it spawns an external OS process; jvm-native/src/test would
  * compile for Native where openssl subprocess is not the right test strategy.
  */
class StartTlsInteropClientTest extends Test:

    import AllowUnsafe.embrace.danger

    private def opensslAvailable(using Frame): Boolean < Async =
        Abort.run[CommandException](Command("openssl", "version").waitFor).map {
            case Result.Success(code) => code.isSuccess
            case _                    => false
        }

    private def opensslServer(certPath: String, keyPath: String)(port: Int): Command =
        Command("openssl", "s_server", "-accept", s"127.0.0.1:$port", "-cert", certPath, "-key", keyPath, "-rev")

    /** A stand-in for s_server that prints ACCEPT once the test writes a line to its stdin, and stays up until its stdin closes. */
    private val standInSource: String =
        """public class StandIn {
          |    public static void main(String[] args) throws Exception {
          |        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
          |        in.readLine();
          |        System.out.println("ACCEPT");
          |        System.out.flush();
          |        while (in.readLine() != null) {}
          |    }
          |}
          |""".stripMargin

    /** The stand-in as a single-file program on the test JVM's own `java`, so it needs no shell on any platform. */
    private def standIn(using Frame, kyo.test.AssertScope): Command < (Sync & Scope & Abort[FileSystemException]) =
        for
            javaHome <- System.property[String]("java.home")
            dir      <- Path.run(Path.tempDir("kyo-starttls-standin"))
            source = dir / "StandIn.java"
            _ <- Path.run(source.write(standInSource))
            javaExe = Path(javaHome.getOrElse(fail("java.home is not set"))) / "bin" / "java"
        yield Command(javaExe.unsafe.show, source.unsafe.show)

    /** Writes the line that makes the stand-in print ACCEPT. A stand-in that the launch already gave up on has nothing to release. */
    private def release(proc: Process)(using Frame): Unit < Sync =
        // Unsafe: `pipeStdin` leaves the child's stdin to the caller, as its raw stream.
        Abort.run[java.io.IOException](Abort.catching[java.io.IOException] {
            val in = proc.unsafe.stdinJava
            in.write('\n')
            in.flush()
        }).unit

    /** An s_server that printed ACCEPT, the port it accepts on, and its output so far. */
    final private case class Server(proc: Process, port: Int, output: AtomicRef[Chunk[String]])

    /** Why one s_server launch did not reach ACCEPT. Only `PortTaken` is worth another port. */
    private enum ServeFailure derives CanEqual:
        case PortTaken(port: Int, output: String)
        case NotStarted(message: String)

    /** Starts `openssl s_server -rev` on 127.0.0.1 at a port outside the ephemeral range, choosing another while the one chosen is taken,
      * or answers why it could not. `choose` maps each port [[NonEphemeralPort]] picks to the one launched, so a leaf can hand it a port it
      * holds. `waiting` runs once a launch is waiting on its server, the point after which a leaf can move time. Every launch belongs to the
      * enclosing `Scope`.
      *
      * Served on the loopback address the client connects to: with SO_REUSEADDR a wildcard bind can share a port another process
      * listens on at 127.0.0.1, and the client's connect then reaches that listener. No -starttls (removed in newer container images),
      * no -no_dhe.
      */
    private def serve(
        command: Int => Command,
        choose: Int => Int < Sync = port => port,
        waiting: Process => Unit < Sync = _ => ()
    )(using Frame): Result[String, Server] < (Async & Scope) =
        AtomicInt.init.map { attempts =>
            Abort.run[ServeFailure](
                NonEphemeralPort.bind[Server, ServeFailure, Scope] {
                    case _: ServeFailure.PortTaken => true
                    case _                         => false
                } { port =>
                    attempts.incrementAndGet.andThen(choose(port)).map(chosen => startOnce(command(chosen), chosen, waiting))
                }
            ).map { result =>
                attempts.get.map { count =>
                    result match
                        case Result.Success(server)                               => Result.succeed(server)
                        case Result.Failure(ServeFailure.PortTaken(port, output)) =>
                            Result.fail(s"openssl s_server found port $port taken after $count attempts. Output:\n$output")
                        case Result.Failure(ServeFailure.NotStarted(message)) =>
                            Result.fail(s"$message after $count attempts")
                        case Result.Panic(e) => Result.panic(e)
                }
            }
        }
    end serve

    /** One s_server launch on `port`: served once it prints ACCEPT, failed once its output has ended and it has exited. Neither outcome has a
      * deadline, because a first launch on a fresh Windows runner reads openssl's binaries from a cold disk, and a cold `openssl version`
      * alone has measured 9.6 s there. The leaf's own timeout bounds a launch that never ends.
      *
      * The bind failure is read from the output because OpenSSL 3 exits with status 0 after `BIO_bind`. It is matched on OpenSSL's own
      * reason, "unable to bind socket", because the system error before it is platform text: "Address already in use" on Linux and macOS,
      * "Unknown error" for WSAEADDRINUSE on Windows.
      */
    private def startOnce(command: Command, port: Int, waiting: Process => Unit < Sync)(using
        Frame
    ): Server < (Async & Scope & Abort[ServeFailure]) =
        Abort.run[CommandException](command.redirectErrorStream(true).pipeStdin.spawn).map {
            case Result.Success(proc) =>
                for
                    output   <- AtomicRef.init(Chunk.empty[String])
                    accepted <- Promise.init[Boolean, Any]
                    _        <- Fiber.init(readOutput(proc, output, accepted))
                    // Runs before the reader fiber's release, which waits for the reader: ending the process ends the output it reads.
                    _         <- Scope.ensure(proc.destroy)
                    _         <- waiting(proc)
                    didAccept <- accepted.get
                    lines     <- output.get
                    exit      <- proc.exitCode
                    server    <-
                        val text = lines.mkString("\n")
                        if didAccept then Server(proc, port, output): Server < Abort[ServeFailure]
                        else if text.contains("unable to bind socket") then Abort.fail(ServeFailure.PortTaken(port, text))
                        else
                            Abort.fail(ServeFailure.NotStarted(
                                s"openssl s_server exited with ${exit.map(_.toInt).getOrElse(-1)} before printing ACCEPT on port $port. Output:\n$text"
                            ))
                        end if
                yield server
            case Result.Failure(e) => Abort.fail(ServeFailure.NotStarted(s"openssl s_server could not be launched on port $port: $e"))
            case Result.Panic(e)   => Abort.panic(e)
        }
    end startOnce

    /** Reads `proc`'s output into `output` line by line and answers `accepted`: `true` on an ACCEPT line, `false` once the output has ended
      * and the process has exited. The first answer wins, and an ACCEPT line is read before the end of the output.
      */
    private def readOutput(proc: Process, output: AtomicRef[Chunk[String]], accepted: Promise[Boolean, Any])(using Frame): Unit < Async =
        def line(bytes: Chunk[Byte]): Unit < Sync =
            val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).stripSuffix("\r")
            output.updateAndGet(_.append(text)).andThen {
                if text.contains("ACCEPT") then accepted.completeDiscard(Result.succeed(true)) else Kyo.unit
            }
        end line
        Scope.run {
            proc.stdout.fold(Chunk.empty[Byte]) { (pending, byte) =>
                val next: Chunk[Byte] < Sync =
                    if byte == '\n'.toByte then line(pending).andThen(Chunk.empty[Byte]) else pending.append(byte)
                next
            }
        }.map(rest => if rest.isEmpty then Kyo.unit else line(rest))
            .andThen(proc.waitFor)
            .andThen(accepted.completeDiscard(Result.succeed(false)))
    end readOutput

    "external TLS interop: kyo-net io_uring client vs openssl s_server, echo round-trip across 8 connections" in {
        PosixTestSockets.assumeUring()
        TlsRealEngines.assumeTlsReady()
        opensslAvailable.map { available =>
            if !available then cancel("openssl not available on this host")

            val maybeUring = TestBackends.all.find(_.name == "io_uring")
            if maybeUring.isEmpty then cancel("io_uring backend not registered on this host")
            val uringEntry = maybeUring.get
            if !uringEntry.isAvailable then cancel("io_uring not available on this host")

            Scope.run {
                TlsTestCertShared.writePems.flatMap { case (certPath, keyPath) =>
                    Sync.defer(uringEntry.transport).flatMap { transport =>
                        // -rev: s_server reverses each input line and echoes it back.
                        // A server that would not start is the host's openssl failing, not kyo-net, so the leaf cancels with its output.
                        serve(opensslServer(certPath, keyPath)).map {
                            case Result.Success(server) => server
                            case Result.Failure(why)    => cancel(why)
                            case Result.Panic(e)        => throw e
                        }.flatMap { server =>
                            val port      = server.port
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
                                            server.output.get.map { lines =>
                                                fail(
                                                    s"iteration $i: TLS handshake or echo failed: $e. " +
                                                        s"openssl s_server output:\n${lines.mkString("\n")}"
                                                )
                                            }.andThen(Loop.continue(i + 1))
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

    "s_server is started on another port when the one chosen is taken" in {
        opensslAvailable.map { available =>
            if !available then cancel("openssl not available on this host")
            Scope.run {
                TlsTestCertShared.writePems.flatMap { case (certPath, keyPath) =>
                    // Holds a port from a probe socket, as another process that took it between the choice and s_server's bind does. A plain
                    // ServerSocket rather than a kyo-net listener: those set SO_REUSEADDR, which on Windows lets s_server's own
                    // SO_REUSEADDR bind share the port instead of failing.
                    NonEphemeralPort.bind[ServerSocket, java.io.IOException, Any](_ => true) { port =>
                        Abort.catching[java.io.IOException](new ServerSocket(port, 50, InetAddress.getLoopbackAddress()))
                    }.map { held =>
                        // s_server's first launch gets the held port; every later one gets the port the helper chose.
                        Scope.ensure(Sync.defer(held.close())).andThen(AtomicBoolean.init(true)).map { first =>
                            val choose = (port: Int) => first.getAndSet(false).map(isFirst => if isFirst then held.getLocalPort else port)
                            serve(opensslServer(certPath, keyPath), choose).map {
                                case Result.Success(server) =>
                                    for
                                        alive <- server.proc.isAlive
                                        lines <- server.output.get
                                    yield assert(server.port != held.getLocalPort && alive, lines.mkString("\n"))
                                case other => fail(s"s_server did not start on another port: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "a server that prints ACCEPT only after 15 s of waiting is still served" in {
        Scope.run {
            standIn.map { command =>
                Clock.withTimeControl { control =>
                    Promise.initWith[Process, Any] { spawned =>
                        Fiber.init(spawned.get.map(proc => control.advance(16.seconds).andThen(release(proc)))).andThen {
                            serve(_ => command, waiting = proc => spawned.completeDiscard(Result.succeed(proc))).map {
                                case Result.Success(server) =>
                                    for
                                        alive <- server.proc.isAlive
                                        lines <- server.output.get
                                    yield assert(alive && lines.exists(_.contains("ACCEPT")), lines.mkString("\n"))
                                case other => fail(s"a server that printed ACCEPT after 15 s was not served: $other")
                            }
                        }
                    }
                }
            }
        }
    }

end StartTlsInteropClientTest
