package kyo

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kyo.Maybe.Absent
import kyo.Maybe.Present

class ScenarioHttpStyleTest extends Test:

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
        val sent = new ConcurrentLinkedQueue[JsonRpcEnvelope]()

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.add(env))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end CapturingTransport

    "single server endpoint with add and greet: two sequential calls return correct typed results" in run {
        val addMethod = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        val greetMethod = JsonRpcMethod[GreetReq, GreetResp, Async & Abort[JsonRpcError]]("greet") {
            (req, _) => GreetResp(s"Hello, ${req.name}!")
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty).map { client =>
                JsonRpcEndpoint.init(tb, Seq(addMethod, greetMethod)).map { _ =>
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
        val handlerRan = new AtomicInteger(0)
        val logMethod = JsonRpcMethod[LogMsg, Unit, Async & Abort[JsonRpcError]]("log") {
            (_, _) => Sync.defer(discard(handlerRan.incrementAndGet()))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty).map { client =>
                JsonRpcEndpoint.init(tb, Seq(logMethod)).map { _ =>
                    val framesBefore = Sync.defer(capA.sent.size())
                    framesBefore.map { before =>
                        client.notify[LogMsg]("log", LogMsg("event occurred")).andThen {
                            untilTrue(Sync.defer(handlerRan.get() == 1)).andThen {
                                Sync.defer {
                                    import scala.jdk.CollectionConverters.*
                                    val responses = capA.sent.asScala.collect {
                                        case r: JsonRpcEnvelope.Response => r
                                    }
                                    assert(responses.isEmpty, s"expected zero response frames from server, got ${responses.size}")
                                    assert(handlerRan.get() == 1, "handler should have run exactly once")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "LSP pre-init gate: requests before initialize return -32002; after initialize, methods succeed" in run {
        val serverNotInitialized = JsonRpcError(-32002, "Server not initialized", Absent)
        var initialized          = false

        val lspInitGate: MessageGate = new MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync =
                env match
                    case JsonRpcEnvelope.Request(_, "initialize", _, _) =>
                        MessageGate.Decision.Allow
                    case JsonRpcEnvelope.Request(_, _, _, _) if !initialized =>
                        MessageGate.Decision.Reject(serverNotInitialized)
                    case _ =>
                        MessageGate.Decision.Allow

        val initMethod = JsonRpcMethod[InitReq, InitResp, Async & Abort[JsonRpcError]]("initialize") {
            (req, _) =>
                Sync.defer { initialized = true }.andThen(InitResp("test-server"))
        }
        val hoverMethod = JsonRpcMethod[HoverReq, HoverResp, Async & Abort[JsonRpcError]]("textDocument/hover") {
            (req, _) => HoverResp(s"hover at line ${req.line}")
        }

        val gatedConfig = JsonRpcEndpoint.Config(gate = Present(lspInitGate))
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty).map { client =>
                JsonRpcEndpoint.init(tb, Seq(initMethod, hoverMethod), gatedConfig).map { _ =>
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

end ScenarioHttpStyleTest
