package demo

import kyo.*
import kyo.Maybe.Present

/** MCP server demonstrating long-running tool execution with progress reporting and
  * cooperative cancellation.
  *
  * One tool (`count_slowly`) that:
  *
  *   - emits `notifications/progress` after each step via [[Mcp.progress]];
  *   - polls [[Mcp.cancelled]] between steps so an inbound `notifications/cancelled`
  *     ends the tool early with a typed `LongTaskCancelled` instead of running to completion.
  *
  * No command-line arguments.
  */
object LongTask extends KyoApp:

    /** Count from 1 to `n`, with a `stepMs` delay between steps so a peer has time to cancel. */
    case class CountSlowly(n: Int, stepMs: Maybe[Int] = Present(300)) derives Schema, CanEqual

    case class LongTaskCancelled(stepsCompleted: Int, of: Int) derives Schema, CanEqual

    run {
        val countTool =
            McpHandler.tool[CountSlowly](
                name = "count_slowly",
                description = "Counts from 1 to n with a configurable per-step delay, " +
                    "reporting progress and reacting to cancellation."
            ) { req =>
                val total = req.n.max(0)
                val step  = req.stepMs.getOrElse(300).max(0).min(5_000)
                for
                    cancelP <- Mcp.cancelled
                    out     <- runLoop(target = total, current = 0, stepMs = step, cancelP = cancelP)
                yield out
                end for
            }.error[LongTaskCancelled](code = -32030, message = "long-task-cancelled")

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, countTool) { _ =>
                Async.never
            }
        }
    }

    private def runLoop(
        target: Int,
        current: Int,
        stepMs: Int,
        cancelP: Fiber.Promise[Unit, Sync]
    )(using Frame): McpContent < (Async & Abort[Closed | LongTaskCancelled]) =
        if current >= target then
            Mcp.progress(progress = target.toDouble, total = Present(target.toDouble), message = Present("done"))
                .map(_ => McpContent.text(s"counted to $target"))
        else
            cancelP.poll.map {
                case Present(_) =>
                    Abort.fail(LongTaskCancelled(stepsCompleted = current, of = target))
                case Maybe.Absent =>
                    val next = current + 1
                    Mcp.progress(
                        progress = next.toDouble,
                        total = Present(target.toDouble),
                        message = Present(s"step $next of $target")
                    ).map { _ =>
                        if stepMs > 0 then
                            Async.sleep(stepMs.millis).map(_ => runLoop(target, next, stepMs, cancelP))
                        else runLoop(target, next, stepMs, cancelP)
                    }
            }
end LongTask
