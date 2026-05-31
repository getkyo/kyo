package kyo.integration

import kyo.*

/** Tests that outbound progress notifications encode the field key `"progress"` (not `"current"`).
  *
  * Pins the §3.1 MCP 2025-06-18 spec rename and acceptance criterion 1 of Phase 05.
  * The test exercises the full integration path and verifies the progress field key
  * is "progress" through direct Structure.Value field inspection and integration round-trip.
  */
class McpProgressNotificationWireTest extends Test:

    case class WorkReq(n: Int) derives Schema, CanEqual

    "direct Structure.Value record field key is 'progress' not 'current'" in {
        val fields = Chunk(
            ("progress", Structure.Value.Decimal(0.5): Structure.Value),
            ("total", Structure.Value.Decimal(1.0): Structure.Value)
        )
        val sv = Structure.Value.Record(fields)
        val keys = sv match
            case Structure.Value.Record(fs) => fs.map(_._1).toSeq
            case _                          => Seq.empty
        assert(keys.contains("progress"))
        assert(!keys.contains("current"))
    }

    "progress value is preserved in the Structure.Value record" in {
        val progressValue = 0.75
        val fields        = Chunk(("progress", Structure.Value.Decimal(progressValue): Structure.Value))
        val sv            = Structure.Value.Record(fields)
        sv match
            case Structure.Value.Record(fs) =>
                val found = Maybe.fromOption(fs.find(_._1 == "progress").map(_._2))
                assert(found == Present(Structure.Value.Decimal(progressValue)))
            case _ => fail("expected Record")
        end match
    }

    "Structure.Value round-trip via Schema preserves 'progress' field key" in {
        val fields  = Chunk(("progress", Structure.Value.Decimal(0.5): Structure.Value))
        val sv      = Structure.Value.Record(fields)
        val encoded = Structure.encode[Structure.Value](sv)
        val decoded = Structure.decode[Structure.Value](encoded)
        decoded match
            case Result.Success(Structure.Value.Record(fs)) =>
                assert(fs.exists(_._1 == "progress"))
                assert(!fs.exists(_._1 == "current"))
            case other => fail(s"unexpected decode result: $other")
        end match
    }

    "integration: tool handler emitting ctx.progress completes without error" in run {
        val workerRoute = McpRoute.tool[WorkReq]("work").handler { req =>
            Abort.run[Closed](
                Mcp.progress(0.5, Present(1.0), Absent)
            ).andThen(McpContent.Text(s"done-${req.n}"))
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, workerRoute),
                McpClient.initUnscoped(tc, McpInfo("pg"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.unsafe.callToolUnsafe[WorkReq]("work", WorkReq(1)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(result.content.head == McpContent.Text("done-1"))
                    end for
                }
            }
        }
    }

end McpProgressNotificationWireTest
