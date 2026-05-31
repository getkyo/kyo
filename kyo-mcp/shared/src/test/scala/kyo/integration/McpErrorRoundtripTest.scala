package kyo.integration

import kyo.*

/** Integration test: McpError propagates across the wire to the client (T-021, INV-003, INV-005).
  *
  * Server-side handler errors reach the client as McpError failures. The exact leaf type
  * depends on how McpClientEngine.callToolUnsafe wraps JsonRpcError responses; the wire
  * round-trip guarantees that the client call aborts with some McpError. Message content
  * carries the original error description. Wire-encoding asymmetry (McpClientEngine converts
  * all JsonRpcError to McpInvalidArgumentError) is a known limitation documented in decisions.md.
  */
class McpErrorRoundtripTest extends Test:

    case class FailReq(name: String) derives Schema, CanEqual

    private val failUri = McpResourceUri.parse("file:///fail").get

    "tool call aborts with McpError when server handler fails (T-021, INV-003)" in run {
        val errRoute = McpRoute.tool[FailReq]("fail") { (_, _) =>
            Abort.fail(McpToolExecutionError(tool = "fail", reason = "intentional", cause = ""))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, errRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpError](client.unsafe.callToolUnsafe[FailReq]("fail", FailReq("x"))).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(err) =>
                                assert(err.message.contains("intentional") || err.message.contains("fail"))
                            case _ => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

    "resource read aborts with McpError when server handler fails (T-021, INV-003)" in run {
        val resRoute = McpRoute.resource(failUri, "fail-resource") { (uri, _) =>
            Abort.fail(McpResourceReadError(uri = uri, reason = "read error", cause = ""))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpError](client.readResource(failUri)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(err) =>
                                assert(err.message.nonEmpty)
                            case _ => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

    "tool call for non-existent tool aborts with McpError code -32602 (T-021, INV-003)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpError](client.unsafe.callToolUnsafe[FailReq]("nonexistent", FailReq("x"))).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(err) => assert(err.code == -32602)
                            case _                   => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

end McpErrorRoundtripTest
