package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** LSP server demonstrating the `$/progress` reverse-direction protocol via
  * `workspace/executeCommand`.
  *
  * Exposes one executable command, `todo-indexer.reindex`, that simulates a long-running
  * workspace indexing job. The handler:
  *
  *   1. asks the client to create a fresh progress token via `window/workDoneProgress/create`
  *      ([[LspServer.createWorkDoneProgress]]), unless the client supplied one in
  *      `workDoneToken` (per LSP §6.5);
  *   2. emits a `Begin` notification ([[Lsp.workDoneBegin]]);
  *   3. emits `Report` notifications at 25 %, 50 %, 75 % ([[Lsp.workDoneReport]]);
  *   4. emits `End` ([[Lsp.workDoneEnd]]) and returns a result summary.
  *
  * The `.todo-idx` file extension exists only so the LSP plugin can be associated with a
  * dedicated workspace ; the demo itself does not parse file contents.
  */
object TodoIndexer extends KyoApp:

    case class ReindexResult(scanned: Int) derives Schema, CanEqual

    run {
        val cmdHandler =
            LspHandler.Workspace.executeCommand[ReindexResult] { params =>
                if params.command != "todo-indexer.reindex" then ((Absent: Maybe[ReindexResult]): Maybe[ReindexResult] < Async)
                else
                    for
                        // §6.5: if the client supplied a workDoneToken use it; otherwise ask the
                        // client to create a new progress slot via window/workDoneProgress/create.
                        suppliedToken <- Lsp.workDoneToken
                        token <- suppliedToken match
                            case Present(t) => (t: LspHandler.ProgressToken < Async)
                            case Absent =>
                                val newToken = LspHandler.ProgressToken.StringToken(s"reindex-${java.util.UUID.randomUUID()}")
                                Lsp.server.map(_.createWorkDoneProgress(LspHandler.WorkDoneProgressCreateParams(newToken)))
                                    .map(_ => newToken)
                                    .handle(Abort.recover[LspException](_ => newToken))
                        _ <- Lsp.workDoneBegin(token, title = "Reindexing TODOs", percentage = Present(0))
                        _ <- emitSteps(token, total = 4, idx = 1)
                        _ <- Lsp.workDoneEnd(token, message = Present("done"))
                    yield Present(ReindexResult(scanned = 4))
                    end for
                end if
            }

        JsonRpcTransport.contentLengthStdio(java.lang.System.in, java.lang.System.out).map { t =>
            LspServer.initWith(t, cmdHandler) { _ =>
                Async.never
            }
        }
    }

    private def emitSteps(token: LspHandler.ProgressToken, total: Int, idx: Int)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        if idx > total then ((): Unit)
        else
            for
                _ <- Async.sleep(120.millis)
                pct = (idx.toDouble / total.toDouble * 100).toInt
                _ <- Lsp.workDoneReport(token, message = Present(s"step $idx of $total"), percentage = Present(pct))
                _ <- emitSteps(token, total, idx + 1)
            yield ()
end TodoIndexer
