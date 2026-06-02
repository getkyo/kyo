package kyo.integration

import kyo.*

/** Integration test: user-defined error types declared via `.error[E2]` round-trip on the wire.
  *
  * Validates the FR-009 fix: handler closures can `Abort.fail` with arbitrary user-defined
  * error types, the typed mapping installs the wire code + message, and the client receives
  * an `McpException` carrying both.
  */
class McpUserDefinedErrorTest extends Test:

    case class FsError(reason: String, path: String) derives Schema, CanEqual
    case class ReadReq(path: String) derives Schema, CanEqual

    "tool handler aborts with user-defined error type via .error[E2] (FR-009 fix)" in run {
        val readTool = McpHandler
            .tool[ReadReq](name = "read", description = "Read a file") { req =>
                if req.path.startsWith("/") then
                    Abort.fail(FsError(reason = "absolute paths rejected", path = req.path))
                else
                    McpContent.text(s"contents of ${req.path}")
            }
            .error[FsError](code = -32001, message = "filesystem-error")

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, readTool),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.callTool[ReadReq]("read", ReadReq("/etc/passwd"))
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(err) =>
                                assert(err.code == -32001, s"expected code -32001, got ${err.code}")
                                assert(
                                    err.message.contains("filesystem-error"),
                                    s"expected message to contain 'filesystem-error', got '${err.message}'"
                                )
                            case _ => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

    "tool handler happy path still works alongside .error[E2]" in run {
        val readTool = McpHandler
            .tool[ReadReq](name = "read", description = "Read a file") { req =>
                if req.path.startsWith("/") then
                    Abort.fail(FsError(reason = "absolute paths rejected", path = req.path))
                else
                    McpContent.text(s"contents of ${req.path}")
            }
            .error[FsError](code = -32001, message = "filesystem-error")

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, readTool),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callTool[ReadReq]("read", ReadReq("hello.txt")).flatMap { outcome =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(outcome.content.nonEmpty, "expected at least one content block")
                        outcome.content.head match
                            case t: McpContent.Text =>
                                assert(t.text.contains("contents of hello.txt"))
                            case other => fail(s"expected Text content, got $other")
                        end match
                    end for
                }
            }
        }
    }

end McpUserDefinedErrorTest
