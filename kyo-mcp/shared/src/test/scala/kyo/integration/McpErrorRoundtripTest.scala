package kyo.integration

import kyo.*

/** Integration test: McpException propagates across the wire to the client (T-021, INV-003, INV-005).
  *
  * Server-side handler errors reach the client as McpException failures. The exact leaf type
  * depends on how McpClientEngine.callToolUnsafe wraps JsonRpcError responses; the wire
  * round-trip guarantees that the client call aborts with some McpException. Message content
  * carries the original error description. Wire-encoding asymmetry (McpClientEngine converts
  * all JsonRpcError to McpInvalidArgumentException) is a known limitation documented in decisions.md.
  */
class McpErrorRoundtripTest extends Test:

    case class FailReq(name: String) derives Schema, CanEqual

    private val failUri = McpResourceUri.parse("file:///fail").get

    "tool call aborts with McpException when server handler fails (T-021, INV-003)" in run {
        val errRoute = McpRoute.tool[FailReq]("fail").handler { _ =>
            Abort.fail(McpToolExecutionException(tool = "fail", reason = "intentional", cause = ""))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, errRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.callTool[FailReq]("fail", FailReq("x"))).flatMap { result =>
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

    "resource read aborts with McpException when server handler fails (T-021, INV-003)" in run {
        val resRoute = McpRoute.resource(failUri, "fail-resource").handler { uri =>
            Abort.fail(McpResourceReadException(uri = uri, reason = "read error", cause = ""))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.readResource(failUri)).flatMap { result =>
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

    "tool call for non-existent tool aborts with McpException code -32602 (T-021, INV-003)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.callTool[FailReq]("nonexistent", FailReq("x"))).flatMap { result =>
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
