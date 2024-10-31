package kyo

import Text.internal.*
import scala.annotation.tailrec

opaque type Text >: String = String | Op

object Text:

    given Tag[Text] = Tag.fromRaw("kyo.Text")

    def apply(s: String): Text = s

    def empty: Text = ""

    extension (self: Text)

        def isEmpty: Boolean =
            self match
                case s: String => s.isEmpty
                case op: Op    => op.isEmpty

        def length: Int =
            self match
                case s: String => s.length
                case op: Op    => op.length

        def charAt(index: Int): Char =
            self match
                case s: String => s.charAt(index)
                case op: Op    => op.charAt(index)

        def substring(from: Int, until: Int): Text =
            self match
                case s: String => Cut(s, from, until)
                case op: Op    => op.substring(from, until)

        infix def +(other: Text): Text =
            if self.isEmpty then other
            else if other.isEmpty then self
            else Concat(self, other)

        def trim: Text =
            val len   = self.length
            val start = self.indexWhere(!_.isWhitespace)
            if start == -1 then Text.empty
            else
                val end = self.lastIndexWhere(!_.isWhitespace) + 1
                if start == 0 && end == len then self
                else self.substring(start, end)
            end if
        end trim

        def contains(substr: Text): Boolean = indexOf(substr) >= 0

        def startsWith(prefix: Text): Boolean =
            val prefixLen = prefix.length
            val selfLen   = self.length
            @tailrec def loop(i: Int): Boolean =
                if i >= prefixLen then true
                else if charAt(i) != prefix.charAt(i) then false
                else loop(i + 1)

            if prefixLen > selfLen then false
            else loop(0)
        end startsWith

        def endsWith(suffix: Text): Boolean =
            val suffixLen = suffix.length
            val selfLen   = self.length
            @tailrec def loop(i: Int): Boolean =
                if i >= suffixLen then true
                else if charAt(selfLen - suffixLen + i) != suffix.charAt(i) then false
                else loop(i + 1)

            if suffixLen > selfLen then false
            else loop(0)
        end endsWith

        def indexOf(substr: Text): Int =
            val substrLen = substr.length
            val selfLen   = self.length

            @tailrec def loop(i: Int): Int =
                if i > selfLen - substrLen then -1
                else if startsWith(substr, i) then i
                else loop(i + 1)

            @tailrec def startsWith(prefix: Text, offset: Int, prefixIndex: Int = 0): Boolean =
                if prefixIndex >= prefix.length then true
                else if charAt(offset + prefixIndex) != prefix.charAt(prefixIndex) then false
                else startsWith(prefix, offset, prefixIndex + 1)

            if substr.isEmpty then 0
            else if substrLen > selfLen then -1
            else loop(0)
        end indexOf

        def lastIndexOf(substr: Text): Int =
            val substrLen = substr.length
            val selfLen   = self.length
            @tailrec def loop(i: Int): Int =
                if i < 0 then -1
                else if startsWith(substr, i) then i
                else loop(i - 1)

            @tailrec def startsWith(prefix: Text, offset: Int, prefixIndex: Int = 0): Boolean =
                if prefixIndex >= prefix.length then true
                else if charAt(offset + prefixIndex) != prefix.charAt(prefixIndex) then false
                else startsWith(prefix, offset, prefixIndex + 1)

            if substr.isEmpty then selfLen
            else if substrLen > selfLen then -1
            else loop(selfLen - substrLen)
        end lastIndexOf

        def split(separator: Char): Chunk[Text] =
            val selfLen = self.length
            @tailrec def loop(start: Int, current: Int, acc: Chunk[Text]): Chunk[Text] =
                if current >= selfLen then
                    if start < selfLen then acc.append(substring(start, selfLen))
                    else acc
                else if charAt(current) == separator then
                    if current > start then
                        loop(current + 1, current + 1, acc.append(substring(start, current)))
                    else
                        loop(current + 1, current + 1, acc)
                else
                    loop(start, current + 1, acc)

            loop(0, 0, Chunk.empty)
        end split

        def take(n: Int): Text =
            if n <= 0 then Text.empty
            else substring(0, n.min(length))

        def drop(n: Int): Text =
            if n <= 0 then self
            else substring(n.min(length), length)

        def takeRight(n: Int): Text =
            val selfLen = self.length
            if n <= 0 then Text.empty
            else substring((selfLen - n).max(0), selfLen)
        end takeRight

        def dropRight(n: Int): Text =
            val selfLen = self.length
            if n <= 0 then self
            else substring(0, (selfLen - n).max(0))
        end dropRight

        def stripPrefix(prefix: Text): Text =
            if startsWith(prefix) then drop(prefix.length) else self

        def stripSuffix(suffix: Text): Text =
            if endsWith(suffix) then dropRight(suffix.length) else self

        def compareToIgnoreCase(other: Text): Int =
            val selfLen  = self.length
            val otherLen = other.length
            @tailrec
            def loop(i: Int): Int =
                if i >= selfLen && i >= otherLen then 0
                else if i >= selfLen then -1
                else if i >= otherLen then 1
                else
                    val c1 = Character.toLowerCase(charAt(i))
                    val c2 = Character.toLowerCase(other.charAt(i))
                    if c1 != c2 then c1 - c2
                    else loop(i + 1)

            loop(0)
        end compareToIgnoreCase

        def head: Maybe[Char] =
            if self.isEmpty then Absent else Present(self.charAt(0))

        def tail: Text = self.drop(1)

        def span(p: Char => Boolean): (Text, Text) =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then (self, Text.empty)
            else (self.take(idx), self.drop(idx))
        end span

        def dropWhile(p: Char => Boolean): Text =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then Text.empty else self.drop(idx)

        def indexWhere(p: Char => Boolean): Int =
            val selfLen = self.length
            @tailrec
            def loop(i: Int): Int =
                if i >= selfLen then -1
                else if p(self.charAt(i)) then i
                else loop(i + 1)
            loop(0)
        end indexWhere

        def lastIndexWhere(p: Char => Boolean): Int =
            @tailrec
            def loop(i: Int): Int =
                if i < 0 then -1
                else if p(self.charAt(i)) then i
                else loop(i - 1)
            loop(self.length - 1)
        end lastIndexWhere

        def filterNot(p: Char => Boolean): Text =
            @tailrec def loop(i: Int, acc: StringBuilder): Text =
                if i >= self.length then Text(acc.toString)
                else
                    val c = self.charAt(i)
                    if !p(c) then acc.append(c)
                    loop(i + 1, acc)

            loop(0, new StringBuilder)
        end filterNot

        def count(p: Char => Boolean): Int =
            @tailrec def loop(i: Int, acc: Int): Int =
                if i >= self.length then acc
                else if p(self.charAt(i)) then loop(i + 1, acc + 1)
                else loop(i + 1, acc)

            loop(0, 0)
        end count

        def show: String = self.toString()

        def compact: Text = self.toString()

        def is(other: Text): Boolean =
            if self.length != other.length then false
            else
                @tailrec
                def loop(i: Int): Boolean =
                    if i >= self.length then true
                    else if self.charAt(i) != other.charAt(i) then false
                    else loop(i + 1)
                loop(0)
        end is

    end extension

    private[kyo] object internal:

        sealed trait Op:
            def isEmpty: Boolean
            def length: Int
            def charAt(index: Int): Char
            def substring(from: Int, until: Int): Text
        end Op

        final class Cut(payload: String, start: Int, end: Int) extends Op:
            val length: Int              = end - start
            def isEmpty: Boolean         = length == 0
            def charAt(index: Int): Char = payload.charAt(start + index)
            def substring(from: Int, until: Int): Text =
                val newStart = start + from
                val newEnd   = Math.min(start + until, end)
                if newStart >= newEnd then Text.empty
                else if newStart == start && newEnd == end then this
                else Cut(payload, newStart, newEnd)
            end substring
            override def toString: String = payload.substring(start, end)
        end Cut

        object Cut:
            def apply(payload: String, start: Int, end: Int): Cut =
                if start < 0 then throw new StringIndexOutOfBoundsException(s"Start index out of range: $start")
                else if end > payload.length then throw new StringIndexOutOfBoundsException(s"End index out of range: $end")
                else if start > end then throw new IllegalArgumentException(s"Start index ($start) is greater than end index ($end)")
                else new Cut(payload, start, end)
        end Cut

        final case class Concat(left: Text, right: Text) extends Op:
            def isEmpty: Boolean = left.isEmpty && right.isEmpty
            def length: Int      = left.length + right.length
            def charAt(index: Int): Char =
                val leftLength = left.length
                if index < leftLength then left.charAt(index)
                else right.charAt(index - leftLength)
            end charAt
            def substring(from: Int, until: Int): Text =
                val leftLength = left.length
                if until <= leftLength then
                    left.substring(from, until)
                else if from >= leftLength then
                    right.substring(from - leftLength, until - leftLength)
                else
                    Concat(left.substring(from, leftLength), right.substring(0, until - leftLength))
                end if
            end substring
            override def toString: String = left.toString + right.toString
        end Concat
    end internal

end Text
