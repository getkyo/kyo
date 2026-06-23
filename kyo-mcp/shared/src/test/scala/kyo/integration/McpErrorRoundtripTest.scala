package kyo.integration

import kyo.*

/** Integration test: McpException propagates across the wire to the client.
  *
  * Server-side handler errors reach the client as McpException failures. JsonRpcError responses
  * from the wire are preserved as McpRemoteApplicationException with the original code/message/data
  * triple (see McpClientEngine), so the caller can pattern-match by `code` to discriminate.
  */
class McpErrorRoundtripTest extends Test:

    case class FailReq(name: String) derives Schema, CanEqual

    private val failUri = McpResourceUri.parse("file:///fail").get

    "tool call aborts with McpException when server handler fails" in {
        val errRoute = McpHandler.toolRaw[FailReq]("fail") { _ =>
            Abort.fail(McpToolExecutionException(tool = "fail", reason = "intentional", cause = Absent))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, errRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.callToolRaw[FailReq]("fail", FailReq("x"))).flatMap { result =>
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

    "resource read aborts with McpException when server handler fails" in {
        val resRoute = McpHandler.resource(failUri, "fail-resource") {
            Abort.fail(McpResourceReadException(uri = failUri, reason = "read error", cause = Absent))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.readResourceRaw(failUri)).flatMap { result =>
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

    "tool call for non-existent tool aborts with McpException code -32601" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("e"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.callToolRaw[FailReq]("nonexistent", FailReq("x"))).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(err) => assert(err.code == -32601)
                            case _                   => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

end McpErrorRoundtripTest
