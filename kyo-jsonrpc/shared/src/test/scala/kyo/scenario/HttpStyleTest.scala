package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class HttpStyleTest extends JsonRpcTest:

    case class AddReq(a: Int, b: Int) derives Schema, CanEqual
    case class AddResp(sum: Int) derives Schema, CanEqual
    case class GreetReq(name: String) derives Schema, CanEqual
    case class GreetResp(greeting: String) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual
    case class InitReq(clientName: String) derives Schema, CanEqual
    case class InitResp(serverName: String) derives Schema, CanEqual
    case class HoverReq(line: Int) derives Schema, CanEqual
    case class HoverResp(text: String) derives Schema, CanEqual

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end CapturingTransport

    "single server endpoint with add and greet: two sequential calls return correct typed results" in run {
        val addMethod = JsonRpcRoute[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        val greetMethod = JsonRpcRoute[GreetReq, GreetResp, Async & Abort[JsonRpcError]]("greet") {
            (req, _) => GreetResp(s"Hello, ${req.name}!")
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { client =>
                JsonRpcHandler.init(tb, Seq(addMethod, greetMethod)).map { _ =>
                    client.call[AddReq, AddResp]("add", AddReq(3, 7)).map { addResult =>
                        assert(addResult == AddResp(10))
                        client.call[GreetReq, GreetResp]("greet", GreetReq("World")).map { greetResult =>
                            assert(greetResult == GreetResp("Hello, World!"))
                        }
                    }
                }
            }
        }
    }

    "notification triggers handler and no reply frame arrives on wire" in run {
        // Unsafe: AtomicInt.Unsafe.init for handler run counter
        val handlerRan = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val logMethod = JsonRpcRoute[LogMsg, Unit, Async & Abort[JsonRpcError]]("log") {
            (_, _) => Sync.defer(discard(handlerRan.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty).map { client =>
                JsonRpcHandler.init(tb, Seq(logMethod)).map { _ =>
                    val framesBefore = Sync.defer(capA.sentList.size)
                    framesBefore.map { before =>
                        client.notify[LogMsg]("log", LogMsg("event occurred")).andThen {
                            untilTrue(Sync.defer(handlerRan.get()(using AllowUnsafe.embrace.danger) == 1)).andThen {
                                Sync.defer {
                                    val responses = capA.sentList.collect {
                                        case r: JsonRpcResponse => r
                                    }
                                    assert(responses.isEmpty, s"expected zero response frames from server, got ${responses.size}")
                                    assert(handlerRan.get()(using AllowUnsafe.embrace.danger) == 1, "handler should have run exactly once")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "LSP pre-init gate: requests before initialize return -32002; after initialize, methods succeed" in run {
        val serverNotInitialized = JsonRpcImplementationError(-32002, "Server not initialized")
        var initialized          = false

        val lspInitGate: JsonRpcHandler.MessageGate = new JsonRpcHandler.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcHandler.MessageGate.Decision < Sync =
                env match
                    case JsonRpcRequest(_, "initialize", _, _) =>
                        JsonRpcHandler.MessageGate.Decision.Allow
                    case JsonRpcRequest(_, _, _, _) if !initialized =>
                        JsonRpcHandler.MessageGate.Decision.Reject(serverNotInitialized)
                    case _ =>
                        JsonRpcHandler.MessageGate.Decision.Allow

        val initMethod = JsonRpcRoute[InitReq, InitResp, Async & Abort[JsonRpcError]]("initialize") {
            (req, _) =>
                Sync.defer { initialized = true }.andThen(InitResp("test-server"))
        }
        val hoverMethod = JsonRpcRoute[HoverReq, HoverResp, Async & Abort[JsonRpcError]]("textDocument/hover") {
            (req, _) => HoverResp(s"hover at line ${req.line}")
        }

        val gatedConfig = JsonRpcHandler.Config(gate = Present(lspInitGate))
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { client =>
                JsonRpcHandler.init(tb, Seq(initMethod, hoverMethod), gatedConfig).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        client.call[HoverReq, HoverResp]("textDocument/hover", HoverReq(10))
                    ).map {
                        case Result.Failure(e: JsonRpcError) =>
                            assert(e.code == -32002, s"expected -32002 before initialize, got ${e.code}")
                            client.call[InitReq, InitResp]("initialize", InitReq("my-client")).map { initResp =>
                                assert(initResp == InitResp("test-server"))
                                client.call[HoverReq, HoverResp]("textDocument/hover", HoverReq(42)).map { hoverResp =>
                                    assert(hoverResp == HoverResp("hover at line 42"))
                                }
                            }
                        case other => fail(s"expected -32002 before initialize, got $other")
                    }
                }
            }
        }
    }

end HttpStyleTest
