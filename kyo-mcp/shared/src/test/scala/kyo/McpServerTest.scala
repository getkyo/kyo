package kyo

/** Tests for McpServer engine wiring. */
class McpServerTest extends Test:

    case class EchoReq(msg: String) derives Schema, CanEqual
    case class EchoResp(msg: String) derives Schema, CanEqual

    private val toolRoute = McpHandler.tool[EchoReq]("echo") { req =>
        McpContent.Text(req.msg)
    }

    private val resourceUri   = McpResourceUri.parse("file:///data").get
    private val resourceRoute = McpHandler.resource(resourceUri, "data")(Chunk.empty)

    // McpServer.init prepends engine-owned initialize route at index 0.
    "init with two user routes: initialize route is registered in the handler" in {
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

    // close(using Frame) completes without error (behavioral test for 30s grace delegation).
    "close completes without error on a live server" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                server.close.andThen(succeed)
            }
        }
    }

    "closeNow completes without error on a live server" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                server.closeNow.andThen(succeed)
            }
        }
    }

    // notifyToolsListChanged with listChanged=false must not send any notification.
    "notifyToolsListChanged with listChanged=false emits no notification (silent drop)" in {
        val emptyCaps = McpCapabilities.Server(tools = Present(McpCapabilities.ToolsCapability(listChanged = false)))
        val config    = McpConfig.default.withDeclaredCapabilities(emptyCaps)

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

    // notify* methods must type-check as Unit < (Async & Abort[McpConnectionClosedException]).
    "notifyToolsListChanged return type is Unit < (Async & Abort[McpConnectionClosedException])" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                val effect: Unit < (Async & Abort[McpConnectionClosedException]) = server.notifyToolsListChanged
                Abort.run[McpConnectionClosedException](effect).andThen(server.closeNow).andThen(succeed)
            }
        }
    }

    "protocolVersion returns Absent before handshake" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta).flatMap { server =>
                val ver = server.protocolVersion
                server.closeNow.andThen {
                    assert(ver == Absent)
                }
            }
        }
    }

    "clientCapabilities returns Absent before handshake" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                val caps = server.clientCapabilities
                server.closeNow.andThen {
                    assert(caps == Absent)
                }
            }
        }
    }

    "underlying returns a JsonRpcHandler instance (access does not throw)" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, toolRoute).flatMap { server =>
                // Access the underlying handler to confirm it is accessible.
                val _handler = server.underlying
                server.closeNow.andThen(succeed)
            }
        }
    }

    // Dispatch-path test: completion/complete routes the request to the registered handler and returns non-empty values.
    "completion/complete dispatches to registered handler and returns handler values (not Chunk.empty)" in {
        val ref = McpHandler.CompletionRef.Prompt("myPrompt")
        val completionRoute = McpHandler.completion(ref) { arg =>
            McpHandler.CompletionOutcome(Chunk(arg.value + "-completed"), Absent, Absent)
        }
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, completionRoute).flatMap { server =>
                Fiber.Promise.init[Unit, Sync].map { promise =>
                    val ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
                    // Build CompleteParams as Structure.Value using the hand-rolled CompletionRef wire format.
                    // Per MCP 2025-06-18 §3.3, CompletionRef uses a "type" discriminator field:
                    //   Prompt encodes as {"type":"ref/prompt","name":"..."}
                    //   Resource encodes as {"type":"ref/resource","uri":"..."}
                    // CompleteParams(ref=CompletionRef.Prompt("myPrompt"), argument=CompletionArg("color", "re")):
                    val params = Structure.Value.Record(Chunk(
                        "ref" -> Structure.Value.Record(Chunk(
                            "type" -> Structure.Value.Str("ref/prompt"),
                            "name" -> Structure.Value.Str("myPrompt")
                        )),
                        "argument" -> Structure.Value.Record(Chunk(
                            "name"  -> Structure.Value.Str("color"),
                            "value" -> Structure.Value.Str("re")
                        ))
                    ))
                    val resultOpt = server.underlying.unsafe.dispatch("completion/complete", params, ctx)
                    assert(resultOpt.isDefined, "completion/complete route must be registered")
                    resultOpt.get.flatMap { resultValue =>
                        // resultValue is CompleteResult { completion: CompletionOutcome { values: Sequence[Str], ... } }.
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

    // requestSampling against a client that did NOT advertise sampling surfaces McpCapabilityNotAdvertisedException.
    "requestSampling against no-sampling client surfaces McpCapabilityNotAdvertisedException" in {
        val clientInfo = McpInfo("inv11-client", "0.0.0")
        val clientCaps = McpCapabilities.Client()
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps)
            ).flatMap { (srv, client) =>
                val req = McpServer.SamplingRequest(
                    messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("ping"))),
                    maxTokens = 8
                )
                Abort.run[McpException](srv.requestSampling(req)).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Failure(_: McpCapabilityNotAdvertisedException) => succeed
                            case Result.Failure(other) =>
                                fail(s"expected McpCapabilityNotAdvertisedException, got: $other")
                            case Result.Success(_) =>
                                fail("expected failure, got success")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // a genuine rejection (sampling advertised but handler aborts) surfaces McpSamplingRejectedException.
    "requestSampling with rejection handler surfaces McpSamplingRejectedException not McpCapabilityNotAdvertisedException" in {
        val clientInfo = McpInfo("inv11b-client", "0.0.0")
        val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))
        val rejectingHandler = McpClientHandler.onSampling[McpSamplingRejectedException] { _ =>
            Abort.fail(McpSamplingRejectedException("deliberate rejection"))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps, rejectingHandler)
            ).flatMap { (srv, client) =>
                val req = McpServer.SamplingRequest(
                    messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("ping"))),
                    maxTokens = 8
                )
                Abort.run[McpException](srv.requestSampling(req)).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Failure(_: McpSamplingRejectedException) => succeed
                            case Result.Failure(other) =>
                                fail(s"expected McpSamplingRejectedException, got: $other")
                            case Result.Success(_) =>
                                fail("expected failure, got success")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // requestRoots when roots advertised but no onRoots handler returns typed error.
    "requestRoots no-handler-but-advertised aborts a typed error" in {
        val clientInfo = McpInfo("inv12-client", "0.0.0")
        val clientCaps = McpCapabilities.Client(roots = Present(McpCapabilities.RootsCapability()))
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](srv.requestRoots).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Failure(_: McpException) => succeed
                            case Result.Success(roots) =>
                                fail(s"expected typed error, got success with roots: $roots")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // a genuine empty workspace returns Chunk.empty (user onRoots handler returning empty).
    "requestRoots with onRoots returning empty returns Chunk.empty" in {
        val clientInfo   = McpInfo("inv12b-client", "0.0.0")
        val clientCaps   = McpCapabilities.Client(roots = Present(McpCapabilities.RootsCapability()))
        val emptyHandler = McpClientHandler.onRoots[Nothing] { Chunk.empty }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps, emptyHandler)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](srv.requestRoots).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Success(roots) =>
                                assert(roots == Chunk.empty, s"expected Chunk.empty, got $roots")
                            case Result.Failure(err) =>
                                fail(s"expected empty roots success, got failure: $err")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // notifyResourceUpdated with subscribe=false emits no notification.
    "notifyResourceUpdated with subscribe=false emits no notification" in {
        val uri           = McpResourceUri.parse("file:///no-sub").get
        val resourceRoute = McpHandler.resource(uri, "r")(Chunk.empty)

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
            McpServer.initUnscoped(ct, resourceRoute).flatMap { server =>
                Abort.run[McpConnectionClosedException](server.notifyResourceUpdated(uri)).andThen {
                    val count = counter.get()(using AllowUnsafe.embrace.danger)
                    server.closeNow.andThen {
                        assert(count == 0, s"expected 0 notifications with subscribe=false, got $count")
                    }
                }
            }
        }
    }

    // requestElicitationAs decodes Accept and aborts on non-conforming payload.
    "requestElicitationAs decodes Accept and aborts McpToolStructuredDecodeException on non-conforming" in {
        case class Answer(value: String) derives Schema, CanEqual

        val clientInfo = McpInfo("inv22-client", "0.0.0")
        val clientCaps = McpCapabilities.Client(elicitation = Present(McpCapabilities.ElicitationCapability()))

        val conformingHandler = McpClientHandler.onElicitation[Nothing] { _ =>
            McpServer.ElicitationResponse(
                action = McpServer.ElicitationResponse.Action.Accept,
                content = Present(Structure.encode(Answer("hello")))
            )
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps, conformingHandler)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](srv.requestElicitationAs[Answer]("please answer")).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Success(McpServer.ElicitationOutcome.Accept(a)) =>
                                assert(a == Answer("hello"), s"expected Answer(hello), got $a")
                            case Result.Success(other) =>
                                fail(s"expected Accept(Answer(hello)), got $other")
                            case Result.Failure(err) =>
                                fail(s"expected success, got failure: $err")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

    // non-conforming: a payload that cannot decode to the requested type aborts McpToolStructuredDecodeException.
    "requestElicitationAs aborts McpToolStructuredDecodeException on non-conforming Accept payload" in {
        case class Answer(value: String) derives Schema, CanEqual

        val clientInfo = McpInfo("inv22b-client", "0.0.0")
        val clientCaps = McpCapabilities.Client(elicitation = Present(McpCapabilities.ElicitationCapability()))

        // Return a payload that cannot decode to Answer (missing required field "value").
        val nonConformingHandler = McpClientHandler.onElicitation[Nothing] { _ =>
            McpServer.ElicitationResponse(
                action = McpServer.ElicitationResponse.Action.Accept,
                content = Present(Structure.Value.Record(Chunk("wrong_field" -> Structure.Value.Str("bad"))))
            )
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, clientInfo, clientCaps, nonConformingHandler)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](srv.requestElicitationAs[Answer]("please answer")).flatMap { result =>
                    srv.closeNow.andThen(client.closeNow).andThen {
                        result match
                            case Result.Failure(_: McpToolStructuredDecodeException) => succeed
                            case Result.Failure(other) =>
                                fail(s"expected McpToolStructuredDecodeException, got: $other")
                            case Result.Success(outcome) =>
                                fail(s"expected failure on non-conforming payload, got success: $outcome")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                    }
                }
            }
        }
    }

end McpServerTest
