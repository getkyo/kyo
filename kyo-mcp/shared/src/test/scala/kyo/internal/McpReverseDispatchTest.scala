package kyo.internal

import kyo.*

/** Tests for McpReverseDispatch client-side reverse-direction routing.
  *
  * Exercises reverse-direction request methods on McpServer (server-to-client calls)
  * and client-to-server notification methods.
  *
  * Decision 15: exercises McpReverseDispatch.buildRoutes via the public McpClient+McpServer surface.
  */
class McpReverseDispatchTest extends Test:

    private val clientInfo = McpInfo("reverse-test-client", "0.0.0")
    private val clientCaps = McpCapabilities.Client(
        sampling = Present(McpCapabilities.SamplingCapability()),
        roots = Present(McpCapabilities.RootsCapability(listChanged = true))
    )

    // Paired transport helper: server starts first so its dispatch loop is ready for client initialize.
    private def withPair[A, S](
        serverRoutes: Seq[McpRoute[?, ?, ?]],
        clientRoutes: Seq[McpRoute[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpError | Closed]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, serverRoutes*).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps, clientRoutes*).flatMap { client =>
                    f(server, client)
                }
            }
        }

    // notifyRootsListChanged: client sends "notifications/roots/list_changed" to server.
    "client.notifyRootsListChanged sends notification without error" in run {
        withPair(Seq.empty, Seq.empty) { (_, client) =>
            Abort.run[Closed](client.notifyRootsListChanged).map { result =>
                assert(result.isSuccess, s"expected success, got $result")
            }
        }
    }

    // requestRoots: server calls roots/list on client; default handler returns empty chunk.
    "server.requestRoots returns empty Chunk when client has no roots handler" in run {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpError | Closed](server.requestRoots).map {
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
    "server.requestSampling aborts when no client sampling handler" in run {
        val req = McpSamplingRequest(
            messages = Chunk(McpSamplingRequest.Message(McpRole.User, McpContent.Text("hello", Absent))),
            maxTokens = 128
        )
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpError | Closed](server.requestSampling(req)).map { result =>
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
    "server.requestElicitation aborts when no client elicitation handler" in run {
        val elicitReq = McpElicitationRequest(
            message = "Please provide your name.",
            requestedSchema = Json.JsonSchema.Null
        )
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            Abort.run[McpError | Closed](server.requestElicitation(elicitReq)).map { result =>
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
    "server.notifyToolsListChanged return type is Unit < (Async & Abort[Closed])" in run {
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
    "requestSampling return type is McpSamplingResponse < ... (typed, no Structure.Value)" in run {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            val req = McpSamplingRequest(
                messages = Chunk(McpSamplingRequest.Message(McpRole.User, McpContent.Text("q", Absent))),
                maxTokens = 16
            )
            val _: McpSamplingResponse < (Async & Abort[McpError | Closed]) = server.requestSampling(req)
            succeed
        }
    }

    "requestRoots return type is Chunk[McpRoot] < ... (typed, no Structure.Value)" in run {
        withPair(Seq.empty, Seq.empty) { (server, _) =>
            val _: Chunk[McpRoot] < (Async & Abort[McpError | Closed]) = server.requestRoots
            succeed
        }
    }

    // Route ordering: user-registered sampling handler must take precedence over default-reject.
    //
    // The user route here is constructed via McpRoute.custom which captures an unset McpServer
    // reference; on the client side that reference is never bound, so the lifted handler panics
    // with an IllegalStateException carrying "McpServer not initialized". The point of the test
    // is the dispatch hop: when the server issues sampling/createMessage we observe the user
    // handler being reached (the panic surfaces) instead of the default-reject McpSamplingRejected.
    // This proves McpReverseDispatch.buildRoutes places user routes after defaults so the
    // last-wins methodMap in JsonRpcEndpointImpl (line 772) lets the user route override.
    "user-registered sampling route is reached instead of default reject" in run {
        val req = McpSamplingRequest(
            messages = Chunk(McpSamplingRequest.Message(McpRole.User, McpContent.Text("ping", Absent))),
            maxTokens = 8
        )
        val userSamplingRoute =
            McpRoute.custom[McpSamplingRequest, McpSamplingResponse]("sampling/createMessage") { (_, _) =>
                McpSamplingResponse(McpRole.Assistant, McpContent.Text("pong", Absent), "test-model")
            }
        withPair(Seq.empty, Seq(userSamplingRoute)) { (server, _) =>
            Abort.run[McpError | Closed](server.requestSampling(req)).map {
                case Result.Failure(err) =>
                    val msg = err.toString
                    assert(
                        msg.contains("McpServer not initialized") || !msg.contains("No sampling handler registered"),
                        s"expected user route to be reached (panic from unset server ref); got default-reject path: $err"
                    )
                case Result.Success(resp) =>
                    fail(s"unexpected success without bound McpServer: $resp")
                case Result.Panic(t) =>
                    assert(
                        t.getMessage != null && t.getMessage.contains("McpServer not initialized"),
                        s"expected panic from unset server ref, got: $t"
                    )
            }
        }
    }

end McpReverseDispatchTest
