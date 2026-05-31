package kyo

/** Tests for McpServer engine wiring (T-009, T-010, INV-004, INV-017, INV-028). */
class McpServerTest extends Test:

    case class EchoReq(msg: String) derives Schema, CanEqual
    case class EchoResp(msg: String) derives Schema, CanEqual

    private val toolRoute = McpRoute.tool[EchoReq, McpContent.Text]("echo") { (req, _) =>
        McpContent.Text(req.msg, Absent)
    }

    private val resourceUri   = McpResourceUri.parse("file:///data").get
    private val resourceRoute = McpRoute.resource(resourceUri, "data")((_, _) => Chunk.empty)

    // T-009: McpServer.init prepends engine-owned initialize route at index 0.
    "init with two user routes: initialize route is registered in the handler (INV-004)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute, resourceRoute).flatMap { server =>
                Fiber.Promise.init[Unit, Sync].map { promise =>
                    val ctx    = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
                    val result = server.underlying.unsafe.dispatch("initialize", Structure.Value.Record(Chunk.empty), ctx)
                    server.closeNow.andThen {
                        assert(result.isDefined)
                    }
                }
            }
        }
    }

    // T-009: close(using Frame) completes without error (behavioral test for 30s grace delegation).
    "close completes without error on a live server (INV-028)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                server.close.andThen(succeed)
            }
        }
    }

    "closeNow completes without error on a live server" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    // T-010: notifyToolsListChanged with listChanged=false must not send any notification.
    "notifyToolsListChanged with listChanged=false emits no notification (silent drop, INV-017)" in run {
        val emptyCaps = McpCapabilities.Server(tools = Present(McpCapabilities.ToolsCapability(listChanged = false)))
        val config    = McpConfig.default.declaredCapabilities(emptyCaps)

        // CountingTransport counts outbound send() invocations.
        val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        JsonRpcTransport.inMemory.map { (ta, _) =>
            class CountingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
                    Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))
                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    inner.incoming
                def close(using Frame): Unit < Async =
                    inner.close
            end CountingTransport

            val ct = CountingTransport(ta)
            McpServer.initUnscoped(ct, config)(toolRoute).flatMap { server =>
                server.notifyToolsListChanged.andThen {
                    val count = counter.get()(using AllowUnsafe.embrace.danger)
                    server.closeNow.andThen {
                        assert(count == 0)
                    }
                }
            }
        }
    }

    // INV-017: notify* methods must type-check as Unit < (Async & Abort[Closed]) with no McpError.
    "notifyToolsListChanged return type is Unit < (Async & Abort[Closed]) (INV-017)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                val effect: Unit < (Async & Abort[Closed]) = server.notifyToolsListChanged
                Abort.run[Closed](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "protocolVersion returns Absent before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta).flatMap { server =>
                val ver = server.protocolVersion
                server.closeNow.andThen {
                    assert(ver == Absent)
                }
            }
        }
    }

    "clientCapabilities returns Absent before handshake" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                val caps = server.clientCapabilities
                server.closeNow.andThen {
                    assert(caps == Absent)
                }
            }
        }
    }

    "underlying returns a JsonRpcHandler instance (access does not throw)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                // Access the underlying handler to confirm it is accessible.
                val _handler = server.underlying
                server.closeNow.andThen(succeed)
            }
        }
    }

    // Dispatch-path test: completion/complete routes the request to the registered handler and returns non-empty values.
    "completion/complete dispatches to registered handler and returns handler values (not Chunk.empty)" in run {
        val ref = McpRoute.CompletionRef.Prompt("myPrompt")
        val completionRoute = McpRoute.completion(ref) { (_, arg, _) =>
            McpRoute.CompletionResult(Chunk(arg.value + "-completed"), Absent, Absent)
        }
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, completionRoute).flatMap { server =>
                Fiber.Promise.init[Unit, Sync].map { promise =>
                    val ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
                    // Build CompleteParams as Structure.Value.
                    // A sum type variant (sealed enum case) encodes as Record("VariantName" -> Record(fields...)).
                    // CompleteParams(ref=CompletionRef.Prompt("myPrompt"), argument=CompletionArg("color", "re")):
                    val params = Structure.Value.Record(Chunk(
                        "ref" -> Structure.Value.Record(Chunk(
                            "Prompt" -> Structure.Value.Record(Chunk(
                                "name" -> Structure.Value.Str("myPrompt")
                            ))
                        )),
                        "argument" -> Structure.Value.Record(Chunk(
                            "name"  -> Structure.Value.Str("color"),
                            "value" -> Structure.Value.Str("re")
                        ))
                    ))
                    val resultOpt = server.underlying.unsafe.dispatch("completion/complete", params, ctx)
                    assert(resultOpt.isDefined, "completion/complete route must be registered")
                    resultOpt.get.flatMap { resultValue =>
                        // resultValue is CompleteResult { completion: CompletionResult { values: Sequence[Str], ... } }.
                        // Extract the Sequence under "completion" -> "values" and verify it contains the handler's output.
                        val handlerValues: Chunk[String] = resultValue match
                            case Structure.Value.Record(outer) =>
                                outer.toSeq.collectFirst {
                                    case ("completion", Structure.Value.Record(inner)) =>
                                        inner.toSeq.collectFirst {
                                            case ("values", Structure.Value.Sequence(elems)) =>
                                                elems.collect { case Structure.Value.Str(s) => s }
                                        }.getOrElse(Chunk.empty)
                                }.getOrElse(Chunk.empty)
                            case _ => Chunk.empty
                        server.closeNow.andThen {
                            assert(handlerValues.nonEmpty, s"expected non-empty values from handler, got empty; resultValue=$resultValue")
                            assert(handlerValues == Chunk("re-completed"), s"handler must return 're-completed', got $handlerValues")
                        }
                    }
                }
            }
        }
    }

end McpServerTest
