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
        clientRoutes: Seq[McpClientHandler[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException]) =
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
            Abort.run[McpConnectionClosedException](client.notifyRootsListChanged).map { result =>
                assert(result.isSuccess, s"expected success, got $result")
            }
        }
    }

    // requestRoots: server calls roots/list on client; default handler (roots advertised but no onRoots
    // handler registered) aborts a typed error.
    "server.requestRoots aborts typed error when client has roots capability but no handler" in {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpException](server.requestRoots).map {
                case Result.Failure(_: McpException) =>
                    succeed
                case Result.Success(roots) =>
                    fail(s"expected typed error but got success: $roots")
                case Result.Panic(t) =>
                    fail(s"expected typed error, got panic: $t")
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
            Abort.run[McpException](server.requestSampling(req)).map { result =>
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
            requestedSchema = Json.JsonSchema.Null()
        )
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpException](server.requestElicitation(elicitReq)).map { result =>
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

    // McpServer.notifyToolsListChanged: typed end-to-end; return type is Unit < (Async & Abort[McpConnectionClosedException]).
    "server.notifyToolsListChanged return type is Unit < (Async & Abort[McpConnectionClosedException])" in {
        val emptyCaps = McpCapabilities.Server(tools = Present(McpCapabilities.ToolsCapability(listChanged = true)))
        val config    = McpConfig.default.withDeclaredCapabilities(emptyCaps)
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, config)().flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val effect: Unit < (Async & Abort[McpConnectionClosedException]) = server.notifyToolsListChanged
                    Abort.run[McpConnectionClosedException](effect).andThen(succeed)
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
            val _: McpServer.SamplingResponse < (Async & Abort[McpException]) = server.requestSampling(req)
            succeed
        }
    }

    "requestRoots return type is Chunk[McpServer.Root] < ... (typed, no Structure.Value)" in {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            val _: Chunk[McpServer.Root] < (Async & Abort[McpException]) = server.requestRoots
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
            McpClientHandler.onSampling { _ =>
                McpServer.SamplingResponse(McpContent.Role.Assistant, McpContent.Text("pong"), Present("test-model"))
            }
        withPair(Seq.empty, Seq(userSamplingRoute)) { (server, _) =>
            Abort.run[McpException](server.requestSampling(req)).map {
                case Result.Success(resp) =>
                    assert(resp.model == Present("test-model"))
                    assert(resp.role == McpContent.Role.Assistant)
                case Result.Failure(err) =>
                    fail(s"expected user sampling handler to succeed, got failure: $err")
                case Result.Panic(t) =>
                    fail(s"expected user sampling handler to succeed, got panic: $t")
            }
        }
    }

end McpReverseDispatchTest
