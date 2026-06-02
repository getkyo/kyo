package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** A tiny LSP server for `.todo` files.
  *
  * `.todo` syntax (one entry per line):
  *
  *   - `TODO Buy milk`
  *   - `DONE Walk the dog`
  *   - `WAIT Review PR`
  *
  * Features:
  *   - `textDocument/hover`: hovering over a TODO keyword shows the current count of each state
  *     in the file.
  *   - `textDocument/completion`: completion at line-start offers `TODO`, `DONE`, `WAIT` keywords
  *     (typed-prefix filtering).
  *
  * The file contents come from the engine's built-in document registry (driven by
  * textDocument/didOpen / didChange that the engine handles internally).
  *
  * Uses Content-Length stdio framing per the LSP spec. Drive via an LSP client.
  */
object TodoLsp extends KyoApp:

    private val Keywords = Chunk("TODO", "DONE", "WAIT")

    private def countStates(text: String): Map[String, Int] =
        val tally = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
        for line <- text.linesIterator do
            val trimmed = line.trim
            for kw <- Keywords if trimmed.startsWith(kw) do
                tally(kw) = tally(kw) + 1
        end for
        tally.toMap
    end countStates

    run {
        val hoverHandler =
            LspHandler.TextDocument.hover { params =>
                Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
                    case Present(doc) =>
                        val counts = countStates(doc.text)
                        val body =
                            if counts.isEmpty then "No TODO/DONE/WAIT entries in this file."
                            else Keywords.map(k => s"$k: ${counts.getOrElse(k, 0)}").mkString("  ")
                        Present(LspHandler.Hover(
                            contents = LspHandler.HoverContents.Markup(
                                LspHandler.MarkupContent(LspHandler.MarkupKind.PlainText, body)
                            ),
                            range = Absent
                        ))
                    case Absent => Absent
                }
            }

        val completionHandler =
            LspHandler.TextDocument.completion { params =>
                Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
                    case Present(doc) =>
                        val line = params.position.line
                        val ch   = params.position.character
                        val cur =
                            doc.text.linesIterator.slice(line, line + 1).nextOption().getOrElse("")
                        val prefix = cur.take(ch).reverse.takeWhile(_.isLetterOrDigit).reverse.toUpperCase
                        val items =
                            Keywords
                                .filter(_.startsWith(prefix))
                                .map(k =>
                                    LspHandler.CompletionItem(
                                        label = k,
                                        kind = Present(LspHandler.CompletionItemKind.Keyword),
                                        detail = Present(s"insert $k state")
                                    )
                                )
                        LspHandler.CompletionResult.Items(items)
                    case Absent =>
                        LspHandler.CompletionResult.Items(Chunk.empty)
                }
            }

        JsonRpcTransport.contentLengthStdio(java.lang.System.in, java.lang.System.out).map { t =>
            LspServer.initWith(t, hoverHandler, completionHandler) { _ =>
                Async.never
            }
        }
    }
end TodoLsp
