package kyo.internal

import kyo.*

private[kyo] object YamlDocuments:

    def split(input: String): Chunk[String] =
        val docs          = scala.collection.mutable.ListBuffer.empty[String]
        val current       = new StringBuilder
        var sawDocument   = false
        var afterDocument = false

        input.linesIterator.foreach { line =>
            marker(line) match
                case Present("...") =>
                    docs += current.toString
                    current.clear()
                    sawDocument = true
                    afterDocument = true
                case Present("---") =>
                    if sawDocument && !afterDocument then docs += current.toString
                    else if !sawDocument && current.toString.trim.nonEmpty then docs += current.toString
                    current.clear()
                    sawDocument = true
                    afterDocument = false
                case _ if afterDocument && ignorableBetweenDocuments(line) =>
                    ()
                case _ if current.isEmpty && !sawDocument && directive(line) =>
                    ()
                case _ =>
                    val _ = current.append(line).append('\n')
                    afterDocument = false
            end match
        }

        if !afterDocument && (current.toString.trim.nonEmpty || sawDocument) then docs += current.toString
        Chunk.from(docs.toList)
    end split

    private def marker(line: String): Maybe[String] =
        if line.startsWith("---") && separated(line, 3) then Maybe("---")
        else if line.startsWith("...") && separated(line, 3) then Maybe("...")
        else Absent
    end marker

    private def separated(line: String, idx: Int): Boolean =
        idx >= line.length || line.charAt(idx).isWhitespace || line.charAt(idx) == '#'

    private def directive(line: String): Boolean =
        line.startsWith("%")

    private def ignorableBetweenDocuments(line: String): Boolean =
        val trimmed = line.trim
        trimmed.isEmpty || trimmed.startsWith("#") || directive(line)
    end ignorableBetweenDocuments
end YamlDocuments
