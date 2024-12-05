package kyo

import Chunk.Indexed
import java.util.Arrays
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.reflect.ClassTag

/** An immutable, efficient sequence of elements.
  *
  * Chunk provides O(1) operations for many common operations like `take`, `drop`, and `slice`. It also provides efficient concatenation and
  * element access.
  *
  * @tparam A
  *   the type of elements in this Chunk
  */
sealed abstract class Chunk[A] extends Seq[A] derives CanEqual:
    self =>

    import Chunk.internal.*

    private inline given ClassTag[A] = ClassTag.Any.asInstanceOf[ClassTag[A]]

    //////////////////
    // O(1) methods //
    //////////////////

    /** Checks if the Chunk is empty.
      *
      * @return
      *   true if the Chunk contains no elements, false otherwise
      */
    final override def isEmpty: Boolean = length == 0

    override def length: Int

    /** Takes the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to take
      * @return
      *   a new Chunk containing the first n elements
      */
    override def take(n: Int): Chunk[A] =
        if n == length then this
        else dropLeftAndRight(0, length - Math.min(Math.max(0, n), length))

    /** Drops the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the first n elements removed
      */
    override def drop(n: Int): Chunk[A] =
        dropLeft(n)

    /** Drops the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the first n elements removed
      */
    final def dropLeft(n: Int): Chunk[A] =
        dropLeftAndRight(Math.min(length, Math.max(0, n)), 0)

    /** Drops the last n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the last n elements removed
      */
    override def dropRight(n: Int): Chunk[A] =
        dropLeftAndRight(0, Math.min(length, Math.max(0, n)))

    /** Returns a Chunk that is a slice of this Chunk.
      *
      * @param from
      *   the starting index of the slice
      * @param until
      *   the ending index (exclusive) of the slice
      * @return
      *   a new Chunk containing the specified slice
      */
    override def slice(from: Int, until: Int): Chunk[A] =
        dropLeftAndRight(Math.max(0, from), length - Math.min(length, until))

    /** Drops elements from both ends of the Chunk.
      *
      * @param left
      *   the number of elements to drop from the left
      * @param right
      *   the number of elements to drop from the right
      * @return
      *   a new Chunk with elements dropped from both ends
      */
    final def dropLeftAndRight(left: Int, right: Int): Chunk[A] =
        @tailrec def loop(c: Chunk[A], left: Int, right: Int): Chunk[A] =
            val length = c.length - left - right
            if length <= 0 then Chunk.empty
            else
                c match
                    case Drop(chunk, dropLeft, dropRight, _) =>
                        Drop(chunk, left + dropLeft, right + dropRight, length)
                    case Append(chunk, value, length) if right > 0 =>
                        loop(chunk, left, right - 1)
                    case _ =>
                        Drop(c, left, right, length)
            end if
        end loop
        loop(this, left, right)
    end dropLeftAndRight

    /** Appends an element to the end of the Chunk.
      *
      * @param v
      *   the element to append
      * @return
      *   a new Chunk with the element appended
      */
    final def append(v: A): Chunk[A] =
        Append(this, v, length + 1)

    /** Returns the first element of the Chunk wrapped in a Maybe.
      *
      * @return
      *   Maybe containing the first element if the Chunk is non-empty, or Maybe.empty if the Chunk is empty
      */
    final def headMaybe: Maybe[A] =
        Maybe.when(nonEmpty)(head)

    /** Returns the last element of the Chunk.
      *
      * @return
      *   the last element
      * @throws NoSuchElementException
      *   if the Chunk is empty
      */
    override def last: A =
        @tailrec def loop(c: Chunk[A], index: Int): A =
            c match
                case c if index >= c.length || index < 0 =>
                    throw new NoSuchElementException
                case c: Append[A] =>
                    if index == c.length - 1 then
                        c.value
                    else
                        loop(c.chunk, index)
                case c: Drop[A] =>
                    loop(c.chunk, index + c.dropLeft)
                case c: Indexed[A] =>
                    c(index)
        loop(this, this.length - 1)
    end last

    //////////////////
    // O(n) methods //
    //////////////////

    /** Returns the element at the specified index.
      *
      * @param index
      *   the index of the element to return
      * @return
      *   the element at the specified index
      * @throws IndexOutOfBoundsException
      *   if the index is out of bounds
      */
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

    /** Returns an iterator over the elements of the Chunk.
      *
      * @return
      *   an Iterator[A] over the elements of the Chunk
      */
    def iterator: Iterator[A] = toIndexed.iterator

    /** Concatenates this Chunk with another Chunk.
      *
      * @param other
      *   the Chunk to concatenate with this one
      * @return
      *   a new Chunk containing all elements from this Chunk followed by all elements from the other Chunk
      */
    final def concat(other: Chunk[A]): Chunk[A] =
        if isEmpty then other
        else if other.isEmpty then this
        else
            val s     = length
            val array = new Array[A](s + other.length)
            this.copyTo(array, 0)
            other.copyTo(array, s)
            Compact(array)
        end if
    end concat

    /** Returns a new Chunk containing only the elements that change from the previous element.
      *
      * @param using
      *   CanEqual[A, A] implicit evidence that A can be compared for equality
      * @return
      *   a new Chunk containing only the changing elements
      */
    final def changes(using CanEqual[A, A]): Chunk[A] =
        changes(Maybe.empty)

    /** Returns a new Chunk containing only the elements that change from the previous element, with a given initial value.
      *
      * @param first
      *   the initial value to compare against
      * @param using
      *   CanEqual[A, A] implicit evidence that A can be compared for equality
      * @return
      *   a new Chunk containing only the changing elements
      */
    @targetName("changesMaybe")
    final def changes(first: Maybe[A])(using CanEqual[A, A]): Chunk[A] =
        if isEmpty then Chunk.empty
        else
            val length  = this.length
            val indexed = this.toIndexed
            @tailrec def loop(idx: Int, prev: Maybe[A], acc: Chunk[A]): Chunk[A] =
                if idx < length then
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

    /** Converts this Chunk to an Indexed Chunk.
      *
      * @return
      *   an Indexed version of this Chunk
      */
    final def toIndexed: Indexed[A] =
        if isEmpty then cachedEmpty.asInstanceOf[Indexed[A]]
        else
            this match
                case c: Indexed[A] => c
                case _             => Compact(toArrayInternal)

    /** Flattens a Chunk of Chunks into a single Chunk.
      *
      * @param ev
      *   evidence that A is a Chunk[B]
      * @return
      *   a flattened Chunk
      */
    final def flattenChunk[B](using ev: A =:= Chunk[B]): Chunk[B] =
        if isEmpty then Chunk.empty
        else
            val nested = this.toArrayInternal

            @tailrec def totalSize(idx: Int = 0, acc: Int = 0): Int =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    totalSize(idx + 1, acc + chunk.length)
                else
                    acc

            val unnested = new Array[B](totalSize())(using ClassTag.Any.asInstanceOf[ClassTag[B]])

            @tailrec def copy(idx: Int = 0, offset: Int = 0): Unit =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    chunk.copyTo(unnested, offset)
                    copy(idx + 1, offset + chunk.length)
            copy()

            Compact(unnested)
    end flattenChunk

    /** Copies the elements of this Chunk to an array.
      *
      * @param array
      *   the array to copy to
      * @param start
      *   the starting position in the array
      */
    final def copyTo[B >: A](array: Array[B], start: Int): Unit =
        copyTo(array, start, length)

    /** Copies a specified number of elements from this Chunk to an array.
      *
      * @param array
      *   the array to copy to
      * @param start
      *   the starting position in the array
      * @param elements
      *   the number of elements to copy
      */
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
                    val seq    = c.value
                    val length = Math.min(end, c.value.length - dropLeft - dropRight)
                    @tailrec def loop(index: Int): Unit =
                        if index < length then
                            array(start + index) = seq(index + dropLeft)
                            loop(index + 1)
                    loop(0)
        if !isEmpty then
            loop(this, elements, 0, 0)
    end copyTo

    /** Converts this Chunk to an Array.
      *
      * @return
      *   an Array containing all elements of this Chunk
      */
    override def toArray[B >: A: ClassTag]: Array[B] =
        val array = new Array[B](length)
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

    /** An indexed version of Chunk that provides O(1) access to elements.
      *
      * @tparam A
      *   the type of elements in the Indexed Chunk
      */
    sealed abstract class Indexed[A] extends Chunk[A]:
        self =>

        //////////////////
        // O(1) methods //
        //////////////////

        /** Returns the element at the specified index.
          *
          * @param i
          *   the index of the element to return
          * @return
          *   the element at the specified index
          * @throws IndexOutOfBoundsException
          *   if the index is out of bounds
          */
        def apply(i: Int): A

        /** Returns the first element of the Indexed Chunk.
          *
          * @return
          *   the first element
          * @throws NoSuchElementException
          *   if the Indexed Chunk is empty
          */
        final override def head: A =
            if isEmpty then
                throw new NoSuchElementException
            else
                apply(0)

        /** Returns a new Indexed Chunk containing all elements except the first.
          *
          * @return
          *   a new Indexed Chunk without the first element
          */
        final override def tail: Indexed[A] =
            if length <= 1 then cachedEmpty.asInstanceOf[Indexed[A]]
            else
                this match
                    case Tail(chunk, offset, length) =>
                        Tail(chunk, offset + 1, length - 1)
                    case c =>
                        Tail(c, 1, length - 1)

        override def iterator: Iterator[A] =
            new Iterator[A]:
                var curr    = 0
                def hasNext = curr < self.length
                def next() =
                    val r = self(curr)
                    curr += 1
                    r
                end next

    end Indexed

    /** Returns an empty Chunk.
      *
      * @tparam A
      *   the type of elements in the Chunk
      * @return
      *   an empty Chunk of type A
      */
    def empty[A]: Chunk[A] =
        cachedEmpty.asInstanceOf[Chunk[A]]

    /** Creates a Chunk from a variable number of elements.
      *
      * @tparam A
      *   the type of elements in the Chunk
      * @param values
      *   the elements to include in the Chunk
      * @return
      *   a new Chunk containing the provided values
      */
    def apply[A](values: A*): Chunk[A] =
        from(values)

    /** Creates a Chunk from an Array of elements.
      *
      * @tparam A
      *   the type of elements in the Array (must be a subtype of AnyRef)
      * @param values
      *   the Array to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the elements from the Array
      */
    def from[A](values: Array[A]): Chunk.Indexed[A] =
        if values.isEmpty then cachedEmpty.asInstanceOf[Chunk.Indexed[A]]
        else
            Compact(Array.copyAs(values, values.length)(using ClassTag.AnyRef).asInstanceOf[Array[A]])
    end from

    private[kyo] def fromNoCopy[A](values: Array[A]): Chunk.Indexed[A] =
        if values.isEmpty then cachedEmpty.asInstanceOf[Chunk.Indexed[A]]
        else
            Compact(values)

    /** Creates a Chunk from a Seq of elements.
      *
      * @tparam A
      *   the type of elements in the Seq
      * @param values
      *   the Seq to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the elements from the Seq
      */
    def from[A](values: Seq[A]): Chunk.Indexed[A] =
        if values.isEmpty then cachedEmpty.asInstanceOf[Chunk.Indexed[A]]
        else
            values match
                case seq: Chunk.Indexed[A] @unchecked => seq
                case seq: IndexedSeq[A]               => FromSeq(seq)
                case _                                => Compact(values.toArray(using ClassTag.Any.asInstanceOf[ClassTag[A]]))

    /** Creates a Chunk filled with a specified number of copies of a given value.
      *
      * @tparam A
      *   the type of elements in the Chunk
      * @param n
      *   the number of times to repeat the value
      * @param v
      *   the value to fill the Chunk with
      * @return
      *   a new Chunk containing n copies of v
      */
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
            val length = value.length
            override def apply(i: Int) =
                if i >= length || i < 0 then
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
                if i >= length || i < 0 then
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
