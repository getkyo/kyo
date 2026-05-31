package kyo.integration

import kyo.*

/** Integration test: prompt list and get roundtrip (T-017, INV-023). */
class McpPromptListGetTest extends Test:

    private val promptRoute = McpRoute.prompt(
        name = "greet",
        description = "greet a user",
        arguments = Chunk(McpRoute.PromptArgument("name", Absent, required = true))
    ) { (args, _) =>
        McpRoute.PromptGetResult(
            description = Absent,
            messages = Chunk(McpRoute.PromptMessage(McpRole.User, McpContent.text(s"hello ${args("name")}")))
        )
    }

    "listPrompts returns page with the registered prompt (T-017, INV-023)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, promptRoute),
                McpClient.initUnscoped(tc, McpInfo("p"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.listPrompts().flatMap { page =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(page.items.size == 1)
                        assert(page.items.head.name == "greet")
                        assert(page.nextCursor == Absent)
                    end for
                }
            }
        }
    }

    "getPrompt returns result with correct message content (T-017)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, promptRoute),
                McpClient.initUnscoped(tc, McpInfo("p"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.getPrompt("greet", Map("name" -> "world")).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.messages.size == 1)
                        assert(result.messages.head.role == McpRole.User)
                        result.messages.head.content match
                            case McpContent.Text(text, _) => assert(text == "hello world")
                            case other                    => fail(s"unexpected content: $other")
                        end match
                    end for
                }
            }
        }
    }

end McpPromptListGetTest
