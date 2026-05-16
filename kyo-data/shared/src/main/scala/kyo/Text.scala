package kyo

import Text.internal.*
import scala.annotation.tailrec

opaque type Text >: String = String | Op

object Text:

    def apply(s: String): Text = s
    def empty: Text = ""

    abstract class Predicate extends Serializable:
        def apply(char: Char): Boolean
    end Predicate

    extension (self: Text)
        def isEmpty: Boolean = self match
            case s: String => s.isEmpty
            case op: Op    => op.isEmpty

        def length: Int = self match
            case s: String => s.length
            case op: Op    => op.length

        def size: Int = length

        def charAt(index: Int): Char = self match
            case s: String => s.charAt(index)
            case op: Op    => op.charAt(index)

        def substring(from: Int, until: Int): Text = self match
            case s: String => Cut(s, from, until)
            case op: Op    => op.substring(from, until)

        infix def +(other: Text): Text =
            if self.isEmpty then other
            else if other.isEmpty then self
            else Concat(self, other)

        // OPTIMIZATION: Bulk character copy using String.getChars for Cut nodes
        def copyToArray(dst: Array[Char], dstBegin: Int): Unit =
            copyToArrayImpl(self, dst, dstBegin, 0, self.length)

        // OPTIMIZATION: Convert to flat char array efficiently
        def toCharArray: Array[Char] =
            val arr = new Array[Char](self.length)
            copyToArrayImpl(self, arr, 0, 0, self.length)
            arr

        override def toString: String =
            self match
                case s: String => s
                case op: Op    => op.toString
    end extension

    // OPTIMIZATION: Bulk copy implementation - avoids per-character charAt overhead
    @tailrec
    private def copyToArrayImpl(
        text: Text, dst: Array[Char], dstPos: Int, srcStart: Int, srcLen: Int
    ): Unit = text match
        case s: String =>
            s.getChars(srcStart, srcStart + srcLen, dst, dstPos)
        case cut: Cut =>
            cut.payload.getChars(cut.start + srcStart, cut.start + srcStart + srcLen, dst, dstPos)
        case concat: Concat =>
            val leftLen = concat.left.length
            if srcStart + srcLen <= leftLen then
                copyToArrayImpl(concat.left, dst, dstPos, srcStart, srcLen)
            else if srcStart >= leftLen then
                copyToArrayImpl(concat.right, dst, dstPos, srcStart - leftLen, srcLen)
            else
                val leftPart = leftLen - srcStart
                copyToArrayImpl(concat.left, dst, dstPos, srcStart, leftPart)
                copyToArrayImpl(concat.right, dst, dstPos + leftPart, 0, srcLen - leftPart)
        case rev: Reverse =>
            val end = rev.payload.length - srcStart
            val start = end - srcLen
            reverseCopy(rev.payload, dst, dstPos, start, end)
        case _ =>
            var i = 0
            while i < srcLen do
                dst(dstPos + i) = text.charAt(srcStart + i)
                i += 1
    end copyToArrayImpl

    // OPTIMIZATION: Reverse copy using bulk String.getChars where possible
    @tailrec
    private def reverseCopy(text: Text, dst: Array[Char], dstPos: Int, start: Int, end: Int): Unit =
        text match
            case s: String =>
                var i = end - 1
                var j = dstPos
                while i >= start do
                    dst(j) = s.charAt(i)
                    i -= 1
                    j += 1
            case cut: Cut =>
                var i = cut.start + end - 1
                var j = dstPos
                while i >= cut.start + start do
                    dst(j) = cut.payload.charAt(i)
                    i -= 1
                    j += 1
            case rev2: Reverse =>
                copyToArrayImpl(rev2.payload, dst, dstPos, start, end)
            case other =>
                var i = 0
                val len = end - start
                while i < len do
                    dst(dstPos + i) = other.charAt(end - 1 - i)
                    i += 1

    // OPTIMIZATION: Balanced concat - prevents O(depth) charAt recursion
    def balancedConcat(left: Text, right: Text): Text =
        if left.isEmpty then right
        else if right.isEmpty then left
        else
            val leftStr = left match { case s: String => Some(s); case _ => None }
            val rightStr = right match { case s: String => Some(s); case _ => None }
            (leftStr, rightStr) match
                case (Some(l), Some(r)) => l + r
                case _ =>
                    val totalLen = left.length + right.length
                    if totalLen <= 64 then Concat(left, right)
                    else
                        val leftDepth = depth(left)
                        val rightDepth = depth(right)
                        if leftDepth > rightDepth + 2 then
                            left match
                                case c: Concat =>
                                    balancedConcat(c.left, balancedConcat(c.right, right))
                                case _ => Concat(left, right)
                        else if rightDepth > leftDepth + 2 then
                            right match
                                case c: Concat =>
                                    balancedConcat(balancedConcat(left, c.left), c.right)
                                case _ => Concat(left, right)
                        else Concat(left, right)

    private def depth(text: Text): Int = text match
        case _: String  => 0
        case _: Cut     => 0
        case c: Concat  => 1 + Math.max(depth(c.left), depth(c.right))
        case r: Reverse => 1 + depth(r.payload)
        case _          => 0

    // OPTIMIZATION: CharSequence bridge for JDK interop
    def asCharSequence(text: Text): CharSequence = text match
        case s: String => s
        case op: Op    => new OpCharSequence(op)

    private final class OpCharSequence(op: Op) extends CharSequence:
        def length(): Int                    = op.length
        def charAt(index: Int): Char         = op.charAt(index)
        def subSequence(start: Int, end: Int): CharSequence =
            OpCharSequence(op.substring(start, end).asInstanceOf[Op])
        override def toString(): String = op.toString
    end OpCharSequence

    private[kyo] object internal:

        sealed trait Op extends Serializable:
            def isEmpty: Boolean
            def length: Int
            def charAt(index: Int): Char
            def substring(from: Int, until: Int): Text
            // OPTIMIZATION: Direct toString for Op
            override def toString: String =
                val arr = new Array[Char](length)
                copyToArrayImpl(this, arr, 0, 0, length)
                new String(arr)
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
            // OPTIMIZATION: Override toString for Cut - direct String.substring
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
                if until <= leftLength then left.substring(from, until)
                else if from >= leftLength then right.substring(from - leftLength, until - leftLength)
                else Concat(left.substring(from, leftLength), right.substring(0, until - leftLength))
            end substring
            // OPTIMIZATION: Use copyToArray for Concat toString
            override def toString: String =
                val arr = new Array[Char](length)
                copyToArrayImpl(this, arr, 0, 0, length)
                new String(arr)
        end Concat

        case class Reverse(payload: Text) extends Op:
            def isEmpty: Boolean         = payload.isEmpty
            def length: Int              = payload.length
            def charAt(index: Int): Char = payload.charAt(length - 1 - index)
            def substring(from: Int, until: Int): Text =
                if from >= until then Text.empty
                else if from == 0 && until == length then this
                else Reverse(payload.substring(length - until, length - from))
            // OPTIMIZATION: Use reverseCopy for Reverse toString
            override def toString: String =
                val arr = new Array[Char](length)
                reverseCopy(payload, arr, 0, 0, length)
                new String(arr)
        end Reverse
    end internal

    given Flag.Reader.Scalar[Text] with
        def apply(s: String): Either[Throwable, Text] = Right(Text(s))
        def typeName: String                          = "Text"

end Text
