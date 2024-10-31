package kyo

import scala.util.control.NonFatal

/** Provides ANSI color and formatting utilities for strings.
  */
object Ansi:

    extension (str: String)
        /** Applies black color to the string. */
        def black: String = s"\u001b[30m$str\u001b[0m"

        /** Applies red color to the string. */
        def red: String = s"\u001b[31m$str\u001b[0m"

        /** Applies green color to the string. */
        def green: String = s"\u001b[32m$str\u001b[0m"

        /** Applies yellow color to the string. */
        def yellow: String = s"\u001b[33m$str\u001b[0m"

        /** Applies blue color to the string. */
        def blue: String = s"\u001b[34m$str\u001b[0m"

        /** Applies magenta color to the string. */
        def magenta: String = s"\u001b[35m$str\u001b[0m"

        /** Applies cyan color to the string. */
        def cyan: String = s"\u001b[36m$str\u001b[0m"

        /** Applies white color to the string. */
        def white: String = s"\u001b[37m$str\u001b[0m"

        /** Applies grey color to the string. */
        def grey: String = s"\u001b[90m$str\u001b[0m"

        /** Applies bold formatting to the string. */
        def bold: String = s"\u001b[1m$str\u001b[0m"

        /** Applies dim formatting to the string. */
        def dim: String = s"\u001b[2m$str\u001b[0m"

        /** Applies italic formatting to the string. */
        def italic: String = s"\u001b[3m$str\u001b[0m"

        /** Applies underline formatting to the string. */
        def underline: String = s"\u001b[4m$str\u001b[0m"

        /** Removes all ANSI escape sequences from the string. */
        def stripAnsi: String = str.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "")
    end extension

    object highlight:

        /** Applies syntax highlighting to a code snippet with optional header and trailer.
          *
          * @param header
          *   The header text to be displayed before the code (optional).
          * @param code
          *   The main code snippet to be highlighted.
          * @param trailer
          *   The trailer text to be displayed after the code (optional).
          * @param startLine
          *   The starting line number for the code snippet (default is 1).
          * @return
          *   A string with the highlighted code, including line numbers and formatting.
          */
        def apply(header: String, code: String, trailer: String, startLine: Int = 1): String =
            try
                val separatorLine = "─".repeat(30).dim
                val headerLines   = if header.nonEmpty then Array(separatorLine) ++ header.split("\n") else Array.empty[String]
                val codeLines     = code.split("\n").dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
                val trailerLines  = if trailer.nonEmpty then trailer.split("\n") ++ Array(separatorLine) else Array.empty[String]

                val toDrop          = codeLines.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
                val lineNumberWidth = (startLine + codeLines.length).toString.length
                val separator       = "│".dim
                val allLines        = headerLines ++ Array(separatorLine) ++ codeLines ++ Array(separatorLine) ++ trailerLines

                val processedLines = allLines.zipWithIndex.map { case (line, index) =>
                    val isHeader  = index <= headerLines.length
                    val isTrailer = index > (headerLines.length + codeLines.length)
                    val lineNumber =
                        if isHeader || isTrailer then
                            " ".repeat(lineNumberWidth)
                        else
                            (startLine + index - headerLines.length).toString.padTo(lineNumberWidth, ' ')

                    val highlightedLine =
                        if isHeader || isTrailer then
                            line.green
                        else
                            highlightLine(line.drop(toDrop))

                    s"${lineNumber.dim} $separator $highlightedLine"
                }

                processedLines.mkString("\n")
            catch
                case ex if NonFatal(ex) =>
                    (header :: code :: trailer :: Nil).mkString("\n")
        end apply

        /** Applies syntax highlighting to a code snippet without header or trailer.
          *
          * @param code
          *   The code snippet to be highlighted.
          * @return
          *   A string with the highlighted code, including line numbers and formatting.
          */
        def apply(code: String): String = apply("", code, "", 1)

        private def highlightLine(line: String): String =
            if line.trim.startsWith("//") then
                line.green // Make entire line green for single-line comments
            else
                line.split(" ").map { token =>
                    if keywords.contains(token) then token.yellow
                    else if token.matches("\".*\"") then token.green
                    else if token.matches("/\\*.*\\*/") then token.green // Inline multi-line comments
                    else if token.matches("[0-9]+") then token.cyan
                    else token
                }.mkString(" ")
        end highlightLine

        private val keywords = Set(
            "abstract",
            "case",
            "catch",
            "class",
            "def",
            "do",
            "else",
            "enum",
            "export",
            "extends",
            "final",
            "finally",
            "for",
            "given",
            "if",
            "implicit",
            "import",
            "lazy",
            "match",
            "new",
            "object",
            "override",
            "package",
            "private",
            "protected",
            "return",
            "sealed",
            "super",
            "throw",
            "trait",
            "try",
            "type",
            "val",
            "var",
            "while",
            "with",
            "yield"
        )
    end highlight
end Ansi
