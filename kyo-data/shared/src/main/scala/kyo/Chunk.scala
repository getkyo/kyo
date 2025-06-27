package kyo

import Chunk.Indexed
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.IterableFactoryDefaults
import scala.collection.StrictOptimizedSeqFactory
import scala.collection.immutable.StrictOptimizedSeqOps
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

/** An immutable, efficient sequence of elements with optimized structural operations.
  *
  * Chunk provides O(1) operations for many common operations like `take`, `drop`, and `slice` without copying the underlying data. It also
  * provides efficient concatenation and element access through specialized implementations.
  *
  * Chunk uses structural sharing to optimize operations that would normally require copying data. This makes it particularly valuable when
  * performing slicing operations or working with large collections.
  *
  * Note that Chunk boxes primitive types, unlike KArray which doesn't. This means KArray is more memory-efficient when working with
  * primitive values like Int, Long, Double, etc. Consider using KArray instead when you need simpler, more predictable performance
  * characteristics or when memory efficiency with primitive types is your primary concern.
  *
  * @tparam A
  *   the type of elements in this Chunk
  */
sealed abstract class Chunk[+A]
    extends Seq[A]
    with StrictOptimizedSeqOps[A, Chunk, Chunk[A]]
    with IterableFactoryDefaults[A, Chunk]
    derives CanEqual:
    self =>

    import Chunk.internal.*

    private inline given [B]: ClassTag[B] = erasedTag[B]

    //////////////////
    // O(1) methods //
    //////////////////

    /** Checks if the Chunk is empty.
      *
      * @return
      *   true if the Chunk contains no elements, false otherwise
      */
    final override def isEmpty: Boolean = length == 0

    final override def knownSize: Int = length

    override def length: Int

    /** Takes the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to take
      * @return
      *   a new Chunk containing the first n elements
      */
    final override def take(n: Int): Chunk[A] =
        if n >= length then self
        else dropLeftAndRight(0, length - Math.max(0, n))

    /** Takes the last n elements of the Chunk.
      *
      * @param n
      *   the number of elements to take
      * @return
      *   a new Chunk containing the last n elements
      */
    override def takeRight(n: Int): Chunk[A] =
        if n == length then this
        else dropLeftAndRight(length - Math.min(Math.max(0, n), length), 0)

    /** Drops the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the first n elements removed
      */
    final override def drop(n: Int): Chunk[A] =
        dropLeft(n)

    /** Drops the first n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the first n elements removed
      */
    final def dropLeft(n: Int): Chunk[A] =
        dropLeftAndRight(Math.min(length, n), 0)

    /** Drops the last n elements of the Chunk.
      *
      * @param n
      *   the number of elements to drop
      * @return
      *   a new Chunk with the last n elements removed
      */
    final override def dropRight(n: Int): Chunk[A] =
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
    final override def slice(from: Int, until: Int): Chunk[A] =
        dropLeftAndRight(from, length - Math.min(length, until))

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
            val len       = c.length
            val remaining = len - left - right
            if remaining <= 0 then Chunk.empty
            else if len <= remaining then c
            else
                c match
                    case Drop(chunk, dropLeft, dropRight, _) =>
                        loop(chunk, left + dropLeft, right + dropRight)
                    case Append(chunk, value, length) if right > 0 =>
                        loop(chunk, left, right - 1)
                    case _ =>
                        Drop(c, left, right, remaining)
            end if
        end loop
        loop(self, Math.max(0, left), Math.max(0, right))
    end dropLeftAndRight

    /** Appends an element to the end of the Chunk.
      *
      * @param v
      *   the element to append
      * @return
      *   a new Chunk with the element appended
      */
    final def append[B >: A](b: B): Chunk[B] =
        Append(self, b, length + 1)

    final override def appended[B >: A](b: B): Chunk[B] =
        append(b)

    /** Returns the first element of the Chunk wrapped in a Maybe.
      *
      * @return
      *   Maybe containing the first element if the Chunk is non-empty, or Maybe.empty if the Chunk is empty
      */
    final def headMaybe: Maybe[A] =
        Maybe.when(nonEmpty)(head)

    override def iterableFactory: StrictOptimizedSeqFactory[Chunk] = Chunk

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
                case c: Append[?] =>
                    if index == c.length - 1 then
                        c.value
                    else
                        loop(c.chunk, index)
                case c: Drop[A] @unchecked =>
                    loop(c.chunk, index + c.dropLeft)
                case c: Indexed[A] @unchecked =>
                    c(index)
        loop(self, self.length - 1)
    end last

    /** Returns the last element of the Chunk wrapped in a Maybe.
      *
      * @return
      *   Maybe containing the last element if the Chunk is non-empty, or Maybe.empty if the Chunk is empty
      */
    def lastMaybe: Maybe[A] =
        Maybe.when(nonEmpty)(last)

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
    override def apply(index: Int): A =
        def outOfBounds = throw new IndexOutOfBoundsException(s"Index out of range: $index")
        @tailrec
        def loop(c: Chunk[A], index: Int): A =
            if index < 0 || index >= c.length then outOfBounds
            else
                c match
                    case c: Indexed[A] @unchecked => c(index)
                    case Drop(c, left, right, _)  => loop(c, index + left)
                    case Append(c, value, len)    => if index == len - 1 then value else loop(c, index)
                end match
        end loop
        loop(self, index)
    end apply

    /** Returns an iterator over the elements of the Chunk.
      *
      * @return
      *   an Iterator[A] over the elements of the Chunk
      */
    override def iterator: Iterator[A] = toIndexed.iterator

    /** Concatenates this Chunk with another Chunk.
      *
      * @param other
      *   the Chunk to concatenate with this one
      * @return
      *   a new Chunk containing all elements from this Chunk followed by all elements from the other Chunk
      */
    final def concat[B >: A](other: Chunk[B]): Chunk[B] =
        if isEmpty then other
        else if other.isEmpty then self
        else
            val len   = length
            val array = new Array[B](len + other.length)
            self.copyTo(array, 0)
            other.copyTo(array, len)
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
    final def changes[B >: A](first: Maybe[B])(using CanEqual[B, B]): Chunk[B] =
        if isEmpty then Chunk.empty
        else
            val len     = self.length
            val indexed = self.toIndexed
            @tailrec def loop(idx: Int, prev: Maybe[B], acc: Chunk[B]): Chunk[B] =
                if idx < len then
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
    final def toIndexed[B >: A]: Indexed[B] =
        if isEmpty then Indexed.empty[B]
        else
            self match
                case c: Indexed[B] @unchecked => c
                case _                        => Compact(toArray)

    /** Flattens a Chunk of Chunks into a single Chunk.
      *
      * @param ev
      *   evidence that A is a Chunk[B]
      * @return
      *   a flattened Chunk
      */
    final def flattenChunk[B](using ev: A <:< Chunk[B]): Chunk[B] =
        if isEmpty then Chunk.empty
        else
            val nested = self.toArrayInternal

            @tailrec def totalSize(idx: Int = 0, acc: Int = 0): Int =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    totalSize(idx + 1, acc + chunk.length)
                else
                    acc

            val unnested = new Array[B](totalSize())(using erasedTag[B])

            @tailrec def copy(idx: Int = 0, offset: Int = 0): Unit =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    chunk.copyTo(unnested, offset)
                    copy(idx + 1, offset + chunk.length)
            copy()

            Compact(unnested)
    end flattenChunk

    override def map[B](f: A => B): Chunk[B] =
        val builder = ChunkBuilder.initTransform[A, B]((b, v) => b.addOne(f(v)))
        foreach(builder)
        builder.result()
    end map

    override def filter(pred: A => Boolean): Chunk[A] =
        val builder =
            ChunkBuilder.initTransform[A, A] { (b, v) =>
                if pred(v) then b.addOne(v)
            }
        foreach(builder)
        builder.result()
    end filter

    override def takeWhile(pred: A => Boolean): Chunk[A] =
        val builder =
            ChunkBuilder.initTransform[A, A] {
                (b, v) => if pred(v) then b.addOne(v) else throw StopException
            }
        try foreach(builder)
        catch
            case ex if StopException.equals(ex) =>
        builder.result()
    end takeWhile

    override def dropWhile(p: A => Boolean): Chunk[A] =
        var collect = false
        val builder =
            ChunkBuilder.initTransform[A, A] {
                (b, v) =>
                    collect ||= !p(v)
                    if collect then b.addOne(v)
            }
        foreach(builder)
        builder.result()
    end dropWhile

    override def collect[B](pf: PartialFunction[A, B]): Chunk[B] =
        val builder =
            ChunkBuilder.initTransform[A, B] { (b, v) =>
                if pf.isDefinedAt(v) then b.addOne(pf(v))
            }
        foreach(builder)
        builder.result()
    end collect

    override def foldLeft[B](z: B)(op: (B, A) => B): B =
        var curr = z
        foreach { v =>
            curr = op(curr, v)
        }
        curr
    end foldLeft

    override def foreach[U](f: A => U): Unit =
        if !isEmpty then
            val buffer = ChunkBuilder.acquireBuffer[A]()
            @tailrec def loop(c: Chunk[A], dropLeft: Int, dropRight: Int): Unit =
                c match
                    case c: Append[A] @unchecked =>
                        if dropRight > 0 then
                            loop(c.chunk, dropLeft, dropRight - 1)
                        else if c.length > dropLeft then
                            buffer.add(c.value)
                            loop(c.chunk, dropLeft, dropRight)
                        else
                            loop(c.chunk, dropLeft, dropRight)
                    case c: Drop[A] @unchecked =>
                        loop(c.chunk, dropLeft + c.dropLeft, dropRight + c.dropRight)
                    case c: Tail[A] @unchecked =>
                        loop(c.chunk, dropLeft + c.offset, dropRight)
                    case c: Compact[A] @unchecked =>
                        val array  = c.array
                        val length = c.array.length - dropRight
                        @tailrec def loop(idx: Int): Unit =
                            if idx < length then
                                discard(f(array(idx)))
                                loop(idx + 1)
                        loop(dropLeft)
                    case c: FromSeq[A] @unchecked =>
                        val seq    = c.value
                        val length = seq.length - dropRight
                        @tailrec def loop(index: Int): Unit =
                            if index < length then
                                discard(f(seq(index)))
                                loop(index + 1)
                        loop(dropLeft)
                    case c: Single[A] @unchecked =>
                        if dropLeft == 0 && dropRight == 0 then
                            discard(f(c.value))
            loop(self, 0, 0)

            @tailrec def flush(): Unit =
                val v = buffer.pollLast()
                if !isNull(v) then
                    discard(f(v))
                    flush()
            end flush
            flush()

            ChunkBuilder.releaseBuffer(buffer)
    end foreach

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
            if end > 0 then
                c match
                    case c: Append[A] @unchecked =>
                        if dropRight > 0 then
                            loop(c.chunk, end, dropLeft, dropRight - 1)
                        else if start + end >= c.length - dropLeft then
                            array(start + end - 1) = c.value
                            loop(c.chunk, end - 1, dropLeft, dropRight)
                        else
                            loop(c.chunk, end, dropLeft, dropRight)
                    case c: Drop[A] @unchecked =>
                        loop(c.chunk, end, dropLeft + c.dropLeft, dropRight + c.dropRight)
                    case c: Tail[A] @unchecked =>
                        loop(c.chunk, end, dropLeft + c.offset, dropRight)
                    case c: Compact[A] @unchecked =>
                        val l = c.array.length
                        if l > 0 then
                            System.arraycopy(c.array, dropLeft, array, start, l - dropRight - dropLeft)
                    case c: FromSeq[A] @unchecked =>
                        val seq    = c.value
                        val length = Math.min(end, c.value.length - dropLeft - dropRight)
                        @tailrec def loop(index: Int): Unit =
                            if index < length then
                                array(start + index) = seq(index + dropLeft)
                                loop(index + 1)
                        loop(0)
                    case c: Single[A] @unchecked =>
                        if dropLeft == 0 && dropRight == 0 then array(start) = c.value
        if !isEmpty then
            loop(self, elements, 0, 0)
    end copyTo

    /** Converts this Chunk to an Array.
      *
      * @return
      *   an Array containing all elements of this Chunk
      */
    final override def toArray[B >: A: ClassTag]: Array[B] =
        val array = new Array[B](length)
        copyTo(array, 0)
        array
    end toArray

    final private def toArrayInternal[B >: A]: Array[B] =
        self match
            case c if c.isEmpty =>
                cachedEmpty.array.asInstanceOf[Array[B]]
            case c: Compact[B] @unchecked => c.array
            case c                        => c.toArray

end Chunk
object Chunk extends StrictOptimizedSeqFactory[Chunk]:
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
            val len = length
            if len <= 1 then cachedEmpty.asInstanceOf[Indexed[A]]
            else
                self match
                    case Tail(chunk, offset, length) =>
                        Tail(chunk, offset + 1, length - 1)
                    case c =>
                        Tail(c, 1, len - 1)
            end if
        end tail

        final override def iterator: Iterator[A] =
            self match
                case c: Compact[A] @unchecked => c.array.iterator
                case c: FromSeq[A] @unchecked => c.value.iterator
                case c: Tail[A] @unchecked    => c.chunk.iterator.drop(c.offset)
                case c: Single[A] @unchecked  => Iterator.single(c.value)
        final override def iterableFactory: StrictOptimizedSeqFactory[Indexed] = Indexed

    end Indexed
    object Indexed extends StrictOptimizedSeqFactory[Indexed]:
        /** Returns an empty Chunk.
          *
          * @tparam A
          *   the type of elements in the Chunk
          * @return
          *   an empty Chunk of type A
          */
        def empty[A]: Indexed[A] = cachedEmpty.asInstanceOf[Indexed[A]]

        /** Returns a single value Chunk.
          *
          * @tparam A
          *   the type of elements in the Chunk
          * @return
          *   a single value Chunk of type A
          */
        private[Chunk] def single[A](a: A): Indexed[A] = Single(a)

        def from[A](source: Array[A]): Indexed[A] =
            source.length match
                case 0 => empty[A]
                case 1 => single(source(0))
                case _ => Compact(Array.copyAs(source, source.length)(using erasedTag[A]))
        end from

        /** Creates an Indexed Chunk from an `IterableOnce`.
          *
          * NOTE: This method will **mutate** the source `IterableOnce` if it is a mutable collection.
          *
          * @tparam A
          *   the type of elements in the IterableOnce
          * @param source
          *   the IterableOnce to create the Chunk from
          * @return
          *   a new Chunk.Indexed containing the elements from the IterableOnce
          */
        def from[A](source: IterableOnce[A]): Indexed[A] =
            source.knownSize match
                case 0 => empty[A]
                case 1 => single(source.iterator.next())
                case _ =>
                    source match
                        case chunk: Chunk.Indexed[A] @unchecked => chunk
                        case seq: IndexedSeq[A]                 => FromSeq(seq)
                        case _ =>
                            val array = source.iterator.toArray(using erasedTag[A])
                            array.length match
                                case 0 => empty[A]
                                case 1 => single(array(0))
                                case _ => Compact(array)
                            end match
            end match
        end from

        /** Creates an Indexed Chunk from a Maybe.
          *
          * @tparam A
          *   the type of the element
          * @param source
          *   the Maybe to create the Chunk from
          * @return
          *   a new Chunk.Indexed containing the single element
          */
        @targetName("fromMaybe")
        def from[A](source: Maybe[A]): Indexed[A] =
            source match
                case Absent         => cachedEmpty.asInstanceOf[Indexed[A]]
                case Present(value) => single(value)

        /** Creates an Indexed Chunk from an Option.
          *
          * @tparam A
          *   the type of the element
          * @param source
          *   the Option to create the Chunk from
          * @return
          *   a new Chunk.Indexed containing the single element
          */
        def from[A](source: Option[A]): Indexed[A] =
            source match
                case None        => cachedEmpty.asInstanceOf[Indexed[A]]
                case Some(value) => single(value)

        def newBuilder[A]: collection.mutable.Builder[A, Indexed[A]] = ChunkBuilder.init[A]
    end Indexed

    /** Returns an empty Chunk.
      *
      * @tparam A
      *   the type of elements in the Chunk
      * @return
      *   an empty Chunk of type A
      */
    def empty[A]: Chunk[A] = Indexed.empty[A]

    /** Creates a Chunk from an Array of elements.
      *
      * @tparam A
      *   the type of elements in the Array (must be a subtype of AnyRef)
      * @param values
      *   the Array to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the elements from the Array
      */
    def from[A](values: Array[A]): Chunk.Indexed[A] = Indexed.from(values)

    private[kyo] def fromNoCopy[A](values: Array[A]): Chunk.Indexed[A] =
        if values.isEmpty then Indexed.empty[A]
        else if values.length == 1 then Indexed.single(values(0))
        else
            Compact(values)

    /** Creates a Chunk from an `IterableOnce`.
      *
      * NOTE: This method will **mutate** the source `IterableOnce` if it is a mutable collection.
      *
      * @tparam A
      *   the type of elements in the IterableOnce
      * @param source
      *   the IterableOnce to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the elements from the IterableOnce
      */
    def from[A](source: IterableOnce[A]): Chunk[A] = Indexed.from(source)

    /** Creates a Chunk from a Maybe.
      *
      * @tparam A
      *   the type of the element
      * @param source
      *   the Maybe to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the single element
      */
    def from[A](source: Maybe[A]): Chunk[A] = Indexed.from(source)

    /** Creates a Chunk from an Option.
      *
      * @tparam A
      *   the type of the element
      * @param source
      *   the Option to create the Chunk from
      * @return
      *   a new Chunk.Indexed containing the single element
      */
    def from[A](source: Option[A]): Chunk[A] = Indexed.from(source)

    /** Creates a new **mutable** builder for constructing Chunks.
      *
      * @tparam A
      *   the type of elements in the Chunk
      * @return
      *   a mutable Builder that constructs a Chunk[A]
      */
    override def newBuilder[A]: collection.mutable.Builder[A, Chunk[A]] = ChunkBuilder.init[A]

    private[kyo] object internal:

        case object StopException extends NoStackTrace

        inline def erasedTag[A]: ClassTag[A] = ClassTag.Any.asInstanceOf[ClassTag[A]]

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

            override def toString = value.mkString("Chunk.Indexed(", ", ", ")")
        end FromSeq

        final case class Compact[A](
            array: Array[A]
        ) extends Indexed[A]:
            def length = array.length

            override def foreach[U](f: A => U): Unit =
                @tailrec def loop(idx: Int): Unit =
                    if idx < array.length then
                        discard(f(array(idx)))
                        loop(idx + 1)
                loop(0)
            end foreach

            override def map[B](f: A => B): Chunk[B] =
                val r = new Array[Any](array.length).asInstanceOf[Array[B]]
                @tailrec def loop(idx: Int): Unit =
                    if idx < array.length then
                        r(idx) = f(array(idx))
                        loop(idx + 1)
                loop(0)
                Compact(r)
            end map

            override def apply(i: Int) =
                if i >= length || i < 0 then
                    throw new IndexOutOfBoundsException(s"Index out of range: $i")
                else
                    array(i)

            override def toString = array.mkString("Chunk.Indexed(", ", ", ")")
        end Compact

        final case class Tail[A](
            chunk: Indexed[A],
            offset: Int,
            length: Int
        ) extends Indexed[A]:
            override def apply(i: Int): A = chunk(i + offset)
            override def toString         = mkString("Chunk.Indexed(", ", ", ")")
        end Tail

        final case class Drop[A](
            chunk: Chunk[A],
            dropLeft: Int,
            dropRight: Int,
            length: Int
        ) extends Chunk[A]:
            override def toString = mkString("Chunk(", ", ", ")")
        end Drop

        final case class Append[A](
            chunk: Chunk[A],
            value: A,
            length: Int
        ) extends Chunk[A]:
            override def toString = mkString("Chunk(", ", ", ")")
        end Append

        /** A Chunk containing exactly one element.
          *
          * @param value
          *   the single element
          */
        final case class Single[A](value: A) extends Indexed[A]:
            override def length: Int = 1
            override def apply(i: Int): A =
                if i == 0 then value else throw new IndexOutOfBoundsException(s"Index out of range: $i")
            override def toString                                      = s"Chunk.Single($value)"
            override def map[B](f: A => B): Chunk[B]                   = copy(f(value))
            override def flatMap[B](f: A => IterableOnce[B]): Chunk[B] = Indexed.from(f(value))
            override def foreach[U](f: A => U): Unit                   = discard(f(value))
        end Single
    end internal
end Chunk
