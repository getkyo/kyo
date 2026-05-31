package kyo.integration

import kyo.*

/** Tests for completion context pass-through (§3.17). */
class McpCompletionContextTest extends Test:

    // AllowUnsafe: AtomicRef for capturing the context received by the completion handler.
    private def makeContextRef =
        AtomicRef.Unsafe.init[Maybe[McpRoute.CompletionArg.Context]](Absent)(using AllowUnsafe.embrace.danger).safe

    "completion handler receives Maybe[CompletionArg.Context] from CompleteParams" in run {
        val capturedContext = makeContextRef
        val ref             = McpRoute.CompletionRef.Prompt("myPrompt")
        val completionRoute = McpRoute.completion(ref) { (_, arg, ctx, _) =>
            Sync.defer {
                capturedContext.unsafe.set(ctx)(using AllowUnsafe.embrace.danger)
                McpRoute.CompletionResult(Chunk(arg.value + "-ok"), Absent, Absent)
            }
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, completionRoute),
                McpClient.initUnscoped(tc, McpInfo("ctx-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.complete(
                    McpRoute.CompletionRef.Prompt("myPrompt"),
                    McpRoute.CompletionArg("arg", "val")
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        // Context is Absent when not explicitly sent (client sends Absent by default).
                        val ctx = capturedContext.unsafe.get()(using AllowUnsafe.embrace.danger)
                        assert(ctx == Absent, s"expected Absent context when client sends default, got $ctx")
                        assert(result.values.size == 1)
                        assert(result.values.head == "val-ok")
                    end for
                }
            }
        }
    }

    "CompletionArg.Context round-trips via Schema" in {
        val ctxObj  = McpRoute.CompletionArg.Context(Map("foo" -> "bar"))
        val encoded = Json.encode[McpRoute.CompletionArg.Context](ctxObj)
        assert(encoded.contains("\"foo\""), s"expected foo in JSON, got: $encoded")
        assert(encoded.contains("\"bar\""), s"expected bar in JSON, got: $encoded")
        val roundTripped = Json.decode[McpRoute.CompletionArg.Context](encoded).getOrThrow
        assert(roundTripped == ctxObj)
    }

end McpCompletionContextTest
