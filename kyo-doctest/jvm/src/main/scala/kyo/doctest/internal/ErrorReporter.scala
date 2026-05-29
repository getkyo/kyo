package kyo.doctest.internal

import kyo.*
import kyo.Ansi.*
import kyo.doctest.*

/** Formats PositionMap.MappedDiagnostic values and Doctest.Failure values as human-readable strings.
  *
  * Output format per diagnostic:
  * {{{
  * <file>:<line>:<col>[<carrierPrefix>]: <severity>: <message>
  *     <contextLine>
  *     ^
  * }}}
  *
  * Severity coloring (useAnsi = true): Error -> red, Warning -> yellow, Info -> cyan. When useAnsi = false all text is plain.
  *
  * Hidden-block prefixes:
  *   - Block.Carrier.Visible: no prefix
  *   - Block.Carrier.Hidden: ` (<!-- hidden -->)`
  */
private[kyo] object ErrorReporter:

    /** Renders a Doctest.Failure as a human-readable string.
      *
      * The failure message is already a pre-formatted string (set by the Orchestrator); this method emits it with the block location
      * header.
      */
    def render(failure: Doctest.Failure, useAnsi: Boolean = true): String =
        val severityLabel = colorize("error", Driver.Diagnostic.Severity.Error, useAnsi)
        s"${failure.file}:${failure.line}:1: $severityLabel: ${failure.message}"
    end render

    /** Renders a single PositionMap.MappedDiagnostic with optional context lines from the README source.
      *
      * @param d
      *   The mapped diagnostic to render.
      * @param sourceContent
      *   The full text of the Markdown source file (or the block body as a fallback). Used to extract context lines.
      * @param useAnsi
      *   When true, severity labels are colored with ANSI codes. When false, plain text only.
      */
    def renderDiagnostic(d: PositionMap.MappedDiagnostic, sourceContent: String, useAnsi: Boolean = true): String =
        val carrierPrefix = carrierPrefixFor(d.carrier)
        val locationPart  = s"${d.block.file}:${d.readmeLine}:${d.col}$carrierPrefix"
        val severityLabel = colorize(severityName(d.severity), d.severity, useAnsi)
        val headerLine    = s"$locationPart: $severityLabel: ${d.message}"

        val contextLines = extractContextLines(sourceContent, d.readmeLine, d.col)
        val parts        = headerLine :: contextLines
        parts.mkString("\n")
    end renderDiagnostic

    /** Renders all failures in a Chunk, concatenating per-failure renderings separated by blank lines.
      *
      * Uses a StringBuilder for O(N) performance rather than O(N^2) string concatenation.
      *
      * @param failures
      *   The failures to render.
      * @param useAnsi
      *   When true, severity labels are colored with ANSI codes. When false, plain text only.
      */
    def renderAll(failures: Chunk[Doctest.Failure], useAnsi: Boolean = true): String =
        val sb = new StringBuilder
        failures.toSeq.zipWithIndex.foreach { case (failure, idx) =>
            if idx > 0 then sb.append("\n\n")
            sb.append(render(failure, useAnsi))
        }
        sb.toString
    end renderAll

    // v0.2: related diagnostic rendering
    private def renderRelated(related: Chunk[Driver.Diagnostic], posMap: PositionMap): String = ""

    /** Returns the carrier-specific prefix appended after the column position and before `: severity:`. */
    private def carrierPrefixFor(carrier: Block.Carrier): String = carrier match
        case Block.Carrier.Visible => ""
        case Block.Carrier.Hidden  => " (<!-- hidden -->)"

    /** Applies ANSI color to the severity label when useAnsi is true. */
    private def colorize(label: String, severity: Driver.Diagnostic.Severity, useAnsi: Boolean): String =
        if !useAnsi then label
        else
            severity match
                case Driver.Diagnostic.Severity.Error   => label.red
                case Driver.Diagnostic.Severity.Warning => label.yellow
                case Driver.Diagnostic.Severity.Info    => label.cyan

    /** Returns the lowercase severity name. */
    private def severityName(severity: Driver.Diagnostic.Severity): String = severity match
        case Driver.Diagnostic.Severity.Error   => "error"
        case Driver.Diagnostic.Severity.Warning => "warning"
        case Driver.Diagnostic.Severity.Info    => "info"

    /** Extracts up to 2 context lines from the source content around the target line, plus a caret indicator.
      *
      * Lines are 1-indexed. Returns a list of indented context strings and a caret line.
      */
    private def extractContextLines(sourceContent: String, targetLine: Int, col: Int): List[String] =
        val allLines = sourceContent.split("\n", -1)
        val lineIdx  = targetLine - 1 // 0-indexed

        val offendingLineOpt =
            if lineIdx >= 0 && lineIdx < allLines.length then Some(allLines(lineIdx))
            else None

        offendingLineOpt match
            case None => Nil
            case Some(offendingLine) =>
                val indented    = "    " + offendingLine
                val caretOffset = 4 + math.max(0, col - 1)
                val caretLine   = " " * caretOffset + "^"
                List(indented, caretLine)
        end match
    end extractContextLines

end ErrorReporter
