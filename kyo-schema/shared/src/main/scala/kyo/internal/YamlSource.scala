package kyo.internal

import scala.annotation.tailrec

private[kyo] object YamlSource:

    def lineEnd(source: String, pos: Int): Int =
        source.indexOf('\n', pos) match
            case -1 => source.length
            case n  => n
    end lineEnd

    def line(source: String, pos: Int): String =
        source.substring(pos, lineEnd(source, pos))
    end line

    def indent(source: String, pos: Int): Int =
        var i = pos
        var n = 0
        while i < source.length && source.charAt(i) == ' ' do
            n += 1
            i += 1
        n
    end indent

    def lineNumber(source: String, position: Int): Int =
        var line = 1
        var i    = 0
        while i < position do
            if source.charAt(i) == '\n' then line += 1
            i += 1
        line
    end lineNumber

    def skipBlankAndCommentLines(source: String, pos: Int): Int =
        var current = pos
        var done    = false
        while !done && current < source.length do
            val start = current
            while current < source.length && source.charAt(current).isWhitespace && source.charAt(current) != '\n' do current += 1
            val blankOrComment =
                current >= source.length ||
                    source.charAt(current) == '\n' ||
                    source.charAt(current) == '#'
            if blankOrComment then
                current = lineEnd(source, start)
                if current < source.length && source.charAt(current) == '\n' then current += 1
            else
                current = start
                done = true
            end if
        end while
        current
    end skipBlankAndCommentLines

    def skipFlowWhitespace(source: String, pos: Int): Int =
        var current = pos
        var done    = false
        while !done && current < source.length do
            val ch = source.charAt(current)
            if ch.isWhitespace then current += 1
            else if ch == '#' then current = lineEnd(source, current)
            else done = true
            end if
        end while
        current
    end skipFlowWhitespace

    def stripComment(s: String): String =
        @tailrec def loop(i: Int, single: Boolean, double: Boolean, escape: Boolean): String =
            if i >= s.length then s
            else
                val ch = s.charAt(i)
                if escape then loop(i + 1, single, double, false)
                else if double && ch == '\\' then loop(i + 1, single, double, true)
                else if !double && ch == '\'' then loop(i + 1, !single, double, false)
                else if !single && ch == '"' then loop(i + 1, single, !double, false)
                else if !single && !double && ch == '#' && (i == 0 || s.charAt(i - 1).isWhitespace) then s.substring(0, i)
                else loop(i + 1, single, double, false)
                end if
            end if
        end loop

        loop(0, single = false, double = false, escape = false)
    end stripComment

    def trimmedRange(source: String, start: Int, stop: Int): (Int, Int) =
        var from = start
        var to   = stop
        while from < to && source.charAt(from).isWhitespace do from += 1
        while to > from && source.charAt(to - 1).isWhitespace do to -= 1
        (from, to)
    end trimmedRange

    def quotedScalar(source: String, start: Int, stop: Int): Boolean =
        stop > start + 1 && (
            source.charAt(start) == '\'' && source.charAt(stop - 1) == '\'' ||
                source.charAt(start) == '"' && source.charAt(stop - 1) == '"'
        )
    end quotedScalar

    def propertyToken(text: String): (String, String) =
        var end = 0
        while end < text.length && !text.charAt(end).isWhitespace do end += 1
        (text.substring(0, end), text.substring(end).trim)
    end propertyToken

    def foldFlowScalarText(text: String): String =
        if text.indexOf('\n') < 0 && text.indexOf('\r') < 0 then text
        else
            val out = new StringBuilder

            @tailrec def lineStop(i: Int): Int =
                if i >= text.length || text.charAt(i) == '\n' || text.charAt(i) == '\r' then i
                else lineStop(i + 1)
            end lineStop

            @tailrec def trimStart(i: Int, stop: Int): Int =
                if i < stop && text.charAt(i).isWhitespace then trimStart(i + 1, stop)
                else i
            end trimStart

            @tailrec def trimEnd(start: Int, stop: Int): Int =
                if stop > start && text.charAt(stop - 1).isWhitespace then trimEnd(start, stop - 1)
                else stop
            end trimEnd

            @tailrec def appendRange(i: Int, stop: Int): Unit =
                if i < stop then
                    out.append(text.charAt(i))
                    appendRange(i + 1, stop)
            end appendRange

            @tailrec def loop(start: Int, append: Boolean): Unit =
                if start <= text.length then
                    val stop = lineStop(start)
                    val from = trimStart(start, stop)
                    val to   = trimEnd(from, stop)
                    val nextAppend =
                        if from < to then
                            if append then out.append(' ')
                            appendRange(from, to)
                            true
                        else append
                    end nextAppend
                    val nextStart =
                        if stop >= text.length then text.length + 1
                        else if text.charAt(stop) == '\r' && stop + 1 < text.length && text.charAt(stop + 1) == '\n' then stop + 2
                        else stop + 1
                    loop(nextStart, nextAppend)
                end if
            end loop

            loop(0, append = false)
            out.result()
        end if
    end foldFlowScalarText

    def startsSequenceEntryAtIndent(source: String, pos: Int): Boolean =
        val n   = indent(source, pos)
        val idx = pos + n
        idx < source.length && source.charAt(idx) == '-' && (
            idx + 1 >= source.length ||
                source.charAt(idx + 1) == ' ' ||
                source.charAt(idx + 1) == '\n' ||
                source.charAt(idx + 1) == '\r'
        )
    end startsSequenceEntryAtIndent

    def startsFlowCollection(source: String, pos: Int): Boolean =
        val n   = indent(source, pos)
        val idx = pos + n
        idx < source.length && (source.charAt(idx) == '[' || source.charAt(idx) == '{')
    end startsFlowCollection

    def startsFlowSequence(source: String, pos: Int): Boolean =
        val n   = indent(source, pos)
        val idx = pos + n
        idx < source.length && source.charAt(idx) == '['
    end startsFlowSequence

    def startsFlowMapping(source: String, pos: Int): Boolean =
        val n   = indent(source, pos)
        val idx = pos + n
        idx < source.length && source.charAt(idx) == '{'
    end startsFlowMapping

    def startsFlowCollection(text: String): Boolean =
        text.startsWith("[") || text.startsWith("{")
    end startsFlowCollection

    def isDocumentMarker(source: String, pos: Int, marker: String): Boolean =
        val text = line(source, pos)
        indent(source, pos) == 0 && text.startsWith(marker) && (
            text.length == marker.length ||
                text.charAt(marker.length).isWhitespace ||
                text.charAt(marker.length) == '#'
        )
    end isDocumentMarker

    def flowMappingSeparator(text: String): Int =
        flowMappingSeparator(text, 0, text.length)
    end flowMappingSeparator

    def flowMappingSeparator(source: String, start: Int, stop: Int): Int =
        var current = start
        var colon   = findTopLevel(source, ':', current, stop)
        while colon >= 0 do
            val (keyStart, keyEnd) = trimmedRange(source, start, colon)
            if colon == stop - 1 || source.charAt(colon + 1).isWhitespace || quotedScalar(source, keyStart, keyEnd) then return colon
            current = colon + 1
            colon = findTopLevel(source, ':', current, stop)
        end while
        -1
    end flowMappingSeparator

    def findTopLevel(s: String, target: Char): Int =
        findTopLevel(s, target, 0, s.length)
    end findTopLevel

    def findTopLevel(s: String, target: Char, from: Int): Int =
        findTopLevel(s, target, from, s.length)
    end findTopLevel

    def findTopLevel(s: String, target: Char, from: Int, stop: Int): Int =
        @tailrec def loop(i: Int, depth: Int, single: Boolean, double: Boolean, escape: Boolean): Int =
            if i >= stop then -1
            else
                val ch = s.charAt(i)
                if escape then loop(i + 1, depth, single, double, false)
                else if double && ch == '\\' then loop(i + 1, depth, single, double, true)
                else if !double && ch == '\'' then loop(i + 1, depth, !single, double, false)
                else if !single && ch == '"' then loop(i + 1, depth, single, !double, false)
                else if !single && !double then
                    ch match
                        case '[' | '{'                      => loop(i + 1, depth + 1, single, double, false)
                        case ']' | '}'                      => loop(i + 1, depth - 1, single, double, false)
                        case c if c == target && depth == 0 => i
                        case _                              => loop(i + 1, depth, single, double, false)
                    end match
                else loop(i + 1, depth, single, double, false)
                end if
            end if
        end loop

        loop(from, 0, single = false, double = false, escape = false)
    end findTopLevel
end YamlSource
