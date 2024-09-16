package kyo

import Chunk.Indexed
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.reflect.ClassTag

sealed abstract class Chunk[A] extends Seq[A] derives CanEqual:
    self =>

    import Chunk.internal.*

    private inline given ClassTag[A] = ClassTag.Any.asInstanceOf[ClassTag[A]]

    //////////////////
    // O(1) methods //
    //////////////////

    final override def isEmpty: Boolean = size == 0

    override def take(n: Int): Chunk[A] =
        dropLeftAndRight(0, size - Math.min(Math.max(0, n), size))

    override def drop(n: Int): Seq[A] =
        dropLeft(n)

    final def dropLeft(n: Int): Chunk[A] =
        dropLeftAndRight(Math.min(size, Math.max(0, n)), 0)

    override def dropRight(n: Int): Chunk[A] =
        dropLeftAndRight(0, Math.min(size, Math.max(0, n)))

    override def slice(from: Int, until: Int): Chunk[A] =
        dropLeftAndRight(Math.max(0, from), size - Math.min(size, until))

    final def dropLeftAndRight(left: Int, right: Int): Chunk[A] =
        @tailrec def loop(c: Chunk[A], left: Int, right: Int): Chunk[A] =
            val size = c.size - left - right
            if size <= 0 then Chunk.empty
            else
                c match
                    case Drop(chunk, dropLeft, dropRight, _) =>
                        Drop(chunk, left + dropLeft, right + dropRight, size)
                    case Append(chunk, value, size) if right > 0 =>
                        loop(chunk, left, right - 1)
                    case _ =>
                        Drop(c, left, right, size)
            end if
        end loop
        loop(this, left, right)
    end dropLeftAndRight

    final def append(v: A): Chunk[A] =
        Append(this, v, size + 1)

    override def last: A =
        @tailrec def loop(c: Chunk[A], index: Int): A =
            c match
                case c if index >= c.size || index < 0 =>
                    throw new NoSuchElementException
                case c: Append[A] =>
                    if index == c.size - 1 then
                        c.value
                    else
                        loop(c.chunk, index)
                case c: Drop[A] =>
                    loop(c.chunk, index + c.dropLeft)
                case c: Indexed[A] =>
                    c(index)
        loop(this, this.size - 1)
    end last

    //////////////////
    // O(n) methods //
    //////////////////

    def apply(index: Int): A =
        def outOfBounds = throw new IndexOutOfBoundsException(s"Index out of range: $index")
        @tailrec
        def loop(chunk: Chunk[A], index: Int): A =
            chunk match
                case c: Indexed[A] =>
                    if index < 0 || index >= c.length then outOfBounds
                    c(index)
                case Drop(c, left, right, len) =>
                    if index < 0 || index >= len then outOfBounds
                    loop(c, index + left)
                case Append(c, value, len) =>
                    if index < 0 || index >= len then outOfBounds
                    if index == len - 1 then value else loop(c, index)
        if index < 0 then outOfBounds
        else loop(this, index)
    end apply

    def iterator: Iterator[A] = toArray.iterator

    final def concat(other: Chunk[A]): Chunk[A] =
        if isEmpty then other
        else if other.isEmpty then this
        else
            val s     = size
            val array = new Array[A](s + other.size)
            this.copyTo(array, 0)
            other.copyTo(array, s)
            Compact(array)
        end if
    end concat

    final def changes(using CanEqual[A, A]): Chunk[A] =
        changes(Maybe.empty)

    @targetName("changesMaybe")
    final def changes(first: Maybe[A])(using CanEqual[A, A]): Chunk[A] =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            @tailrec def loop(idx: Int, prev: Maybe[A], acc: Chunk[A]): Chunk[A] =
                if idx < size then
                    val v = indexed(idx)
                    if prev.contains(v) then
                        loop(idx + 1, prev, acc)
                    else
                        loop(idx + 1, Maybe(v), acc.append(v))
                    end if
                else
                    acc
            loop(0, first, Chunk.empty)
    end changes

    final def toIndexed: Indexed[A] =
        if isEmpty then cachedEmpty.asInstanceOf[Indexed[A]]
        else
            this match
                case c: Indexed[A] => c
                case _             => Compact(toArrayInternal)

    final def flatten[B](using ev: A =:= Chunk[B]): Chunk[B] =
        if isEmpty then Chunk.empty
        else
            val nested = this.toArrayInternal

            @tailrec def totalSize(idx: Int = 0, acc: Int = 0): Int =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    totalSize(idx + 1, acc + chunk.size)
                else
                    acc

            val unnested = new Array[B](totalSize())(using ClassTag.Any.asInstanceOf[ClassTag[B]])

            @tailrec def copy(idx: Int = 0, offset: Int = 0): Unit =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    chunk.copyTo(unnested, offset)
                    copy(idx + 1, offset + chunk.size)
            copy()

            Compact(unnested)
    end flatten

    final def copyTo[B >: A](array: Array[B], start: Int): Unit =
        copyTo(array, start, size)

    final def copyTo[B >: A](array: Array[B], start: Int, elements: Int): Unit =
        @tailrec def loop(c: Chunk[A], end: Int, dropLeft: Int, dropRight: Int): Unit =
            c match
                case c: Append[A] =>
                    if dropRight > 0 then
                        loop(c.chunk, end, dropLeft, dropRight - 1)
                    else if end > 0 then
                        array(start + end - 1) = c.value
                        loop(c.chunk, end - 1, dropLeft, dropRight)
                case c: Drop[A] =>
                    loop(c.chunk, end, dropLeft + c.dropLeft, dropRight + c.dropRight)
                case c: Tail[A] =>
                    loop(c.chunk, end, dropLeft + c.offset, dropRight)
                case c: Compact[A] =>
                    val l = c.array.length
                    if l > 0 then
                        System.arraycopy(c.array, dropLeft, array, start, l - dropRight - dropLeft)
                case c: FromSeq[A] =>
                    val seq  = c.value
                    val size = Math.min(end, c.value.size - dropLeft - dropRight)
                    @tailrec def loop(index: Int): Unit =
                        if index < size then
                            array(start + index) = seq(index + dropLeft)
                            loop(index + 1)
                    loop(0)
        if !isEmpty then
            loop(this, elements, 0, 0)
    end copyTo

    override def toArray[B >: A: ClassTag]: Array[B] =
        val array = new Array[B](size)
        copyTo(array, 0)
        array
    end toArray

    final private def toArrayInternal: Array[A] =
        this match
            case c if c.isEmpty =>
                cachedEmpty.array.asInstanceOf[Array[A]]
            case c: Compact[A] =>
                c.array
            case c =>
                c.toArray

end Chunk

object Chunk:

    import internal.*

    sealed abstract class Indexed[A] extends Chunk[A]:

        //////////////////
        // O(1) methods //
        //////////////////

        def apply(i: Int): A

        final override def head: A =
            if isEmpty then
                throw new NoSuchElementException
            else
                apply(0)

        final override def tail: Indexed[A] =
            if size <= 1 then cachedEmpty.asInstanceOf[Indexed[A]]
            else
                this match
                    case Tail(chunk, offset, size) =>
                        Tail(chunk, offset + 1, size - 1)
                    case c =>
                        Tail(c, 1, size - 1)
    end Indexed

    def empty[A]: Chunk[A] =
        cachedEmpty.asInstanceOf[Chunk[A]]

    def apply[A](values: A*): Chunk[A] =
        from(values)

    def from[A](values: Seq[A]): Chunk.Indexed[A] =
        if values.isEmpty then cachedEmpty.asInstanceOf[Chunk.Indexed[A]]
        else
            values match
                case seq: Chunk.Indexed[A] @unchecked => seq
                case seq: IndexedSeq[A]               => FromSeq(seq)
                case _                                => Compact(values.toArray(using ClassTag.Any.asInstanceOf[ClassTag[A]]))

    def fill[A](n: Int)(v: A): Chunk[A] =
        if n <= 0 then empty
        else
            val array = (new Array[Any](n)).asInstanceOf[Array[A]]
            @tailrec def loop(idx: Int = 0): Unit =
                if idx < n then
                    array(idx) = v
                    loop(idx + 1)
            loop()
            Compact(array)
    end fill

    private[kyo] object internal:

        val cachedEmpty = Compact(new Array[Any](0))

        final case class FromSeq[A](
            value: IndexedSeq[A]
        ) extends Indexed[A]:
            def length = value.size
            override def apply(i: Int) =
                if i >= size || i < 0 then
                    throw new IndexOutOfBoundsException(s"Index out of range: $i")
                else
                    value(i)

            override def toString = s"Chunk.Indexed(${value.mkString(", ")})"
        end FromSeq

        final case class Compact[A](
            array: Array[A]
        ) extends Indexed[A]:
            def length = array.length
            override def apply(i: Int) =
                if i >= size || i < 0 then
                    throw new IndexOutOfBoundsException(s"Index out of range: $i")
                else
                    array(i)

            override def toString = s"Chunk.Indexed(${array.mkString(", ")})"
        end Compact

        final case class Tail[A](
            chunk: Indexed[A],
            offset: Int,
            length: Int
        ) extends Indexed[A]:
            override def apply(i: Int): A = chunk(i + offset)
            override def toString         = s"Chunk(${toSeq.mkString(", ")})"
        end Tail

        final case class Drop[A](
            chunk: Chunk[A],
            dropLeft: Int,
            dropRight: Int,
            length: Int
        ) extends Chunk[A]:
            override def toString = s"Chunk(${toSeq.mkString(", ")})"
        end Drop

        final case class Append[A](
            chunk: Chunk[A],
            value: A,
            length: Int
        ) extends Chunk[A]:
            override def toString = s"Chunk(${toSeq.mkString(", ")})"
        end Append
    end internal
end Chunk
