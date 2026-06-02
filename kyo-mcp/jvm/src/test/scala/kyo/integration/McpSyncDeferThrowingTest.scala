package kyo.integration

import kyo.*

/** Integration test for `Sync.defer { javaCallThatThrows() }` semantics.
  *
  * Validates that an uncaught Java throwable inside `Sync.defer` propagates as a panic at the
  * dispatch boundary, surfacing on the wire as `JsonRpcInternalError` (-32603). The fix-pattern
  * for users is `Abort.catching[IOException](Sync.defer(...))` which converts the throwable into
  * a typed Abort row.
  *
  * JVM-only because `java.nio.file.Files.readString` is JVM API.
  */
class McpSyncDeferThrowingTest extends Test:

    case class ReadReq(path: String) derives Schema, CanEqual

    "uncaught throwable in Sync.defer surfaces as panic (-32603 InternalError)" in run {
        val throwingTool = McpHandler.tool[ReadReq](name = "read", description = "Read a file") { req =>
            Sync.defer(java.nio.file.Files.readString(java.nio.file.Paths.get(req.path)))
                .map(s => McpContent.text(s))
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, throwingTool),
                McpClient.initUnscoped(tc, McpInfo("t"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.callTool[ReadReq]("read", ReadReq("/no/such/file/anywhere"))
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(err) =>
                                assert(err.code == -32603, s"expected -32603 InternalError, got ${err.code}")
                            case _ => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

    "Abort.catching[IOException] converts throwable into typed Abort row" in run {
        case class FileNotFound(path: String) derives Schema, CanEqual

        val catchingTool = McpHandler
            .tool[ReadReq](name = "read", description = "Read a file") { req =>
                Abort.catching[java.io.IOException](
                    Sync.defer(java.nio.file.Files.readString(java.nio.file.Paths.get(req.path)))
                )
                    .map(s => McpContent.text(s))
                    .handle(Abort.recover[java.io.IOException] { _ => Abort.fail(FileNotFound(req.path)) })
            }
            .error[FileNotFound](code = -32010, message = "file-not-found")

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, catchingTool),
                McpClient.initUnscoped(tc, McpInfo("t"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.callTool[ReadReq]("read", ReadReq("/no/such/file/anywhere"))
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(err) =>
                                assert(err.code == -32010, s"expected -32010 file-not-found, got ${err.code}")
                                assert(
                                    err.message.contains("file-not-found"),
                                    s"expected 'file-not-found' in message, got '${err.message}'"
                                )
                            case _ => fail(s"expected Failure, got $result")
                        end match
                    end for
                }
            }
        }
    }

end McpSyncDeferThrowingTest
