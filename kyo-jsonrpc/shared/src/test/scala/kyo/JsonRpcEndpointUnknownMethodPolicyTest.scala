package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcEndpointUnknownMethodPolicyTest extends JsonRpcTestBase:

    case class Ping(msg: String) derives Schema, CanEqual
    case class Pong(reply: String) derives Schema, CanEqual
    case class Empty() derives Schema, CanEqual

    private def mkEndpoints(
        methodsA: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        methodsB: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        configA: JsonRpcEndpoint.Config = JsonRpcEndpoint.Config(),
        configB: JsonRpcEndpoint.Config = JsonRpcEndpoint.Config()
    )(using Frame): (JsonRpcEndpoint, JsonRpcEndpoint) < (Sync & Async & Scope) =
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, methodsA, configA).map { endpointA =>
                JsonRpcEndpoint.init(tb, methodsB, configB).map { endpointB =>
                    (endpointA, endpointB)
                }
            }
        }

    "lsp policy: unknown request returns MethodNotFound code -32601" in run {
        val lspConfig = JsonRpcEndpoint.Config(unknownMethod = JsonRpcEndpoint.UnknownMethodPolicy.lsp)
        mkEndpoints(Seq.empty, Seq.empty, configB = lspConfig).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[Empty, Empty]("unknown/method", Empty())).map {
                case Result.Failure(e: JsonRpcError) => assert(e.code == -32601)
                case other                           => fail(s"expected MethodNotFound, got $other")
            }
        }
    }

    "lsp policy: unknown notification starting with dollar-slash is silently dropped" in run {
        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val dollarMethod = JsonRpcMethod[Empty, Unit, Async & Abort[JsonRpcError]]("$/setTrace") {
            (_, _) => Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        val lspConfig = JsonRpcEndpoint.Config(unknownMethod = JsonRpcEndpoint.UnknownMethodPolicy.lsp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { a =>
                JsonRpcEndpoint.init(tb, Seq.empty, lspConfig).map { _ =>
                    a.notify[Empty]("$/setTrace", Empty()).andThen {
                        Async.sleep(80.millis).andThen {
                            Sync.defer(assert(handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0))
                        }
                    }
                }
            }
        }
    }

    "lsp policy: unknown notification not starting with dollar-slash is silently dropped" in run {
        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val lspConfig      = JsonRpcEndpoint.Config(unknownMethod = JsonRpcEndpoint.UnknownMethodPolicy.lsp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { a =>
                JsonRpcEndpoint.init(tb, Seq.empty, lspConfig).map { _ =>
                    a.notify[Empty]("unknown/event", Empty()).andThen {
                        Async.sleep(80.millis).andThen {
                            Sync.defer(assert(handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0))
                        }
                    }
                }
            }
        }
    }

    "strict policy: unknown notification Reject closes engine" in run {
        val strictConfig = JsonRpcEndpoint.Config(unknownMethod = JsonRpcEndpoint.UnknownMethodPolicy.strict)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty).map { a =>
                JsonRpcEndpoint.init(tb, Seq.empty, strictConfig).map { b =>
                    a.notify[Empty]("unknown/event", Empty()).andThen {
                        untilTrue(Sync.defer(b.impl.config.unknownMethod == JsonRpcEndpoint.UnknownMethodPolicy.strict)).andThen {
                            Async.sleep(150.millis).andThen {
                                Abort.run[JsonRpcError | Closed](a.call[Empty, Empty]("any/method", Empty())).map {
                                    case Result.Failure(_) => succeed
                                    case Result.Success(v) => fail(s"expected failure after B closed, got $v")
                                    case Result.Panic(t)   => fail(s"unexpected panic: $t")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "gate Allow: request reaches registered handler normally" in run {
        val allowGate: JsonRpcEndpoint.MessageGate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                JsonRpcEndpoint.MessageGate.Decision.Allow

        val pingMethod = JsonRpcMethod[Ping, Pong, Async & Abort[JsonRpcError]]("ping") {
            (req, _) => Pong("pong:" + req.msg)
        }
        val gatedConfig = JsonRpcEndpoint.Config(gate = Present(allowGate))
        mkEndpoints(Seq.empty, Seq(pingMethod), configB = gatedConfig).map { (a, _) =>
            a.call[Ping, Pong]("ping", Ping("hello")).map { resp =>
                assert(resp == Pong("pong:hello"))
            }
        }
    }

    "gate Reject for Request: caller sees error reply with gate error code" in run {
        val gateError = JsonRpcError(-32099, "gate blocked", Absent)
        val rejectGate: JsonRpcEndpoint.MessageGate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                JsonRpcEndpoint.MessageGate.Decision.Reject(gateError)

        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val pingMethod = JsonRpcMethod[Ping, Pong, Async & Abort[JsonRpcError]]("ping") {
            (req, _) =>
                Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen(Pong("pong"))
        }
        val gatedConfig = JsonRpcEndpoint.Config(gate = Present(rejectGate))
        mkEndpoints(Seq.empty, Seq(pingMethod), configB = gatedConfig).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[Ping, Pong]("ping", Ping("x"))).map {
                case Result.Failure(e: JsonRpcError) =>
                    assert(e.code == -32099 && handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0)
                case other => fail(s"expected gate error, got $other")
            }
        }
    }

    "gate Reject for Notification: notification dropped, engine does not close" in run {
        val rejectGate: JsonRpcEndpoint.MessageGate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                JsonRpcEndpoint.MessageGate.Decision.Reject(JsonRpcError(-32000, "rejected", Absent))

        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val eventMethod = JsonRpcMethod[Empty, Unit, Async & Abort[JsonRpcError]]("event") {
            (_, _) => Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        val gatedConfig = JsonRpcEndpoint.Config(gate = Present(rejectGate))
        mkEndpoints(Seq.empty, Seq(eventMethod), configB = gatedConfig).map { (a, _) =>
            a.notify[Empty]("event", Empty()).andThen {
                Async.sleep(80.millis).andThen {
                    Sync.defer(assert(handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0)).andThen {
                        a.call[Empty, Pong]("event", Empty()).andThen {
                            fail("expected gate-reject error but got success")
                        }.handle(
                            Abort.run[JsonRpcError | Closed](_).map {
                                case Result.Failure(_: JsonRpcError) => succeed
                                case Result.Failure(_: Closed)       => fail("engine closed unexpectedly after notification Reject")
                                case other                           => fail(s"unexpected: $other")
                            }
                        )
                    }
                }
            }
        }
    }

    "gate Drop for Request: call hangs until timeout with JsonRpcError" in run {
        val dropGate: JsonRpcEndpoint.MessageGate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                JsonRpcEndpoint.MessageGate.Decision.Drop

        val pingMethod = JsonRpcMethod[Ping, Pong, Async & Abort[JsonRpcError]]("ping") {
            (req, _) => Pong("pong")
        }
        val gatedConfig  = JsonRpcEndpoint.Config(gate = Present(dropGate))
        val callerConfig = JsonRpcEndpoint.Config(requestTimeout = 150.millis)
        mkEndpoints(Seq.empty, Seq(pingMethod), configA = callerConfig, configB = gatedConfig).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[Ping, Pong]("ping", Ping("x"))).map {
                case Result.Failure(_: JsonRpcError) => succeed
                case Result.Failure(_: Closed)       => fail("expected timeout JsonRpcError, not Closed")
                case Result.Success(v)               => fail(s"expected timeout error, got $v")
                case Result.Panic(t)                 => fail(s"unexpected panic: $t")
            }
        }
    }

    "gate LSP initialize pattern: allows initialize, rejects others with ServerNotInitialized" in run {
        val serverNotInitialized = JsonRpcError(-32002, "ServerNotInitialized", Absent)
        val initGate: JsonRpcEndpoint.MessageGate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                env match
                    case JsonRpcEnvelope.Request(_, "initialize", _, _) =>
                        JsonRpcEndpoint.MessageGate.Decision.Allow
                    case JsonRpcEnvelope.Request(_, _, _, _) =>
                        JsonRpcEndpoint.MessageGate.Decision.Reject(serverNotInitialized)
                    case _ =>
                        JsonRpcEndpoint.MessageGate.Decision.Allow

        val initMethod = JsonRpcMethod[Ping, Pong, Async & Abort[JsonRpcError]]("initialize") {
            (req, _) => Pong("initialized:" + req.msg)
        }
        val gatedConfig = JsonRpcEndpoint.Config(gate = Present(initGate))
        mkEndpoints(Seq.empty, Seq(initMethod), configB = gatedConfig).map { (a, _) =>
            a.call[Ping, Pong]("initialize", Ping("client")).map { resp =>
                assert(resp == Pong("initialized:client"))
            }.andThen {
                Abort.run[JsonRpcError | Closed](
                    a.call[Ping, Pong]("textDocument/hover", Ping("x"))
                ).map {
                    case Result.Failure(e: JsonRpcError) => assert(e.code == -32002)
                    case other                           => fail(s"expected ServerNotInitialized -32002, got $other")
                }
            }
        }
    }

end JsonRpcEndpointUnknownMethodPolicyTest
