package kyo

import kyo.Maybe.Absent

class JsonRpcTransportTest extends JsonRpcTest:

    val ping1 = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Absent)
    val ping2 = JsonRpcRequest(JsonRpcId.Num(2L), "ping", Absent, Absent)
    val ping3 = JsonRpcRequest(JsonRpcId.Num(3L), "ping", Absent, Absent)

    // SignalingTransport completes `entered` the instant a matching envelope reaches send (before the
    // underlying channel.put parks). It gives the test a deterministic latch to await the parked-put state
    // instead of sleeping and hoping the forked send fiber has reached its put.
    private class SignalingTransport(
        inner: JsonRpcTransport,
        entered: Fiber.Promise[Unit, Any],
        entersOn: JsonRpcEnvelope => Boolean
    ) extends JsonRpcTransport:
        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
            val signal: Unit < Sync = if entersOn(env) then entered.completeUnitDiscard else Kyo.unit
            signal.andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end SignalingTransport

    "send reports an envelope it could not encode" - {

        // Both cases reach the wire adapter's Structure.encode and are unrepresentable there, so the
        // frame never leaves. send must say so: reporting success would tell the caller a message was
        // transmitted that no peer will ever see.

        "a Malformed message, which only decoding a peer's garbage ever produces" in {
            JsonRpcTransport.fromWire(JsonRpcWireTransport.empty, JsonRpcFramer.lineDelimited).map { transport =>
                val unsendable = JsonRpcMalformedMessage(Absent, "inbound only", Structure.Value.Str("x"))
                Abort.run[JsonRpcError](transport.send(unsendable)).map {
                    case Result.Failure(e: JsonRpcInternalError) =>
                        assert(e.message.contains("Malformed"), s"message was: ${e.message}")
                    case other => fail(s"expected a JsonRpcInternalError, got $other")
                }
            }
        }

        "extras carrying a key the envelope reserves" in {
            JsonRpcTransport.fromWire(
                JsonRpcWireTransport.empty,
                JsonRpcFramer.lineDelimited,
                JsonRpcEnvelope.lenientSchema
            ).map { transport =>
                val extras = Structure.Value.Record(Chunk("id" -> Structure.Value.Str("collides")))
                val req    = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Present(extras))
                Abort.run[JsonRpcError](transport.send(req)).map {
                    case Result.Failure(e: JsonRpcInvalidRequestError) =>
                        // The offending key is carried as the error's data, which is what a peer would
                        // be told; `message` is the protocol's fixed "Invalid Request" text.
                        assert(
                            e.data.exists {
                                case Structure.Value.Str(s) => s.contains("'id' is reserved")
                                case _                      => false
                            },
                            s"data was: ${e.data}"
                        )
                    case other => fail(s"expected a JsonRpcInvalidRequestError, got $other")
                }
            }
        }
    }

    "a send on transport A is received via incoming on transport B" in {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(b.incoming.take(1).run)
            _      <- a.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on transport B is received via incoming on transport A" in {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(a.incoming.take(1).run)
            _      <- b.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on a closed transport fails with Abort[Closed]" in {
        for
            (a, _) <- JsonRpcTransport.inMemory
            _      <- a.close
            result <- Abort.run[Closed](a.send(ping2))
        yield assert(result.isFailure)
    }

    "the incoming stream on B terminates when A closes" in {
        for
            (a, b)    <- JsonRpcTransport.inMemory
            collector <- Fiber.initUnscoped(b.incoming.run)
            _         <- a.close
            result    <- collector.get
        yield assert(result.isEmpty)
    }

    "a send parks when the consumer of incoming is slow" in {
        // The capacity-2 channel is full after ping1+ping2 with no consumer, so the forked ping3 send parks
        // in channel.put. `entered` fires when ping3 reaches send; once it does the put cannot complete (full,
        // no consumer), so putFiber.done is reliably false. This replaces a fixed sleep with a state latch.
        for
            (inner, b) <- JsonRpcTransport.inMemory(2)
            entered    <- Fiber.Promise.init[Unit, Any]
            a = new SignalingTransport(inner, entered, _ == ping3)
            _         <- a.send(ping1)
            _         <- a.send(ping2)
            putFiber  <- Fiber.initUnscoped(a.send(ping3))
            _         <- entered.get
            notDone   <- putFiber.done
            collector <- Fiber.initUnscoped(b.incoming.take(3).run)
            _         <- assertEventually(putFiber.done)
            result    <- collector.get
        yield assert(!notDone && result == Chunk(ping1, ping2, ping3))
    }

    "a parked send unblocks with Abort[Closed] when the transport closes" in {
        // Same parked-put latch: `entered` fires when ping3 reaches send; with the channel full and no
        // consumer the put stays parked (done == false) until close unblocks it with Abort[Closed].
        for
            (inner, _) <- JsonRpcTransport.inMemory(2)
            entered    <- Fiber.Promise.init[Unit, Any]
            a = new SignalingTransport(inner, entered, _ == ping3)
            _       <- a.send(ping1)
            _       <- a.send(ping2)
            parked  <- Fiber.initUnscoped(Abort.run[Closed](a.send(ping3)))
            _       <- entered.get
            notDone <- parked.done
            _       <- a.close
            result  <- parked.get
        yield assert(!notDone && result.isFailure)
    }

    "stdio transport sends one line per envelope" in {
        Scope.run {
            Console.withOut {
                for
                    transport <- JsonRpcTransport.stdio()
                    notification = JsonRpcNotification(
                        "log",
                        Maybe.Present(Structure.Value.Record(Chunk("text" -> Structure.Value.Str("hi")))),
                        Absent
                    )
                    _ <- Abort.run[Closed](transport.send(notification))
                yield ()
            }.map { case (out, _) =>
                val line = out.stdOut.trim
                assert(line.nonEmpty)
                // The transport now encodes via Json.encode[Structure.Value]; decode with the same codec
                // to confirm the emitted line is standard, round-trippable JSON-RPC.
                val parsed = Json.decode[Structure.Value](line)
                assert(parsed.isSuccess)
            }
        }
    }

    "stdio transport reads one envelope per stdin line" in {
        val inputLine = """{"jsonrpc":"2.0","method":"ping"}"""
        Console.withIn(List(inputLine)) {
            Scope.run {
                for
                    transport <- JsonRpcTransport.stdio()
                    result    <- Abort.run[Closed](transport.incoming.take(1).run)
                yield result
            }
        }.map { result =>
            result match
                case Result.Success(chunk) =>
                    assert(chunk.size == 1)
                    chunk.head match
                        case JsonRpcNotification(method, _, _) =>
                            assert(method == "ping")
                        case other =>
                            fail(s"unexpected envelope: $other")
                    end match
                case other =>
                    fail(s"unexpected result: $other")
        }
    }

    "stdio transport EOF closes incoming" in {
        Console.withIn(Seq.empty[String]) {
            Abort.run[Timeout](Async.timeout(2.seconds) {
                Scope.run {
                    for
                        transport <- JsonRpcTransport.stdio()
                        result    <- Abort.run[Closed](transport.incoming.run)
                    yield result
                }
            })
        }.map { outerResult =>
            outerResult match
                case Result.Failure(_)           => fail("timed out - incoming did not close on EOF")
                case Result.Panic(t)             => fail(s"panic: ${t.getMessage}")
                case Result.Success(innerResult) =>
                    innerResult match
                        case Result.Success(chunk: Chunk[?]) => assert(chunk.isEmpty)
                        case other                           => assert(true, s"acceptable result: $other")
        }
    }

    "stdioWith keeps application output off the protocol channel" - {

        "Console.printLine from inside the body goes to stderr, not stdout" in {
            // stdout IS the protocol channel here, so one application line between two envelopes makes
            // the peer see a parse error. `Console.printLine` is the idiomatic way to print in kyo, so
            // the transport that owns the channel is what has to make it safe.
            Console.withOut {
                Scope.run {
                    JsonRpcTransport.stdioWith() { _ =>
                        Console.printLine("this line must not reach the protocol channel")
                    }
                }
            }.map { (out, _) =>
                assert(out.stdOut.isEmpty, s"nothing may reach stdout; got: ${out.stdOut}")
                assert(
                    out.stdErr.contains("this line must not reach the protocol channel"),
                    s"the line must still be printed, on stderr; got: ${out.stdErr}"
                )
            }
        }

        "Console.print from inside the body goes to stderr too" in {
            Console.withOut {
                Scope.run {
                    JsonRpcTransport.stdioWith()(_ => Console.print("partial"))
                }
            }.map { (out, _) =>
                assert(out.stdOut.isEmpty, s"nothing may reach stdout; got: ${out.stdOut}")
                assert(out.stdErr.contains("partial"), s"got: ${out.stdErr}")
            }
        }

        "Console.printErr still reaches stderr" in {
            Console.withOut {
                Scope.run {
                    JsonRpcTransport.stdioWith()(_ => Console.printLineErr("diagnostic"))
                }
            }.map { (out, _) =>
                assert(out.stdErr.contains("diagnostic"), s"got: ${out.stdErr}")
            }
        }

        "reading stdin fails rather than consuming protocol bytes" in {
            // The other half of the same channel. A handler that reads a line would steal bytes the
            // dispatch loop is waiting for, which corrupts the stream in the harder-to-see direction.
            Scope.run {
                Abort.run[java.io.IOException] {
                    JsonRpcTransport.stdioWith()(_ => Console.readLine)
                }
            }.map { result =>
                assert(result.isFailure, s"reading stdin must fail on this transport; got: $result")
            }
        }

        "the binding is scoped to the body and does not leak out" in {
            Scope.run(JsonRpcTransport.stdioWith()(_ => ())).andThen {
                Console.withOut(Console.printLine("after")).map { (out, _) =>
                    assert(out.stdOut.contains("after"), s"stdout must work again outside the body; got: $out")
                }
            }
        }

        // The two leaves the rest of this block does not cover: the transport it hands out has to keep working
        // inside it. The diversion is the whole of what `stdioWith` does, and this transport reaches the channel
        // through the same `Console` it diverts, so the body it protects is also the body it runs in.
        //
        // What that costs, when it costs anything: the transport reads through `Console.readLine`, which the
        // diverted console fails by design, so `incoming` ends on its first read and the server answers nothing at
        // all. A stdio server written the way this method's own documentation recommends is silent.

        "the transport it hands out still reads the protocol channel" in {
            val request = """{"jsonrpc":"2.0","method":"ping"}"""
            Console.withIn(List(request)) {
                Scope.run {
                    JsonRpcTransport.stdioWith() { transport =>
                        Abort.run[Closed](transport.incoming.take(1).run)
                    }
                }
            }.map {
                case Result.Success(chunk) if chunk.size == 1 =>
                    chunk.head match
                        case JsonRpcNotification(method, _, _) => assert(method == "ping")
                        case other                             => fail(s"unexpected envelope: $other")
                case other => fail(s"the transport read nothing from the channel: $other")
            }
        }

        "the transport it hands out still writes the protocol channel" in {
            Console.withOut {
                Scope.run {
                    JsonRpcTransport.stdioWith() { transport =>
                        Abort.run[Closed](transport.send(JsonRpcNotification("ping", Absent, Absent)))
                    }
                }
            }.map { (out, _) =>
                assert(out.stdOut.contains("ping"), s"the envelope must reach stdout; got stdout=${out.stdOut} stderr=${out.stdErr}")
                assert(!out.stdErr.contains("ping"), s"the envelope must not be diverted; got stderr=${out.stdErr}")
            }
        }
    }

end JsonRpcTransportTest
