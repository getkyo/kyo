package kyo2

object Ansi:

    extension (str: String)
        def black: String   = s"\u001b[30m$str\u001b[0m"
        def red: String     = s"\u001b[31m$str\u001b[0m"
        def green: String   = s"\u001b[32m$str\u001b[0m"
        def yellow: String  = s"\u001b[33m$str\u001b[0m"
        def blue: String    = s"\u001b[34m$str\u001b[0m"
        def magenta: String = s"\u001b[35m$str\u001b[0m"
        def cyan: String    = s"\u001b[36m$str\u001b[0m"
        def white: String   = s"\u001b[37m$str\u001b[0m"
        def grey: String    = s"\u001b[90m$str\u001b[0m"

        def bold: String      = s"\u001b[1m$str\u001b[0m"
        def dim: String       = s"\u001b[2m$str\u001b[0m"
        def italic: String    = s"\u001b[3m$str\u001b[0m"
        def underline: String = s"\u001b[4m$str\u001b[0m"

        def stripAnsi: String = str.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "")
    end extension

    object highlight:
        def apply(header: String, code: String, trailer: String, startLine: Int = 1): String =
            val headerLines  = if header.nonEmpty then header.split("\n") else Array.empty[String]
            val codeLines    = code.split("\n").dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
            val trailerLines = if trailer.nonEmpty then trailer.split("\n") else Array.empty[String]

            val allLines        = headerLines ++ codeLines ++ trailerLines
            val toDrop          = codeLines.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
            val lineNumberWidth = (startLine + codeLines.length - 1).toString.length
            val separator       = "│".dim

            val processedLines = allLines.zipWithIndex.map { case (line, index) =>
                val isHeader  = index < headerLines.length
                val isTrailer = index >= (headerLines.length + codeLines.length)
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
        end apply

        def apply(code: String): String = apply("", code, "", 1)

        private def highlightLine(line: String): String =
            if line.trim.startsWith("//") then
                line.green // Make entire line green for single-line comments
            else
                line.split("\\b")
                    .map { token =>
                        if keywords.contains(token) then token.yellow
                        else if token.matches("\".*\"") then token.green
                        else if token.matches("/\\*.*\\*/") then token.green // Inline multi-line comments
                        else if token.matches("[0-9]+") then token.cyan
                        else token
                    }
                    .mkString
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
