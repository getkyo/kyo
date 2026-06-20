package kyo.integration

import kyo.*

/** Integration test: prompt list and get roundtrip. */
class McpPromptListGetTest extends Test:

    private val promptRoute = McpHandler.prompt(
        name = "greet",
        description = "greet a user",
        arguments = Chunk(McpHandler.PromptArgument("name", Absent, required = true))
    ) { args =>
        McpHandler.PromptOutcome(
            description = Absent,
            messages = Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text(s"hello ${args("name")}")))
        )
    }

    "listPrompts returns page with the registered prompt" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
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

    "getPrompt returns result with correct message content" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, promptRoute),
                McpClient.initUnscoped(tc, McpInfo("p"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.getPrompt("greet", Map("name" -> "world")).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.messages.size == 1)
                        assert(result.messages.head.role == McpContent.Role.User)
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
