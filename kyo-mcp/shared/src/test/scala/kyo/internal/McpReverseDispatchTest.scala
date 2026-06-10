package kyo.internal

import kyo.*

/** Tests for McpReverseDispatch client-side reverse-direction routing.
  *
  * Exercises reverse-direction request methods on McpServer (server-to-client calls) and
  * client-to-server notification methods, driving McpReverseDispatch.buildRoutes through the
  * public McpClient+McpServer surface.
  */
class McpReverseDispatchTest extends Test:

    private val clientInfo = McpInfo("reverse-test-client", "0.0.0")
    private val clientCaps = McpCapabilities.Client(
        sampling = Present(McpCapabilities.SamplingCapability()),
        roots = Present(McpCapabilities.RootsCapability(listChanged = true))
    )

    // Paired transport helper: server starts first so its dispatch loop is ready for client initialize.
    private def withPair[A, S](
        serverRoutes: Seq[McpHandler[?, ?, ?]],
        clientRoutes: Seq[McpHandler[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, serverRoutes*).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps, clientRoutes*).flatMap { client =>
                    f(server, client)
                }
            }
        }

    // notifyRootsListChanged: client sends "notifications/roots/list_changed" to server.
    "client.notifyRootsListChanged sends notification without error" in {
        withPair(Seq.empty, Seq.empty) { (_, client) =>
            Abort.run[Closed](client.notifyRootsListChanged).map { result =>
                assert(result.isSuccess, s"expected success, got $result")
            }
        }
    }

    // requestRoots: server calls roots/list on client; default handler returns empty chunk.
    "server.requestRoots returns empty Chunk when client has no roots handler" in {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpException | Closed](server.requestRoots).map {
                case Result.Success(roots) =>
                    assert(roots == Chunk.empty, s"expected empty roots, got $roots")
                case Result.Failure(err) =>
                    fail(s"expected success, got failure: $err")
                case Result.Panic(t) =>
                    fail(s"expected success, got panic: $t")
            }
        }
    }

    // requestSampling: server calls sampling/createMessage on client; no client handler returns rejection.
    "server.requestSampling aborts when no client sampling handler" in {
        val req = McpServer.SamplingRequest(
            messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("hello"))),
            maxTokens = 128
        )
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpException | Closed](server.requestSampling(req)).map { result =>
                result match
                    case Result.Failure(_) =>
                        succeed
                    case Result.Success(_) =>
                        fail("expected failure when no sampling handler registered")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: $t")
            }
        }
    }

    // requestElicitation: server calls elicitation/create on client; no client handler returns rejection.
    "server.requestElicitation aborts when no client elicitation handler" in {
        val elicitReq = McpServer.ElicitationRequest(
            message = "Please provide your name.",
            requestedSchema = Json.JsonSchema.Null
        )
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpException | Closed](server.requestElicitation(elicitReq)).map { result =>
                result match
                    case Result.Failure(_) =>
                        succeed
                    case Result.Success(_) =>
                        fail("expected failure when no elicitation handler registered")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: $t")
            }
        }
    }

    // McpServer.notifyToolsListChanged: typed end-to-end; return type is Unit < (Async & Abort[Closed]).
    "server.notifyToolsListChanged return type is Unit < (Async & Abort[Closed])" in {
        val emptyCaps = McpCapabilities.Server(tools = Present(McpCapabilities.ToolsCapability(listChanged = true)))
        val config    = McpConfig.default.declaredCapabilities(emptyCaps)
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, config)().flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val effect: Unit < (Async & Abort[Closed]) = server.notifyToolsListChanged
                    Abort.run[Closed](effect).andThen(succeed)
                }
            }
        }
    }

    // McpServer reverse-direction extension methods are typed end-to-end (no Structure.Value escape).
    "requestSampling return type is McpServer.SamplingResponse < ... (typed, no Structure.Value)" in {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            val req = McpServer.SamplingRequest(
                messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("q"))),
                maxTokens = 16
            )
            val _: McpServer.SamplingResponse < (Async & Abort[McpException | Closed]) = server.requestSampling(req)
            succeed
        }
    }

    "requestRoots return type is Chunk[McpServer.Root] < ... (typed, no Structure.Value)" in {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            val _: Chunk[McpServer.Root] < (Async & Abort[McpException | Closed]) = server.requestRoots
            succeed
        }
    }

    // Route ordering: user-registered sampling handler must take precedence over default-reject.
    //
    // McpClientEngine.initClient populates serverRef for all carrier routes with a no-op sentinel
    // server, so a user-registered client-side sampling handler successfully returns its response
    // rather than panicking with "McpServer not initialized". The test verifies the dispatch hop
    // (user route overrides default-reject) by asserting that the response is the one returned by
    // the user handler.
    "user-registered sampling route is reached instead of default reject" in {
        val req = McpServer.SamplingRequest(
            messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("ping"))),
            maxTokens = 8
        )
        val userSamplingRoute =
            McpHandler.custom[McpServer.SamplingRequest]("sampling/createMessage") { _ =>
                McpServer.SamplingResponse(McpContent.Role.Assistant, McpContent.Text("pong"), "test-model")
            }
        withPair(Seq.empty, Seq(userSamplingRoute)) { (server, _) =>
            Abort.run[McpException | Closed](server.requestSampling(req)).map {
                case Result.Success(resp) =>
                    assert(resp.model == "test-model")
                    assert(resp.role == McpContent.Role.Assistant)
                case Result.Failure(err) =>
                    fail(s"expected user sampling handler to succeed, got failure: $err")
                case Result.Panic(t) =>
                    fail(s"expected user sampling handler to succeed, got panic: $t")
            }
        }
    }

end McpReverseDispatchTest
