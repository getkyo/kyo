package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerUnknownMethodPolicyTest extends JsonRpcTest:

    case class Ping(msg: String) derives Schema, CanEqual
    case class Pong(reply: String) derives Schema, CanEqual
    case class Empty() derives Schema, CanEqual

    private def mkEndpoints(
        methodsA: Seq[JsonRpcRoute[?, ?, ?]],
        methodsB: Seq[JsonRpcRoute[?, ?, ?]],
        configA: JsonRpcHandler.Config = JsonRpcHandler.Config(),
        configB: JsonRpcHandler.Config = JsonRpcHandler.Config()
    )(using Frame): (JsonRpcHandler, JsonRpcHandler) < (Sync & Async & Scope) =
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, methodsA, configA).map { endpointA =>
                JsonRpcHandler.init(tb, methodsB, configB).map { endpointB =>
                    (endpointA, endpointB)
                }
            }
        }

    "lsp policy: unknown request returns MethodNotFound code -32601" in run {
        val lspConfig = JsonRpcHandler.Config(unknownMethod = JsonRpcUnknownMethodPolicy.lsp)
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
        val dollarMethod = JsonRpcRoute.request[Empty, Unit]("$/setTrace") {
            (_, _) => Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        val lspConfig = JsonRpcHandler.Config(unknownMethod = JsonRpcUnknownMethodPolicy.lsp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, lspConfig).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, lspConfig).map { _ =>
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
        val lspConfig      = JsonRpcHandler.Config(unknownMethod = JsonRpcUnknownMethodPolicy.lsp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, lspConfig).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, lspConfig).map { _ =>
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
        val strictConfig = JsonRpcHandler.Config(unknownMethod = JsonRpcUnknownMethodPolicy.strict)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, strictConfig).map { b =>
                    a.notify[Empty]("unknown/event", Empty()).andThen {
                        untilTrue(Sync.defer(b.unsafe.asInstanceOf[
                            internal.engine.JsonRpcEndpointImpl
                        ].config.unknownMethod == JsonRpcUnknownMethodPolicy.strict)).andThen {
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

    "ignoreUnknownNotification predicate: matching notifications are silently dropped" in run {
        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        // Custom predicate: silently ignore notifications whose method starts with "internal/"
        val customPolicy = JsonRpcUnknownMethodPolicy.minimal.copy(
            ignoreUnknownNotification = _.startsWith("internal/")
        )
        val customConfig = JsonRpcHandler.Config(unknownMethod = customPolicy)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, customConfig).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, customConfig).map { _ =>
                    a.notify[Empty]("internal/heartbeat", Empty()).andThen {
                        Async.sleep(80.millis).andThen {
                            Sync.defer(assert(handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0))
                        }
                    }
                }
            }
        }
    }

    "ignoreUnknownNotification predicate: non-matching notifications go through onUnknownNotification" in run {
        // Unsafe: AtomicInt.Unsafe.init for invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        // Predicate only matches "internal/" prefix; "external/" should fall through to Drop (silent)
        val customPolicy = JsonRpcUnknownMethodPolicy.minimal.copy(
            ignoreUnknownNotification = _.startsWith("internal/")
        )
        val customConfig = JsonRpcHandler.Config(unknownMethod = customPolicy)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, customConfig).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, customConfig).map { _ =>
                    a.notify[Empty]("external/event", Empty()).andThen {
                        Async.sleep(80.millis).andThen {
                            // engine still running: unknown non-matching notification goes to Drop (onUnknownNotification)
                            Abort.run[JsonRpcError | Closed](a.call[Empty, Empty]("any/probe", Empty())).map {
                                case Result.Failure(e: JsonRpcError) => assert(e.code == -32601)
                                case Result.Failure(_: Closed)       => fail("engine closed unexpectedly")
                                case other                           => fail(s"expected MethodNotFound, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "ignoreUnknownNotification predicate: always-true predicate drops all unknown notifications silently" in run {
        // Unsafe: AtomicInt.Unsafe.init for invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val alwaysIgnorePolicy = JsonRpcUnknownMethodPolicy.strict.copy(
            ignoreUnknownNotification = _ => true
        )
        val alwaysIgnoreConfig = JsonRpcHandler.Config(unknownMethod = alwaysIgnorePolicy)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, alwaysIgnoreConfig).map { a =>
                JsonRpcHandler.init(tb, Seq.empty, alwaysIgnoreConfig).map { _ =>
                    a.notify[Empty]("would/reject", Empty()).andThen {
                        Async.sleep(80.millis).andThen {
                            // engine still running: the always-true predicate short-circuited Reject
                            Abort.run[JsonRpcError | Closed](a.call[Empty, Empty]("any/probe", Empty())).map {
                                case Result.Failure(e: JsonRpcError) => assert(e.code == -32601)
                                case Result.Failure(_: Closed) => fail("engine closed after notification: predicate should have ignored it")
                                case other                     => fail(s"expected MethodNotFound, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "gate Allow: request reaches registered handler normally" in run {
        val allowGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                JsonRpcMessageGate.Decision.Allow

        val pingMethod = JsonRpcRoute.request[Ping, Pong]("ping") {
            (req, _) => Pong("pong:" + req.msg)
        }
        val gatedConfig = JsonRpcHandler.Config(gate = Present(allowGate))
        mkEndpoints(Seq.empty, Seq(pingMethod), configB = gatedConfig).map { (a, _) =>
            a.call[Ping, Pong]("ping", Ping("hello")).map { resp =>
                assert(resp == Pong("pong:hello"))
            }
        }
    }

    "gate Reject for Request: caller sees error reply with gate error code" in run {
        val gateError = JsonRpcImplementationError(-32099, "gate blocked")
        val rejectGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                env match
                    case JsonRpcRequest(id, _, _, _) =>
                        JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(id, gateError))
                    case _ =>
                        JsonRpcMessageGate.Decision.Drop

        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val pingMethod = JsonRpcRoute.request[Ping, Pong]("ping") {
            (req, _) =>
                Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen(Pong("pong"))
        }
        val gatedConfig = JsonRpcHandler.Config(gate = Present(rejectGate))
        mkEndpoints(Seq.empty, Seq(pingMethod), configB = gatedConfig).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[Ping, Pong]("ping", Ping("x"))).map {
                case Result.Failure(e: JsonRpcError) =>
                    assert(e.code == -32099 && handlerInvoked.get()(using AllowUnsafe.embrace.danger) == 0)
                case other => fail(s"expected gate error, got $other")
            }
        }
    }

    "gate Reject for Notification: notification dropped, engine does not close" in run {
        val rejectGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                env match
                    case _: JsonRpcNotification =>
                        // For notifications (no id) Reject is logged and message is dropped silently.
                        JsonRpcMessageGate.Decision.Reject(
                            JsonRpcResponse(JsonRpcId.Num(0L), Absent, Present(JsonRpcImplementationError(-32000, "rejected")), Absent)
                        )
                    case _ =>
                        // Allow all other messages (requests) through so they can fail with MethodNotFound.
                        JsonRpcMessageGate.Decision.Allow

        // Unsafe: AtomicInt.Unsafe.init for handler invocation counter
        val handlerInvoked = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val eventMethod = JsonRpcRoute.request[Empty, Unit]("event") {
            (_, _) => Sync.defer(discard(handlerInvoked.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        val gatedConfig = JsonRpcHandler.Config(gate = Present(rejectGate))
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
        val dropGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                JsonRpcMessageGate.Decision.Drop

        val pingMethod = JsonRpcRoute.request[Ping, Pong]("ping") {
            (req, _) => Pong("pong")
        }
        val gatedConfig  = JsonRpcHandler.Config(gate = Present(dropGate))
        val callerConfig = JsonRpcHandler.Config(requestTimeout = 150.millis)
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
        val serverNotInitialized = JsonRpcImplementationError(-32002, "ServerNotInitialized")
        val initGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                env match
                    case JsonRpcRequest(_, "initialize", _, _) =>
                        JsonRpcMessageGate.Decision.Allow
                    case JsonRpcRequest(id, _, _, _) =>
                        JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(id, serverNotInitialized))
                    case _ =>
                        JsonRpcMessageGate.Decision.Allow

        val initMethod = JsonRpcRoute.request[Ping, Pong]("initialize") {
            (req, _) => Pong("initialized:" + req.msg)
        }
        val gatedConfig = JsonRpcHandler.Config(gate = Present(initGate))
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

end JsonRpcHandlerUnknownMethodPolicyTest
