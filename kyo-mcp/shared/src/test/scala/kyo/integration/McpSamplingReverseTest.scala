package kyo.integration

import kyo.*

/** Integration test: sampling reverse-direction request (T-018, INV-014, INV-024). */
class McpSamplingReverseTest extends Test:

    // Client-side route that handles sampling/createMessage requests from the server.
    // Registered as a custom route on the client; McpReverseDispatch builds the handler.
    private val samplingRoute =
        McpRoute.custom[McpServer.SamplingRequest]("sampling/createMessage").handler { req =>
            McpServer.SamplingResponse(
                role = McpContent.Role.Assistant,
                content = McpContent.text("reply"),
                model = "model-x",
                stopReason = Present(McpServer.SamplingResponse.StopReason.EndTurn)
            )
        }

    private val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))

    "server.requestSampling returns the client-provided response (T-018)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("s"), clientCaps, samplingRoute)
            ).flatMap { (srv, client) =>
                srv.requestSampling(
                    McpServer.SamplingRequest(
                        messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("q"))),
                        maxTokens = 256
                    )
                ).flatMap { resp =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(resp.role == McpContent.Role.Assistant)
                        assert(resp.model == "model-x")
                        assert(resp.stopReason == Present(McpServer.SamplingResponse.StopReason.EndTurn))
                    end for
                }
            }
        }
    }

    "sampling request without client handler aborts with McpSamplingRejectedException" in run {
        // No sampling route registered on the client; default handler rejects.
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("s"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    srv.requestSampling(
                        McpServer.SamplingRequest(
                            messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("q"))),
                            maxTokens = 10
                        )
                    )
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(result.isFailure)
                    end for
                }
            }
        }
    }

end McpSamplingReverseTest
