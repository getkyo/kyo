package demo

import kyo.*

/** BatchRunner: a long-running job MCP server.
  *
  * A single tool, `run-batch`, walks a list of items one at a time. It shows the three things a
  * long-running MCP tool does from inside an `Async` handler:
  *
  *   - **Progress** (`Mcp.progress`): each step emits a progress notification keyed on the
  *     `progressToken` the host supplied, so the host can render a bar.
  *   - **Cooperative cancellation** (`Mcp.cancelled`): the handler checks the cancellation promise
  *     between steps and returns a partial summary instead of being hard-interrupted.
  *   - **Logging** (`Mcp.server.notifyLog`): each step sends a structured log notification.
  *
  * Run as a stdio MCP server: `java -cp <kyo-mcpJVM test classpath> demo.BatchRunner`.
  */
object BatchRunner extends KyoApp:

    case class RunIn(items: Chunk[String], stepMillis: Int = 250) derives Schema, CanEqual
    case class RunSummary(processed: Int, total: Int, cancelled: Boolean) derives Schema, CanEqual

    run {
        val runBatch =
            McpHandler.tool[RunIn]("run-batch", "Process items one by one with progress, logging, and cancellation") { in =>
                val total = in.items.size
                def loop(i: Int): Int < (Async & Abort[McpConnectionClosedException]) =
                    if i >= total then i
                    else
                        Mcp.cancelled.map(_.done).map { isCancelled =>
                            if isCancelled then i
                            else
                                for
                                    server <- Mcp.server
                                    _      <- server.notifyLog(McpServer.LogLevel.Info, s"processing ${in.items(i)} (${i + 1}/$total)")
                                    _      <- Mcp.progress((i + 1).toDouble, Present(total.toDouble), Present(s"${i + 1}/$total"))
                                    _      <- Async.sleep(in.stepMillis.millis)
                                    done   <- loop(i + 1)
                                yield done
                        }
                loop(0).map(processed => RunSummary(processed, total, processed < total))
            }
        JsonRpcTransport.stdio().map(t => McpServer.initWith(t, runBatch)(_ => Async.never))
    }
end BatchRunner
