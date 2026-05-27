package kyo.doctest.internal

import kyo.*

/** Tests for ErrorReporter covering formatting of MappedDiagnostics and Failures. */
class ErrorReporterTest extends Test:

    // Helper: builds a PositionMap.MappedDiagnostic inline.
    private def makeDiagnostic(
        file: String = "kyo-data/README.md",
        lineStart: Int = 40,
        lineEnd: Int = 45,
        body: String = "val x: Int = \"hello\"\nval y = 2",
        readmeLine: Int = 42,
        col: Int = 5,
        severity: Driver.Diagnostic.Severity = Driver.Diagnostic.Severity.Error,
        message: String = "type mismatch",
        carrier: Block.Carrier = Block.Carrier.Visible
    ): PositionMap.MappedDiagnostic =
        val block = Block(
            file = kyo.Path(file),
            lineStart = lineStart,
            lineEnd = lineEnd,
            body = body,
            visibility = Block.Visibility.Isolated,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM),
            carrier = carrier
        )
        PositionMap.MappedDiagnostic(
            severity = severity,
            block = block,
            readmeLine = readmeLine,
            col = col,
            message = message,
            carrier = carrier
        )
    end makeDiagnostic

    // A small source content string with multiple lines for context extraction.
    // Line 42 (1-indexed) is "val x: Int = \"hello\""
    private val sampleSource: String =
        (1 to 50).map(i => if i == 42 then "val x: Int = \"hello\"" else s"// line $i").mkString("\n")

    "render of a single error produces README.md:LINE:COL and error: label" in run {
        val d      = makeDiagnostic(readmeLine = 42, col = 5)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        assert(result.contains("kyo-data/README.md:42:5"), s"expected location header in: $result")
        assert(result.contains("error:"), s"expected 'error:' label in: $result")
        assert(result.contains("type mismatch"), s"expected message in: $result")
        succeed
    }

    "two errors in the same block each produce their own location header" in run {
        val d1 = makeDiagnostic(readmeLine = 42, col = 5, message = "type mismatch")
        val d2 = makeDiagnostic(readmeLine = 43, col = 1, message = "value not found")

        val r1 = ErrorReporter.renderDiagnostic(d1, sampleSource, useAnsi = false)
        val r2 = ErrorReporter.renderDiagnostic(d2, sampleSource, useAnsi = false)

        assert(r1.contains("kyo-data/README.md:42:5"), s"first diagnostic missing location: $r1")
        assert(r1.contains("type mismatch"), s"first diagnostic missing message: $r1")
        assert(r2.contains("kyo-data/README.md:43:1"), s"second diagnostic missing location: $r2")
        assert(r2.contains("value not found"), s"second diagnostic missing message: $r2")
        succeed
    }

    "warning diagnostic produces warning: label" in run {
        val d      = makeDiagnostic(severity = Driver.Diagnostic.Severity.Warning, message = "unused import")
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        assert(result.contains("warning:"), s"expected 'warning:' in: $result")
        assert(result.contains("unused import"), s"expected message in: $result")
        // Must NOT contain "error:"
        assert(!result.contains("error:"), s"must not contain 'error:' for warning: $result")
        succeed
    }

    "useAnsi=true produces ANSI escape codes" in run {
        val d      = makeDiagnostic(severity = Driver.Diagnostic.Severity.Error)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = true)

        // ANSI escape sequences start with ESC (0x1b) followed by '['.
        val hasAnsi = result.contains("[")
        assert(hasAnsi, s"expected ANSI escape codes in: ${result.replace("", "ESC")}")
        succeed
    }

    "useAnsi=false produces no ANSI escape codes" in run {
        val d      = makeDiagnostic(severity = Driver.Diagnostic.Severity.Error)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        assert(!result.contains("["), s"expected no ANSI codes in: $result")
        succeed
    }

    "Visible carrier produces no carrier prefix" in run {
        val d      = makeDiagnostic(carrier = Block.Carrier.Visible, readmeLine = 42, col = 5)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        assert(!result.contains("(<!--"), s"Visible carrier must not have comment prefix: $result")
        assert(result.contains("kyo-data/README.md:42:5: error:"), s"expected clean location:severity in: $result")
        succeed
    }

    // Additional: Hidden carrier produces "(<!-- hidden -->)" prefix.
    "Hidden carrier produces (<!-- hidden -->) prefix" in run {
        val d      = makeDiagnostic(carrier = Block.Carrier.Hidden)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        assert(result.contains("(<!-- hidden -->)"), s"expected hidden prefix in: $result")
        succeed
    }

    // Additional: caret indicator is placed under the offending column.
    "caret indicator is placed under the offending column" in run {
        val d      = makeDiagnostic(readmeLine = 42, col = 5, carrier = Block.Carrier.Visible)
        val result = ErrorReporter.renderDiagnostic(d, sampleSource, useAnsi = false)

        // The caret line should have 4 (indent) + 4 (col-1) = 8 spaces before '^'.
        val lines     = result.split("\n")
        val caretLine = lines.find(_.contains("^"))
        assert(caretLine.isDefined, s"no caret line found in: $result")
        val cl = caretLine.get
        // 4 spaces indent + (col-1)=4 spaces = 8 spaces before '^'
        assert(cl.startsWith("        ^"), s"caret not at column 5 (8 leading spaces): '$cl'")
        succeed
    }

end ErrorReporterTest
