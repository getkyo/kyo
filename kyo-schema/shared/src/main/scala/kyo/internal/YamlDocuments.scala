package kyo.internal

import kyo.*

private[kyo] object YamlDocuments:

    def split(input: String): Chunk[String] =
        val docs          = Chunk.newBuilder[String]
        val current       = new StringBuilder
        var sawDocument   = false
        var afterDocument = false
        var currentText   = false

        var start = 0
        while start < input.length do
            val lineEnd = YamlSource.lineEnd(input, start)
            val stop =
                if lineEnd > start && input.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                else lineEnd
            marker(input, start, stop) match
                case Present("...") =>
                    docs += current.result()
                    current.clear()
                    currentText = false
                    sawDocument = true
                    afterDocument = true
                case Present("---") =>
                    if sawDocument && !afterDocument then docs += current.result()
                    else if !sawDocument && currentText then docs += current.result()
                    current.clear()
                    currentText = false
                    sawDocument = true
                    afterDocument = false
                case _ if afterDocument && ignorableBetweenDocuments(input, start, stop) =>
                    ()
                case _ if current.isEmpty && !sawDocument && directive(input, start, stop) =>
                    ()
                case _ =>
                    appendLine(current, input, start, stop)
                    currentText ||= hasNonWhitespace(input, start, stop)
                    afterDocument = false
            end match
            start = if lineEnd < input.length then lineEnd + 1 else input.length
        end while

        if !afterDocument && (currentText || sawDocument) then docs += current.result()
        docs.result()
    end split

    private def marker(input: String, start: Int, stop: Int): Maybe[String] =
        if startsWith(input, start, stop, "---") && separated(input, start + 3, stop) then Maybe("---")
        else if startsWith(input, start, stop, "...") && separated(input, start + 3, stop) then Maybe("...")
        else Absent
    end marker

    private def startsWith(input: String, start: Int, stop: Int, prefix: String): Boolean =
        stop - start >= prefix.length && input.startsWith(prefix, start)
    end startsWith

    private def appendLine(current: StringBuilder, input: String, start: Int, stop: Int): Unit =
        var i = start
        while i < stop do
            current.append(input.charAt(i))
            i += 1
        end while
        current.append('\n')
    end appendLine

    private def separated(input: String, idx: Int, stop: Int): Boolean =
        idx >= stop || input.charAt(idx).isWhitespace || input.charAt(idx) == '#'

    private def directive(input: String, start: Int, stop: Int): Boolean =
        start < stop && input.charAt(start) == '%'

    private def ignorableBetweenDocuments(input: String, start: Int, stop: Int): Boolean =
        val first = firstNonWhitespace(input, start, stop)
        first >= stop || input.charAt(first) == '#' || directive(input, first, stop)
    end ignorableBetweenDocuments

    private def hasNonWhitespace(input: String, start: Int, stop: Int): Boolean =
        firstNonWhitespace(input, start, stop) < stop
    end hasNonWhitespace

    private def firstNonWhitespace(input: String, start: Int, stop: Int): Int =
        var i = start
        while i < stop && input.charAt(i).isWhitespace do i += 1
        i
    end firstNonWhitespace
end YamlDocuments
