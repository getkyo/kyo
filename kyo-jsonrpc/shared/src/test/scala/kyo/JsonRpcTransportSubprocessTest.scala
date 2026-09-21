package kyo

import java.nio.charset.StandardCharsets

/** Drives [[JsonRpcTransport.subprocess]] against real child processes: a POSIX `sh` script that speaks line-delimited JSON-RPC using only
  * parameter expansion, so it behaves the same under every `sh` the platforms ship.
  */
class JsonRpcTransportSubprocessTest extends JsonRpcTest:

    case class Text(text: String) derives Schema, CanEqual

    // The peer and the lifecycle children are sh scripts; each leaf cancels (not fails) on Windows.
    private def unixOnly(using Frame): Unit =
        assume(!kyo.internal.Platform.isWindows, "the subprocess peer is a POSIX sh script")

    // Answers one request per line on stdin, keyed by method, and exits 0 when stdin reaches EOF:
    //   echo   -> result {"text": <params.text>}
    //   notify -> notification "hello" {"text": <params.text>}, then result {"text": "notified"}
    //   stderr -> <params.text> and a newline on stderr, then result {"text": "logged"}
    //   flood  -> 30000 lines of 100 bytes on stderr (3 MB, far beyond a pipe buffer), then result {"text": "flooded"}
    //   exit   -> exits with status 3 without answering
    private val peerScript =
        """while IFS= read -r line; do
          |  method=${line#*\"method\":\"}
          |  method=${method%%\"*}
          |  id=${line#*\"id\":}
          |  id=${id%%,*}
          |  id=${id%%\}*}
          |  text=${line#*\"text\":\"}
          |  text=${text%%\"*}
          |  case "$method" in
          |    echo)
          |      printf '{"jsonrpc":"2.0","id":%s,"result":{"text":"%s"}}\n' "$id" "$text" ;;
          |    notify)
          |      printf '{"jsonrpc":"2.0","method":"hello","params":{"text":"%s"}}\n' "$text"
          |      printf '{"jsonrpc":"2.0","id":%s,"result":{"text":"notified"}}\n' "$id" ;;
          |    stderr)
          |      printf '%s\n' "$text" >&2
          |      printf '{"jsonrpc":"2.0","id":%s,"result":{"text":"logged"}}\n' "$id" ;;
          |    flood)
          |      i=1
          |      while [ "$i" -le 30000 ]; do printf 'line %05d %s\n' "$i" "$text" >&2; i=$((i+1)); done
          |      printf '{"jsonrpc":"2.0","id":%s,"result":{"text":"flooded"}}\n' "$id" ;;
          |    exit)
          |      exit 3 ;;
          |  esac
          |done
          |""".stripMargin

    private val peer = Command("sh", "-c", peerScript)

    // Ignores stdin EOF (it never reads stdin) but honours SIGTERM.
    private val ignoresEof = Command("sh", "-c", "while :; do sleep 1; done")

    // Ignores stdin EOF and SIGTERM, so only a forced kill ends it.
    private val ignoresTerm = Command("sh", "-c", "trap '' TERM; while :; do sleep 1; done")

    private def request(id: Long, method: String, text: String): JsonRpcRequest =
        JsonRpcRequest(JsonRpcId.Num(id), method, Present(Structure.encode(Text(text))), Absent)

    private def utf8(bytes: Chunk[Byte]): String = new String(bytes.toArray, StandardCharsets.UTF_8)

    "calls" - {

        "a call round-trips through the child's stdin and stdout" in {
            unixOnly
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer)
                    handler   <- JsonRpcHandler.init(transport, Seq.empty)
                    first     <- handler.call[Text, Text]("echo", Text("hello"))
                    second    <- handler.call[Text, Text]("echo", Text("again"))
                yield assert(first == Text("hello") && second == Text("again"))
            }
        }

        "a notification the child writes reaches its route" in {
            unixOnly
            Scope.run {
                for
                    arrived <- Latch.init(1)
                    seen    <- AtomicRef.init(Chunk.empty[Text])
                    hello =
                        JsonRpcRoute.notification[Text]("hello")((note, _) => seen.updateAndGet(_.append(note)).andThen(arrived.release))
                    transport <- JsonRpcTransport.subprocess(peer)
                    handler   <- JsonRpcHandler.init(transport, Seq(hello))
                    reply     <- handler.call[Text, Text]("notify", Text("ping"))
                    _         <- arrived.await
                    notes     <- seen.get
                yield
                    assert(reply == Text("notified"))
                    assert(notes == Chunk(Text("ping")))
            }
        }

        "the protocol's stdio settings override the command's own" in {
            unixOnly
            // A command that asks for stderr merged into stdout and for stdout inherited would corrupt the protocol channel. The line the
            // child writes to stderr must arrive on the stderr stream, and the reply on the protocol channel.
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer.redirectErrorStream(true).inheritStdout)
                    handler   <- JsonRpcHandler.init(transport, Seq.empty)
                    reply     <- handler.call[Text, Text]("stderr", Text("kept apart"))
                    err       <- transport.stderr.take("kept apart\n".length).run
                yield
                    assert(reply == Text("logged"))
                    assert(utf8(err) == "kept apart\n")
            }
        }
    }

    "stderr" - {

        "what the child writes to stderr is readable as a stream" in {
            unixOnly
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer)
                    handler   <- JsonRpcHandler.init(transport, Seq.empty)
                    reply     <- handler.call[Text, Text]("stderr", Text("diagnostic line"))
                    err       <- transport.stderr.take("diagnostic line\n".length).run
                yield
                    assert(reply == Text("logged"))
                    assert(utf8(err) == "diagnostic line\n")
            }
        }

        "a child flooding an unread stderr is never blocked, and the stream keeps the most recent output" in {
            unixOnly
            val pad = "x" * 88
            for
                transport <- JsonRpcTransport.subprocessUnscoped(peer)
                handler   <- JsonRpcHandler.initUnscoped(transport, Seq.empty)
                reply     <- handler.call[Text, Text]("flood", Text(pad))
                _         <- handler.close
                code      <- transport.process.waitFor
                err       <- transport.stderr.run
            yield
                val text = utf8(err)
                assert(reply == Text("flooded"))
                assert(code == Process.ExitCode.Success)
                assert(text.endsWith(s"line 30000 $pad\n"), s"the most recent stderr output was not kept: ...${text.takeRight(200)}")
                assert(
                    err.size <= internal.transport.SubprocessTransport.stderrBufferChunks * 8192,
                    s"stderr buffer grew to ${err.size} bytes"
                )
                assert(err.size < 30000 * 100, "the oldest stderr output was not dropped")
            end for
        }
    }

    "child exit" - {

        "an unexpected exit fails the pending call with Closed" in {
            unixOnly
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer)
                    handler   <- JsonRpcHandler.init(transport, Seq.empty)
                    result    <- Abort.run[JsonRpcError | Closed](handler.call[Text, Text]("exit", Text("bye")))
                    code      <- transport.process.waitFor
                yield
                    assert(code == Process.ExitCode.Failure(3))
                    result match
                        case Result.Failure(_: Closed) => succeed
                        case other                     => fail(s"expected the pending call to fail with Closed, got $other")
                    end match
            }
        }

        "incoming ends when the child exits" in {
            unixOnly
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer)
                    _         <- transport.send(request(1, "exit", "bye"))
                    received  <- transport.incoming.run
                    code      <- transport.process.waitFor
                yield
                    assert(received.isEmpty)
                    assert(code == Process.ExitCode.Failure(3))
            }
        }

        "a send after the child exited fails with Closed" in {
            unixOnly
            Scope.run {
                for
                    transport <- JsonRpcTransport.subprocess(peer)
                    _         <- transport.send(request(1, "exit", "bye"))
                    _         <- transport.process.waitFor
                    result    <- Abort.run[Closed](transport.send(request(2, "echo", "too late")))
                yield result match
                    case Result.Failure(_: Closed) => succeed
                    case other                     => fail(s"expected Closed, got $other")
            }
        }
    }

    "close" - {

        "a child that exits when stdin closes is not signalled" in {
            unixOnly
            for
                transport <- JsonRpcTransport.subprocessUnscoped(peer)
                reply     <- transport.send(request(1, "echo", "x")).andThen(transport.incoming.take(1).run)
                _         <- transport.close
                code      <- transport.process.waitFor
            yield
                assert(reply.size == 1)
                assert(code == Process.ExitCode.Success)
            end for
        }

        "a child that ignores stdin EOF is terminated after the grace period" in {
            unixOnly
            Clock.withTimeControl { control =>
                for
                    transport <- JsonRpcTransport.subprocessUnscoped(ignoresEof, closeGracePeriod = 5.seconds)
                    closing   <- Fiber.initUnscoped(transport.close)
                    _         <- control.awaitPendingSleepers(1)
                    _         <- control.advance(5.seconds)
                    _         <- closing.get
                    code      <- transport.process.waitFor
                yield assert(code == Process.ExitCode.SIGTERM)
            }
        }

        "a child that ignores stdin EOF and SIGTERM is killed after the second grace period" in {
            unixOnly
            Clock.withTimeControl { control =>
                for
                    transport      <- JsonRpcTransport.subprocessUnscoped(ignoresTerm, closeGracePeriod = 5.seconds)
                    closing        <- Fiber.initUnscoped(transport.close)
                    _              <- control.awaitPendingSleepers(1)
                    _              <- control.advance(5.seconds)
                    _              <- control.awaitPendingSleepers(1)
                    aliveAfterTerm <- transport.process.isAlive
                    _              <- control.advance(5.seconds)
                    _              <- closing.get
                    code           <- transport.process.waitFor
                yield
                    assert(aliveAfterTerm, "the child exited on SIGTERM, so the test did not exercise the forced kill")
                    assert(code == Process.ExitCode.SIGKILL)
            }
        }

        "an interrupted close kills the child" in {
            unixOnly
            Clock.withTimeControl { control =>
                for
                    transport <- JsonRpcTransport.subprocessUnscoped(ignoresTerm, closeGracePeriod = 5.seconds)
                    closing   <- Fiber.initUnscoped(transport.close)
                    _         <- control.awaitPendingSleepers(1)
                    _         <- closing.interrupt
                    code      <- transport.process.waitFor
                yield assert(code == Process.ExitCode.SIGKILL)
            }
        }

        "concurrent and repeated closes all complete once the child is gone" in {
            unixOnly
            for
                transport <- JsonRpcTransport.subprocessUnscoped(peer)
                first     <- Fiber.initUnscoped(transport.close)
                second    <- Fiber.initUnscoped(transport.close)
                _         <- first.get
                _         <- second.get
                _         <- transport.close
                alive     <- transport.process.isAlive
                code      <- transport.process.waitFor
            yield
                assert(!alive)
                assert(code == Process.ExitCode.Success)
            end for
        }

        "the scoped transport closes the child when its scope ends" in {
            unixOnly
            for
                process <- Scope.run(JsonRpcTransport.subprocess(peer).map(_.process))
                alive   <- process.isAlive
                code    <- process.waitFor
            yield
                assert(!alive, "the child was still running after its scope ended")
                assert(code == Process.ExitCode.Success)
            end for
        }
    }

end JsonRpcTransportSubprocessTest
