package kyo.readmeExamples

import scala.io.Source

object utils:

    def readSource(path: String, lines: Seq[(Int, Int)]): String =
        def readFile(path: String) =
            try
                Source.fromFile("../" + path)
            catch
                case _: Throwable => Source.fromFile(path)

        if lines.isEmpty then
            val content = readFile(path).getLines().mkString("\n")
            content
        else
            val chunks =
                for
                    (from, to) <- lines
                yield readFile(path)
                    .getLines()
                    .toArray[String]
                    .slice(from - 1, to)
                    .mkString("\n")

            chunks.mkString("\n\n")
        end if
    end readSource

    def printSource(
        path: String,
        lines: Seq[(Int, Int)] = Seq.empty,
        comment: Boolean = true,
        showLineNumbers: Boolean = false
    ) =
        val title     = if comment then s"""title="$path"""" else ""
        val showLines = if showLineNumbers then "showLineNumbers" else ""
        println(s"""```scala""")
        println(readSource(path, lines))
        println("```")
    end printSource
end utils
