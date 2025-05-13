package kyo

import Text.internal.*
import scala.annotation.tailrec

opaque type Text >: String = String | Op

object Text:

    /** Creates a new Text from a String
      *
      * @param s
      *   the string to convert to Text
      * @return
      *   a new Text instance
      */
    def apply(s: String): Text = s

    /** Returns an empty Text instance */
    def empty: Text = ""

    /** Abstract class for character predicates used in Text operations */
    abstract class Predicate extends Serializable:
        /** Tests if a character matches the predicate
          *
          * @param char
          *   the character to test
          * @return
          *   true if the character matches the predicate
          */
        def apply(char: Char): Boolean
    end Predicate

    extension (self: Text)

        /** Tests if this Text is empty
          *
          * @return
          *   true if the Text contains no characters
          */
        def isEmpty: Boolean =
            self match
                case s: String => s.isEmpty
                case op: Op    => op.isEmpty

        /** Returns the length of this Text
          *
          * @return
          *   the number of characters in this Text
          */
        def length: Int =
            self match
                case s: String => s.length
                case op: Op    => op.length

        /** Alias for length
          *
          * @return
          *   the number of characters in this Text
          */
        def size: Int = length

        /** Returns the character at the specified index
          *
          * @param index
          *   the index of the character to return
          * @return
          *   the character at the specified index
          * @throws StringIndexOutOfBoundsException
          *   if index is negative or >= length
          */
        def charAt(index: Int): Char =
            self match
                case s: String => s.charAt(index)
                case op: Op    => op.charAt(index)

        /** Creates a new Text containing the characters from the specified range
          *
          * @param from
          *   the start index, inclusive
          * @param until
          *   the end index, exclusive
          * @return
          *   a new Text containing the specified range of characters
          * @throws StringIndexOutOfBoundsException
          *   if indices are out of bounds
          * @throws IllegalArgumentException
          *   if from > until
          */
        def substring(from: Int, until: Int): Text =
            self match
                case s: String => Cut(s, from, until)
                case op: Op    => op.substring(from, until)

        /** Concatenates this Text with another
          *
          * @param other
          *   the Text to append
          * @return
          *   a new Text containing this Text followed by other
          */
        infix def +(other: Text): Text =
            if self.isEmpty then other
            else if other.isEmpty then self
            else Concat(self, other)

        /** Removes leading and trailing whitespace
          *
          * @return
          *   a new Text with whitespace removed from both ends
          */
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

        /** Tests if this Text contains a substring
          *
          * @param substr
          *   the substring to search for
          * @return
          *   true if this Text contains substr
          */
        def contains(substr: Text): Boolean = indexOf(substr) >= 0

        /** Tests if this Text starts with a prefix
          *
          * @param prefix
          *   the prefix to test
          * @return
          *   true if this Text starts with prefix
          */
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

        /** Tests if this Text ends with a suffix
          *
          * @param suffix
          *   the suffix to test
          * @return
          *   true if this Text ends with suffix
          */
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

        /** Finds the first occurrence of a substring
          *
          * @param substr
          *   the substring to find
          * @return
          *   the index of the first occurrence, or -1 if not found
          */
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

        /** Finds the last occurrence of a substring
          *
          * @param substr
          *   the substring to find
          * @return
          *   the index of the last occurrence, or -1 if not found
          */
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

        /** Splits this Text around a separator character
          *
          * @param separator
          *   the character to split on
          * @return
          *   a Chunk containing the parts between separators
          */
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

        /** Takes the first n characters
          *
          * @param n
          *   number of characters to take
          * @return
          *   a new Text containing at most n characters from the start
          */
        def take(n: Int): Text =
            if n <= 0 then Text.empty
            else substring(0, n.min(length))

        /** Drops the first n characters
          *
          * @param n
          *   number of characters to drop
          * @return
          *   a new Text without the first n characters
          */
        def drop(n: Int): Text =
            if n <= 0 then self
            else substring(n.min(length), length)

        /** Takes the last n characters
          *
          * @param n
          *   number of characters to take from the end
          * @return
          *   a new Text containing at most n characters from the end
          */
        def takeRight(n: Int): Text =
            val selfLen = self.length
            if n <= 0 then Text.empty
            else substring((selfLen - n).max(0), selfLen)
        end takeRight

        /** Drops the last n characters
          *
          * @param n
          *   number of characters to drop from the end
          * @return
          *   a new Text without the last n characters
          */
        def dropRight(n: Int): Text =
            val selfLen = self.length
            if n <= 0 then self
            else substring(0, (selfLen - n).max(0))
        end dropRight

        /** Removes a prefix if it exists
          *
          * @param prefix
          *   the prefix to remove
          * @return
          *   a new Text with the prefix removed if it existed, otherwise this Text
          */
        def stripPrefix(prefix: Text): Text =
            if startsWith(prefix) then drop(prefix.length) else self

        /** Removes a suffix if it exists
          *
          * @param suffix
          *   the suffix to remove
          * @return
          *   a new Text with the suffix removed if it existed, otherwise this Text
          */
        def stripSuffix(suffix: Text): Text =
            if endsWith(suffix) then dropRight(suffix.length) else self

        /** Compares two Texts ignoring case
          *
          * @param other
          *   the Text to compare to
          * @return
          *   negative if this < other, 0 if equal, positive if this > other
          */
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

        /** Returns the first character if present
          *
          * @return
          *   Maybe containing the first character, or Absent if empty
          */
        def head: Maybe[Char] =
            if self.isEmpty then Absent else Present(self.charAt(0))

        /** Returns all characters except the first
          *
          * @return
          *   a new Text without the first character
          */
        def tail: Text = self.drop(1)

        /** Splits this Text into two parts based on a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   a tuple of (matching prefix, remaining text)
          */
        def span(p: Predicate): (Text, Text) =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then (self, Text.empty)
            else (self.take(idx), self.drop(idx))
        end span

        /** Drops characters while they match a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   remaining text after dropping matching characters
          */
        def dropWhile(p: Predicate): Text =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then Text.empty else self.drop(idx)

        /** Finds index of first character matching a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   index of first match, or -1 if none found
          */
        def indexWhere(p: Predicate): Int =
            val selfLen = self.length
            @tailrec
            def loop(i: Int): Int =
                if i >= selfLen then -1
                else if p(self.charAt(i)) then i
                else loop(i + 1)
            loop(0)
        end indexWhere

        /** Finds index of last character matching a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   index of last match, or -1 if none found
          */
        def lastIndexWhere(p: Predicate): Int =
            @tailrec
            def loop(i: Int): Int =
                if i < 0 then -1
                else if p(self.charAt(i)) then i
                else loop(i - 1)
            loop(self.length - 1)
        end lastIndexWhere

        /** Creates new Text excluding characters matching a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   new Text with matching characters removed
          */
        def filterNot(p: Predicate): Text =
            @tailrec def loop(i: Int, acc: StringBuilder): Text =
                if i >= self.length then Text(acc.toString)
                else
                    val c = self.charAt(i)
                    if !p(c) then acc.append(c)
                    loop(i + 1, acc)

            loop(0, new StringBuilder)
        end filterNot

        /** Counts characters matching a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   number of characters matching the predicate
          */
        def count(p: Predicate): Int =
            @tailrec def loop(i: Int, acc: Int): Int =
                if i >= self.length then acc
                else if p(self.charAt(i)) then loop(i + 1, acc + 1)
                else loop(i + 1, acc)

            loop(0, 0)
        end count

        /** Converts this Text to a String representation
          *
          * @return
          *   string representation of this Text
          */
        def show: String = self.toString()

        /** Creates a compact representation of this Text
          *
          * @return
          *   new Text with internal operations collapsed
          */
        def compact: Text = self.toString()

        /** Tests if this Text equals another Text
          *
          * @param other
          *   the Text to compare to
          * @return
          *   true if the Texts contain the same characters
          */
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

        /** Takes characters while they match a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   prefix of characters matching the predicate
          */
        def takeWhile(p: Predicate): Text =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then self else self.take(idx)
        end takeWhile

        /** Creates new Text including only characters matching a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   new Text with only matching characters
          */
        def filter(p: Predicate): Text =
            @tailrec def loop(i: Int, acc: StringBuilder): Text =
                if i >= self.length then Text(acc.toString)
                else
                    val c = self.charAt(i)
                    if p(c) then acc.append(c)
                    loop(i + 1, acc)

            loop(0, new StringBuilder)
        end filter

        /** Tests if any character matches a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   true if any character matches
          */
        def exists(p: Predicate): Boolean =
            indexWhere(p) >= 0

        /** Tests if all characters match a predicate
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   true if all characters match
          */
        def forall(p: Predicate): Boolean =
            !exists(!p(_))

        /** Creates a new Text with characters in reverse order
          *
          * @return
          *   new Text with reversed characters
          */
        def reverse: Text = Reverse(self)

        /** Takes characters until finding one that doesn't match the predicate, including that character
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   prefix of Text up to and including first non-matching character
          */
        def takeUntilNext(p: Predicate): Text =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then self else self.take(idx)

        /** Drops characters until finding one that doesn't match the predicate, starting from that character
          *
          * @param p
          *   the predicate to test characters against
          * @return
          *   suffix of Text starting from first non-matching character
          */
        def dropUntilNext(p: Predicate): Text =
            val idx = self.indexWhere(!p(_))
            if idx == -1 then Text.empty else self.drop(idx + 1)

    end extension

    private[kyo] object internal:

        sealed trait Op extends Serializable:
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

        case class Reverse(payload: Text) extends Op:
            def isEmpty: Boolean         = payload.isEmpty
            def length: Int              = payload.length
            def charAt(index: Int): Char = payload.charAt(length - 1 - index)
            def substring(from: Int, until: Int): Text =
                if from >= until then Text.empty
                else if from == 0 && until == length then this
                else Reverse(payload.substring(length - until, length - from))
            override def toString: String =
                val sb = new StringBuilder(length)
                var i  = length - 1
                while i >= 0 do
                    sb.append(payload.charAt(i))
                    i -= 1
                sb.toString
            end toString
        end Reverse
    end internal

end Text
