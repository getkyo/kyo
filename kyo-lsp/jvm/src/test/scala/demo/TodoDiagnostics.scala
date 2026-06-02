package demo

import kyo.*
import kyo.LspHandler.Diagnostic
import kyo.LspHandler.DiagnosticSeverity
import kyo.LspHandler.DocumentSymbol
import kyo.LspHandler.DocumentSymbolResult
import kyo.LspHandler.Position
import kyo.LspHandler.PublishDiagnosticsParams
import kyo.LspHandler.Range
import kyo.LspHandler.SymbolKind
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** LSP server for `.todo` files that publishes diagnostics on open / change and serves the
  * document outline via `textDocument/documentSymbol`.
  *
  * Diagnostics surfaced:
  *   - **Unknown keyword** (Warning): a line starts with an alphanumeric token that isn't
  *     `TODO` / `DONE` / `WAIT`.
  *   - **Duplicate entry** (Information): two or more lines have the same keyword + body.
  *   - **Empty body** (Hint): a keyword line has no text after the keyword.
  *
  * Outline:
  *   - One [[DocumentSymbol]] per non-blank line, grouped by keyword via `SymbolKind`
  *     (`TODO` -> `Constant`, `DONE` -> `EnumMember`, `WAIT` -> `Event`, other -> `Variable`).
  *
  * Demonstrates:
  *   - Server-initiated [[LspServer.publishDiagnostics]] fired from `textDocument/didOpen`
  *     and `textDocument/didChange` notification handlers.
  *   - [[LspHandler.TextDocument.documentSymbol]] returning [[DocumentSymbolResult.Symbols]].
  *   - Driving everything off the engine's document registry ([[Lsp.documents]]).
  */
object TodoDiagnostics extends KyoApp:

    private val Keywords = Chunk("TODO", "DONE", "WAIT")

    // Compute diagnostics for a document. Returns one Diagnostic per finding.
    private def analyse(text: String): Chunk[Diagnostic] =
        val builder = scala.collection.mutable.ArrayBuffer.empty[Diagnostic]
        val seen    = scala.collection.mutable.Map.empty[String, Int] // key -> first-occurrence line

        val lines = text.split("\n", -1)
        var i     = 0
        while i < lines.length do
            val raw     = lines(i)
            val trimmed = raw.trim
            if trimmed.nonEmpty then
                // Tokenize the leading word.
                val firstSpace = trimmed.indexWhere(c => c == ' ' || c == '\t')
                val keyword    = if firstSpace < 0 then trimmed else trimmed.substring(0, firstSpace)
                val body       = if firstSpace < 0 then "" else trimmed.substring(firstSpace).trim
                val lineRange  = Range(Position(i, 0), Position(i, raw.length))

                if Keywords.contains(keyword) then
                    if body.isEmpty then
                        builder += Diagnostic(
                            range = lineRange,
                            severity = Present(DiagnosticSeverity.Hint),
                            code = Present("empty-body"),
                            source = Present("todo-lsp"),
                            message = s"$keyword has no body."
                        )
                    end if
                    val dedupeKey = s"$keyword:$body"
                    seen.get(dedupeKey) match
                        case Some(firstLine) =>
                            builder += Diagnostic(
                                range = lineRange,
                                severity = Present(DiagnosticSeverity.Information),
                                code = Present("duplicate"),
                                source = Present("todo-lsp"),
                                message = s"Duplicate of line ${firstLine + 1}: '$keyword $body'."
                            )
                        case None => seen.update(dedupeKey, i)
                    end match
                else
                    builder += Diagnostic(
                        range = lineRange,
                        severity = Present(DiagnosticSeverity.Warning),
                        code = Present("unknown-keyword"),
                        source = Present("todo-lsp"),
                        message =
                            s"Unknown keyword '$keyword'. Expected one of: ${Keywords.mkString(", ")}."
                    )
                end if
            end if
            i += 1
        end while
        Chunk.from(builder.toSeq)
    end analyse

    private def kindOf(keyword: String): SymbolKind = keyword match
        case "TODO" => SymbolKind.Constant
        case "DONE" => SymbolKind.EnumMember
        case "WAIT" => SymbolKind.Event
        case _      => SymbolKind.Variable

    // Build the outline: one DocumentSymbol per non-blank line.
    private def outline(text: String): Chunk[DocumentSymbol] =
        val builder = scala.collection.mutable.ArrayBuffer.empty[DocumentSymbol]
        val lines   = text.split("\n", -1)
        var i       = 0
        while i < lines.length do
            val raw     = lines(i)
            val trimmed = raw.trim
            if trimmed.nonEmpty then
                val firstSpace = trimmed.indexWhere(c => c == ' ' || c == '\t')
                val keyword    = if firstSpace < 0 then trimmed else trimmed.substring(0, firstSpace)
                val body       = if firstSpace < 0 then "" else trimmed.substring(firstSpace).trim
                val range      = Range(Position(i, 0), Position(i, raw.length))
                builder += DocumentSymbol(
                    name = if body.isEmpty then keyword else s"$keyword: $body",
                    detail = Present(s"line ${i + 1}"),
                    kind = kindOf(keyword),
                    range = range,
                    selectionRange = range
                )
            end if
            i += 1
        end while
        Chunk.from(builder.toSeq)
    end outline

    // Read the current document text for a URI and publish diagnostics derived from it. Silently no-ops if the
    // document is not in the registry (e.g. the engine processed a didClose before our handler fired).
    private def publishFor(uri: LspHandler.LspDocument.Uri, version: Maybe[Int])(using Frame): Unit < (Async & Abort[LspException]) =
        Lsp.documents.flatMap(_.get(uri)).flatMap {
            case Present(d) =>
                Lsp.server.flatMap { server =>
                    server.publishDiagnostics(PublishDiagnosticsParams(
                        uri = uri,
                        version = version,
                        diagnostics = analyse(d.text)
                    )).handle(Abort.recover[Closed](_ => ()))
                }
            case Absent => ()
        }

    run {
        val didOpen = LspHandler.TextDocument.didOpen { params =>
            publishFor(params.textDocument.uri, Present(params.textDocument.version))
        }

        val didChange = LspHandler.TextDocument.didChange { params =>
            publishFor(params.textDocument.uri, Present(params.textDocument.version))
        }

        val symbols = LspHandler.TextDocument.documentSymbol { params =>
            Lsp.documents.flatMap(_.get(params.textDocument.uri)).map {
                case Present(doc) => DocumentSymbolResult.Symbols(outline(doc.text))
                case Absent       => DocumentSymbolResult.Symbols(Chunk.empty)
            }
        }

        JsonRpcTransport.contentLengthStdio(java.lang.System.in, java.lang.System.out).map { t =>
            LspServer.initWith(t, didOpen, didChange, symbols) { _ =>
                Async.never
            }
        }
    }
end TodoDiagnostics
