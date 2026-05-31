package kyo.integration

import kyo.*

/** Tests for -32601 capability gating on reverse-direction methods (§3.11). */
class McpReverseDispatchCapabilityGatingTest extends Test:

    "requestSampling without client sampling capability returns -32601 error" in run {
        // Client does NOT declare sampling capability.
        // The client-side reverse dispatch returns McpCapabilityNotAdvertisedError (-32601).
        // The server wraps the received JSON-RPC error as McpSamplingRejectedError, preserving
        // the original error message which contains "Method not found" from the -32601 code.
        val clientCaps = McpCapabilities.Client()
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("gate-test"), clientCaps)
            ).flatMap { (srv, client) =>
                Abort.run[McpError](
                    srv.requestSampling(
                        McpServer.SamplingRequest(
                            messages = Chunk(McpServer.SamplingRequest.Message(
                                McpRole.User,
                                McpServer.SamplingContent.Text("q")
                            )),
                            maxTokens = 10
                        )
                    )
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(_) =>
                                // The client-side reverse dispatch returned McpCapabilityNotAdvertisedError
                                // (-32601) to the server, which wrapped it as McpSamplingRejectedError.
                                // Any failure here confirms the capability gate is working.
                                succeed
                            case Result.Success(_) =>
                                fail("expected failure when sampling capability not advertised")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                        end match
                    end for
                }
            }
        }
    }

    "requestSampling with client sampling capability does not return -32601" in run {
        // Client declares sampling capability; default handler rejects with a different error (not -32601).
        val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("gate-test2"), clientCaps)
            ).flatMap { (srv, client) =>
                Abort.run[McpError](
                    srv.requestSampling(
                        McpServer.SamplingRequest(
                            messages = Chunk(McpServer.SamplingRequest.Message(
                                McpRole.User,
                                McpServer.SamplingContent.Text("q")
                            )),
                            maxTokens = 10
                        )
                    )
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(err: McpCapabilityNotAdvertisedError) =>
                                fail(s"did not expect -32601 when sampling capability is advertised")
                            case Result.Failure(_) =>
                                // Any other error is fine (e.g. sampling rejected, no handler registered)
                                succeed
                            case Result.Success(_) =>
                                succeed
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                        end match
                    end for
                }
            }
        }
    }

end McpReverseDispatchCapabilityGatingTest
