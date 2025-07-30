package kyo

import java.util.Arrays
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

/** An efficient, immutable array-backed sequence of elements optimized to avoid boxing.
  *
  * Span is similar to Scala's built-in IArray (immutable array), but provides optimized methods for common operations and additional
  * utility functions. It offers better performance characteristics than standard Scala collections for many use cases while maintaining
  * immutability semantics. In practice, using Span is akin to using Array directly but with a richer and safer immutable API.
  *
  * Span is essentially a thin wrapper around arrays, making it ideal for scenarios where you need predictable, array-like performance with
  * O(1) indexing and iteration. It excels in memory-sensitive contexts due to its minimal overhead.
  *
  * A key advantage of Span over Chunk is that Span doesn't box primitive types, while Chunk does. This makes Span more memory-efficient
  * when working with primitive values like Int, Long, Double, etc.
  *
  * Important: Unlike Chunk, Span is NOT part of Scala's collection hierarchy. It doesn't extend Seq or any other collection trait, which
  * means it won't work with methods expecting standard Scala collections. Consider using Chunk instead when you need compatibility with
  * Scala's collection library or when you require efficient structural operations like slicing without copying data.
  *
  * Span deliberately omits APIs that would force boxing of primitive values. For example, there is no `zip` method that returns tuples, as
  * this would box primitives. Instead, specialized methods like `existsZip` and `forallZip` accept functions with multiple parameters to
  * operate on corresponding elements without creating intermediate objects.
  *
  * Span is also invariant due to the underlying specialized array implementation - you cannot treat a Span[Int] as a Span[AnyVal] without
  * losing specialization benefits. Consider using Chunk instead when you need compatibility with Scala's collection library or when you
  * require efficient structural operations like slicing without copying data.
  *
  * @tparam A
  *   the type of elements in this Span
  */
opaque type Span[A] = Array[A]

object Span:

    import internal.*

    /** Creates a new Span containing the given elements.
      *
      * @param values
      *   the elements to include in the Span
      * @return
      *   a new Span containing the specified elements
      */
    def apply[A: ClassTag](values: A*): Span[A] =
        values match
            case values: ArraySeq[A] => values.unsafeArray.asInstanceOf[Array[A]]
            case values              => values.toArray

    /** Returns an empty Span.
      *
      * @return
      *   an empty Span of type A
      */
    def empty[A: ClassTag as ct]: Span[A] =
        if cachedEmpty.contains(ct) then
            cachedEmpty(ct).asInstanceOf[Array[A]]
        else
            Array.empty

    /** Creates a Span from an Array without copying the array.
      *
      * Note: This method does not create a defensive copy of the array, so changes to the original array will be reflected in the Span. Use
      * with caution.
      *
      * @param array
      *   the Array to create the Span from
      * @return
      *   a new Span sharing the same underlying array
      */
    def fromUnsafe[A](array: Array[A]): Span[A] = array

    /** Creates a Span from an IArray.
      *
      * Since IArray is already immutable, this operation is safe.
      *
      * @param array
      *   the IArray to create the Span from
      * @return
      *   a new Span containing the elements from the IArray
      */
    def from[A](array: IArray[A])(using Discriminator): Span[A] =
        array.unsafeArray.asInstanceOf[Array[A]]

    /** Creates a Span from an Array by copying the array.
      *
      * @param array
      *   the Array to create the Span from
      * @return
      *   a new Span containing a copy of the elements from the Array
      */
    def from[A: ClassTag](array: Array[A]): Span[A] =
        val copy = new Array[A](array.length)
        System.arraycopy(array, 0, copy, 0, array.length)
        copy
    end from

    /** Creates a Span from an IterableOnce.
      *
      * @param seq
      *   the IterableOnce to create the Span from
      * @return
      *   a new Span containing the elements from the IterableOnce
      */
    def from[A: ClassTag](seq: IterableOnce[A]): Span[A] =
        seq.iterator.toArray

    extension [A](self: Span[A])

        /** Returns the number of elements in this Span.
          *
          * @return
          *   the number of elements in this Span
          */
        inline def size: Int = self.length

        /** Checks if this Span is empty.
          *
          * @return
          *   true if the Span contains no elements, false otherwise
          */
        inline def isEmpty: Boolean = size == 0

        /** Tests whether the Span is not empty.
          *
          * @return
          *   true if the Span contains at least one element, false otherwise
          */
        inline def nonEmpty: Boolean = !isEmpty

        /** Returns the element at the specified index.
          *
          * @param idx
          *   the index of the element to return
          * @return
          *   the element at the specified index
          */
        inline def apply(idx: Int): A = self(idx)

        /** Selects the first element.
          *
          * @return
          *   the first element if the Span is not empty, Absent otherwise
          */
        inline def head: Maybe[A] =
            Maybe.when(nonEmpty)(self(0))

        /** Selects the last element.
          *
          * @return
          *   the last element if the Span is not empty, Absent otherwise
          */
        inline def last: Maybe[A] =
            Maybe.when(nonEmpty)(self(size - 1))

        /** The rest of the Span without its first element.
          *
          * @return
          *   a new Span containing all elements except the first
          * @throws UnsupportedOperationException
          *   if the Span is empty
          */
        inline def tail(using ct: ClassTag[A]): Maybe[Span[A]] =
            Maybe.when(nonEmpty)(slice(1, size)(using ct))

        /** Tests whether this Span contains a given value as an element.
          *
          * @param elem
          *   the element to test for membership
          * @return
          *   true if this Span contains an element equal to elem, false otherwise
          */
        inline def contains(elem: A)(using CanEqual[A, A]): Boolean =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx != size && (self(idx) == elem || loop(idx + 1))
            loop(0)
        end contains

        /** Finds index of first occurrence of some value in this Span after or at some start index.
          *
          * @param elem
          *   the element to search for
          * @param from
          *   the start index
          * @return
          *   the index of the first element equal to elem, or -1 if not found
          */
        inline def indexOf(elem: A, from: Int = 0)(using CanEqual[A, A]): Maybe[Int] =
            val size  = self.length
            val start = math.max(0, from)
            @tailrec def loop(idx: Int): Maybe[Int] =
                if idx >= size then Absent
                else if self(idx) == elem then Present(idx)
                else loop(idx + 1)
            loop(start)
        end indexOf

        /** Finds index of last occurrence of some value in this Span before or at a given end index.
          *
          * @param elem
          *   the element to search for
          * @param end
          *   the end index (inclusive)
          * @return
          *   the index of the last element equal to elem, or -1 if not found
          */
        inline def lastIndexOf(elem: A, end: Int = -1)(using CanEqual[A, A]): Maybe[Int] =
            val size   = self.length
            val endIdx = if end < 0 then size - 1 else math.min(end, size - 1)
            @tailrec def loop(idx: Int): Maybe[Int] =
                if idx < 0 then Absent
                else if self(idx) == elem then Present(idx)
                else loop(idx - 1)
            loop(endIdx)
        end lastIndexOf

        /** Finds index of the first element satisfying some predicate after or at some start index.
          *
          * @param p
          *   the predicate to test elements with
          * @param from
          *   the start index
          * @return
          *   the index of the first element satisfying p, or -1 if none found
          */
        inline def indexWhere(inline p: A => Boolean, from: Int = 0): Maybe[Int] =
            val size  = self.length
            val start = math.max(0, from)
            @tailrec def loop(idx: Int): Maybe[Int] =
                if idx >= size then Absent
                else if p(self(idx)) then Present(idx)
                else loop(idx + 1)
            loop(start)
        end indexWhere

        /** Finds index of last element satisfying some predicate before or at given end index.
          *
          * @param p
          *   the predicate to test elements with
          * @param end
          *   the end index (inclusive)
          * @return
          *   the index of the last element satisfying p, or -1 if none found
          */
        inline def lastIndexWhere(inline p: A => Boolean, end: Int = -1): Maybe[Int] =
            val size   = self.length
            val endIdx = if end < 0 then size - 1 else math.min(end, size - 1)
            @tailrec def loop(idx: Int): Maybe[Int] =
                if idx < 0 then Absent
                else if p(self(idx)) then Present(idx)
                else loop(idx - 1)
            loop(endIdx)
        end lastIndexWhere

        /** Finds the first element of the Span satisfying a predicate, if any.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   a Maybe value containing the first element satisfying p, or Absent if none found
          */
        inline def find(inline p: A => Boolean): Maybe[A] =
            val size = self.length
            def loop(idx: Int): Maybe[A] =
                if idx >= size then Absent
                else if p(self(idx)) then Present(self(idx))
                else loop(idx + 1)
            loop(0)
        end find

        /** Counts the number of elements in this Span which satisfy a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   the number of elements satisfying the predicate p
          */
        inline def count(inline p: A => Boolean): Int =
            val size = self.length
            @tailrec def loop(idx: Int, acc: Int): Int =
                if idx >= size then acc
                else if p(self(idx)) then loop(idx + 1, acc + 1)
                else loop(idx + 1, acc)
            loop(0, 0)
        end count

        /** Apply f to each element for its side effects.
          *
          * @param f
          *   the function to apply to each element
          */
        inline def foreach(inline f: A => Any): Unit =
            val size = self.length
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    discard(f(self(idx)))
                    loop(idx + 1)
            loop(0)
        end foreach

        /** Checks if this Span is equal to another Span.
          *
          * @param other
          *   the Span to compare with
          * @return
          *   true if the Spans contain the same elements in the same order, false otherwise
          */
        inline def is(other: Span[A])(using CanEqual[A, A]): Boolean =
            val size = self.length
            size == other.length && {
                @tailrec def loop(idx: Int): Boolean =
                    idx == size || (self(idx) == other(idx) && loop(idx + 1))
                loop(0)
            }
        end is

        /** Checks if all elements in this Span satisfy the given predicate.
          *
          * @param f
          *   the predicate to test each element with
          * @return
          *   true if all elements satisfy the predicate, false otherwise
          */
        inline def forall(inline f: A => Boolean): Boolean =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx == size || (f(self(idx)) && loop(idx + 1))
            loop(0)
        end forall

        /** Checks if at least one element in this Span satisfies the given predicate.
          *
          * @param f
          *   the predicate to test each element with
          * @return
          *   true if at least one element satisfies the predicate, false otherwise
          */
        inline def exists(inline f: A => Boolean): Boolean =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx != size && (f(self(idx)) || loop(idx + 1))
            loop(0)
        end exists

        /** Applies a function to each element of this Span and returns a new Span with the results.
          *
          * @param f
          *   the function to apply to each element
          * @return
          *   a new Span containing the results of applying the function to each element
          */
        inline def map[B: ClassTag](inline f: A => B): Span[B] =
            val size = self.length
            val r    = new Array[B](size)
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    r(idx) = f(self(idx))
                    loop(idx + 1)
            loop(0)
            r
        end map

        /** Builds a new Span by applying a function to all elements and using the elements of the resulting collections.
          *
          * @param f
          *   the function to apply to each element
          * @return
          *   a new Span containing the concatenated results
          */
        inline def flatMap[B: ClassTag](inline f: A => Span[B]): Span[B] =
            val size = self.length
            if size == 0 then Span.empty[B]
            else
                val spans     = new Array[Span[B]](size)
                var totalSize = 0
                @tailrec def collectLoop(idx: Int): Unit =
                    if idx < size then
                        val span = f(self(idx))
                        spans(idx) = span
                        totalSize += span.length
                        collectLoop(idx + 1)
                collectLoop(0)

                if totalSize == 0 then Span.empty[B]
                else
                    val result = new Array[B](totalSize)
                    @tailrec def populateLoop(idx: Int, writeIdx: Int): Unit =
                        if idx < size then
                            val span     = spans(idx)
                            val spanSize = span.length
                            if spanSize > 0 then
                                System.arraycopy(span.toArrayUnsafe, 0, result, writeIdx, spanSize)
                            populateLoop(idx + 1, writeIdx + spanSize)
                    populateLoop(0, 0)
                    result
                end if
            end if
        end flatMap

        /** Selects all elements of this Span which satisfy a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   a new Span containing all elements that satisfy the predicate
          */
        inline def filter(inline p: A => Boolean)(using ClassTag[A]): Span[A] =
            val size = self.length
            if size == 0 then Span.empty[A]
            else
                val temp = new Array[A](size)
                @tailrec def loop(idx: Int, writeIdx: Int): Int =
                    if idx < size then
                        val elem = self(idx)
                        if p(elem) then
                            temp(writeIdx) = elem
                            loop(idx + 1, writeIdx + 1)
                        else
                            loop(idx + 1, writeIdx)
                        end if
                    else writeIdx
                val actualSize = loop(0, 0)
                if actualSize == size then temp
                else if actualSize == 0 then Span.empty[A]
                else
                    val result = new Array[A](actualSize)
                    System.arraycopy(temp, 0, result, 0, actualSize)
                    result
                end if
            end if
        end filter

        /** Selects all elements of this Span which do not satisfy a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   a new Span containing all elements that do not satisfy the predicate
          */
        inline def filterNot(inline p: A => Boolean)(using ct: ClassTag[A]): Span[A] =
            self.filter(!p(_))(using ct)

        /** Selects the interval of elements between the given indices.
          *
          * @param from
          *   the lowest index to include
          * @param until
          *   the lowest index to exclude
          * @return
          *   a Span containing the elements from index from up to but not including index until
          */
        def slice(from: Int, until: Int)(using ClassTag[A]): Span[A] =
            val size  = self.length
            val start = math.max(0, from)
            val end   = math.min(size, until)
            val len   = math.max(0, end - start)
            if len == 0 then Span.empty[A]
            else
                val r = new Array[A](len)
                System.arraycopy(self, start, r, 0, len)
                r
            end if
        end slice

        /** An Span containing the first n elements of this Span.
          *
          * @param n
          *   the number of elements to take
          * @return
          *   a Span containing the first n elements
          */
        inline def take(n: Int)(using ct: ClassTag[A]): Span[A] =
            slice(0, n)(using ct)

        /** An Span containing the last n elements of this Span.
          *
          * @param n
          *   the number of elements to take
          * @return
          *   a Span containing the last n elements
          */
        inline def takeRight(n: Int)(using ct: ClassTag[A]): Span[A] =
            slice(math.max(0, size - n), size)(using ct)

        /** Takes longest prefix of elements that satisfy a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   the longest prefix of this Span whose elements all satisfy the predicate p
          */
        inline def takeWhile(inline p: A => Boolean)(using ct: ClassTag[A]): Span[A] =
            val size = self.length
            @tailrec def findEnd(idx: Int): Int =
                if idx >= size then size
                else if !p(self(idx)) then idx
                else findEnd(idx + 1)
            val end = findEnd(0)
            if end == size then self
            else if end == 0 then Span.empty[A]
            else
                val r = new Array[A](end)
                System.arraycopy(self, 0, r, 0, end)
                r
            end if
        end takeWhile

        /** The rest of the Span without its n first elements.
          *
          * @param n
          *   the number of elements to drop
          * @return
          *   a Span containing all elements except the first n ones
          */
        inline def drop(n: Int)(using ct: ClassTag[A]): Span[A] =
            slice(n, size)(using ct)

        /** The rest of the Span without its n last elements.
          *
          * @param n
          *   the number of elements to drop
          * @return
          *   a Span containing all elements except the last n ones
          */
        inline def dropRight(n: Int)(using ct: ClassTag[A]): Span[A] =
            slice(0, size - n)(using ct)

        /** Drops longest prefix of elements that satisfy a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   the longest suffix of this Span whose first element does not satisfy the predicate p
          */
        inline def dropWhile(inline p: A => Boolean)(using ct: ClassTag[A]): Span[A] =
            val size = self.length
            @tailrec def findStart(idx: Int): Int =
                if idx >= size then size
                else if !p(self(idx)) then idx
                else findStart(idx + 1)
            val start = findStart(0)
            if start >= size then Span.empty[A]
            else if start == 0 then self
            else
                val len = size - start
                val r   = new Array[A](len)
                System.arraycopy(self, start, r, 0, len)
                r
            end if
        end dropWhile

        /** Returns a new Span with the elements in reversed order.
          *
          * @return
          *   a Span with elements in reverse order
          */
        inline def reverse(using ClassTag[A]): Span[A] =
            val size = self.length
            val r    = new Array[A](size)
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    r(size - 1 - idx) = self(idx)
                    loop(idx + 1)
            loop(0)
            r
        end reverse

        /** A copy of this Span with one single replaced element.
          *
          * @param index
          *   the position of the replacement
          * @param elem
          *   the replacing element
          * @return
          *   a new Span with the element at position index replaced by elem
          */
        inline def update(index: Int, elem: A)(using ClassTag[A]): Span[A] =
            val size = self.length
            val r    = new Array[A](size)
            System.arraycopy(self, 0, r, 0, size)
            r(index) = elem
            r
        end update

        /** Splits this Span into two at a given position.
          *
          * @param n
          *   the position at which to split
          * @return
          *   a pair of Spans consisting of the first n elements and the remaining elements
          */
        inline def splitAt(n: Int)(using ct: ClassTag[A]): (Span[A], Span[A]) =
            (take(n)(using ct), drop(n)(using ct))

        /** Splits this Span into a prefix/suffix pair according to a predicate.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   a pair consisting of the longest prefix satisfying p and the remainder
          */
        inline def span(inline p: A => Boolean)(using ct: ClassTag[A]): (Span[A], Span[A]) =
            val size = self.length
            @tailrec def findSplit(idx: Int): Int =
                if idx >= size then size
                else if !p(self(idx)) then idx
                else findSplit(idx + 1)
            val split = findSplit(0)
            if split == size then (self, Span.empty[A])
            else if split == 0 then (Span.empty[A], self)
            else
                val prefix    = new Array[A](split)
                val suffixLen = size - split
                val suffix    = new Array[A](suffixLen)
                System.arraycopy(self, 0, prefix, 0, split)
                System.arraycopy(self, split, suffix, 0, suffixLen)
                (prefix, suffix)
            end if
        end span

        /** A pair of, first, all elements that satisfy predicate p and, second, all elements that do not.
          *
          * @param p
          *   the predicate to test elements with
          * @return
          *   a pair of Spans: the first contains all elements that satisfy p, the second contains all elements that do not
          */
        inline def partition(inline p: A => Boolean)(using ClassTag[A]): (Span[A], Span[A]) =
            val size = self.length
            if size == 0 then (Span.empty[A], Span.empty[A])
            else
                val trueTemp  = new Array[A](size)
                val falseTemp = new Array[A](size)
                @tailrec def loop(idx: Int, trueIdx: Int, falseIdx: Int): (Int, Int) =
                    if idx < size then
                        val elem = self(idx)
                        if p(elem) then
                            trueTemp(trueIdx) = elem
                            loop(idx + 1, trueIdx + 1, falseIdx)
                        else
                            falseTemp(falseIdx) = elem
                            loop(idx + 1, trueIdx, falseIdx + 1)
                        end if
                    else (trueIdx, falseIdx)
                val (trueSize, falseSize) = loop(0, 0, 0)
                val trueResult = if trueSize == 0 then Span.empty[A]
                else if trueSize == size then Span.fromUnsafe(trueTemp)
                else
                    val result = new Array[A](trueSize)
                    System.arraycopy(trueTemp, 0, result, 0, trueSize)
                    Span.fromUnsafe(result)
                val falseResult = if falseSize == 0 then Span.empty[A]
                else if falseSize == size then Span.fromUnsafe(falseTemp)
                else
                    val result = new Array[A](falseSize)
                    System.arraycopy(falseTemp, 0, result, 0, falseSize)
                    Span.fromUnsafe(result)
                (trueResult, falseResult)
            end if
        end partition

        /** Tests whether this Span starts with the given Span.
          *
          * @param that
          *   the Span to test
          * @param offset
          *   the index where the Span is searched
          * @return
          *   true if this Span has that as a prefix starting at offset, false otherwise
          */
        inline def startsWith[B >: A](that: Span[B], offset: Int = 0)(using CanEqual[A, B]): Boolean =
            val thatSize = that.length
            val thisSize = self.length
            offset >= 0 && offset + thatSize <= thisSize && {
                @tailrec def loop(idx: Int): Boolean =
                    idx >= thatSize || (self(offset + idx) == that(idx) && loop(idx + 1))
                loop(0)
            }
        end startsWith

        /** Tests whether this Span ends with the given Span.
          *
          * @param that
          *   the Span to test
          * @return
          *   true if this Span has that as a suffix, false otherwise
          */
        inline def endsWith[B >: A](that: Span[B])(using CanEqual[A, B]): Boolean =
            startsWith(that, size - that.length)

        /** Folds the elements of this Span using the specified associative binary operator.
          *
          * @param z
          *   the neutral element for the fold operation
          * @param op
          *   the binary operator
          * @return
          *   the result of applying the fold operator between all elements and z
          */
        inline def fold[B >: A](z: B)(inline op: (B, B) => B): B =
            foldLeft(z)(op)

        /** Applies a binary operator to a start value and all elements of this Span, going left to right.
          *
          * @param z
          *   the start value
          * @param op
          *   the binary operator
          * @return
          *   the result of inserting op between consecutive elements of this Span, going left to right with the start value z on the left
          */
        inline def foldLeft[B](z: B)(inline op: (B, A) => B): B =
            val size = self.length
            @tailrec def loop(idx: Int, acc: B): B =
                if idx >= size then acc
                else loop(idx + 1, op(acc, self(idx)))
            loop(0, z)
        end foldLeft

        /** Applies a binary operator to all elements of this Span and a start value, going right to left.
          *
          * @param z
          *   the start value
          * @param op
          *   the binary operator
          * @return
          *   the result of inserting op between consecutive elements of this Span, going right to left with the start value z on the right
          */
        inline def foldRight[B](z: B)(inline op: (A, B) => B): B =
            val size = self.length
            @tailrec def loop(idx: Int, acc: B): B =
                if idx < 0 then acc
                else loop(idx - 1, op(self(idx), acc))
            loop(size - 1, z)
        end foldRight

        /** Copy elements of this Span to another array.
          *
          * @param xs
          *   the array to copy elements to
          * @param start
          *   the starting index in the target array
          * @param len
          *   the maximum number of elements to copy
          * @return
          *   the number of elements actually copied
          */
        inline def copyToArray[B >: A](xs: Array[B], start: Int = 0, len: Int = Int.MaxValue): Int =
            val size   = self.length
            val toCopy = if len < size then len else size
            val copied = if toCopy <= xs.length - start then toCopy else xs.length - start
            if copied > 0 then
                System.arraycopy(self, 0, xs, start, copied)
            copied
        end copyToArray

        /** Converts this Span to a String with the given separator between elements.
          *
          * @param separator
          *   the string to insert between each element
          * @return
          *   a string representation of this Span
          */
        def mkString(separator: String): String =
            val r = new java.lang.StringBuilder
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    r.append(self(idx).toString)
                    if idx < size - 1 then
                        discard(r.append(separator))
                    loop(idx + 1)
            loop(0)
            r.toString()
        end mkString

        /** Converts this Span to a String with the given start, separator, and end strings.
          *
          * @param start
          *   the starting string
          * @param sep
          *   the separator string
          * @param end
          *   the ending string
          * @return
          *   a string representation of this Span
          */
        def mkString(start: String, sep: String, end: String): String =
            start + mkString(sep) + end

        /** Converts this Span to a String.
          *
          * @return
          *   a string representation of this Span with no separator
          */
        def mkString: String = mkString("")

        /** Returns a copy of the underlying array.
          *
          * @return
          *   a new array containing all elements of this Span
          */
        def toArray(using ClassTag[A]): Array[A] =
            val size = self.length
            val copy = new Array[A](size)
            System.arraycopy(self, 0, copy, 0, size)
            copy
        end toArray

        /** Returns the underlying array without copying.
          *
          * Note: This method does not create a defensive copy of the array, so changes to the returned array will be reflected in the Span.
          * Use with caution.
          *
          * @return
          *   the underlying array of this Span
          */
        def toArrayUnsafe: Array[A] = self.asInstanceOf[Array[A]]

        /** Returns a new Span which contains all elements of this Span followed by all elements of a given Span.
          *
          * @param suffix
          *   the Span to concatenate
          * @return
          *   a new Span which contains all elements of this Span followed by all elements of suffix
          */
        inline def concat(suffix: Span[A])(using ClassTag[A]): Span[A] =
            Span.concat(self, suffix)

        /** Alias for concat.
          *
          * @param suffix
          *   the Span to append
          * @return
          *   a new Span with suffix appended
          */
        inline infix def ++(suffix: Span[A])(using ClassTag[A]): Span[A] =
            concat(suffix)

        /** Returns a new Span with an element appended.
          *
          * @param x
          *   the element to append
          * @return
          *   a new Span with x appended
          */
        inline def append(x: A)(using ClassTag[A]): Span[A] =
            val size = self.length
            val r    = new Array[A](size + 1)
            System.arraycopy(self, 0, r, 0, size)
            r(size) = x
            r
        end append

        /** Alias for append.
          *
          * @param x
          *   the element to append
          * @return
          *   a new Span with x appended
          */
        inline def :+(x: A)(using ClassTag[A]): Span[A] = append(x)

        /** Returns a new Span with an element prepended.
          *
          * @param x
          *   the element to prepend
          * @return
          *   a new Span with x prepended
          */
        inline def prepend(x: A)(using ClassTag[A]): Span[A] =
            val size = self.length
            val r    = new Array[A](size + 1)
            r(0) = x
            System.arraycopy(self, 0, r, 1, size)
            r
        end prepend

        /** Builds a new Span by applying a partial function to all elements of this Span on which the function is defined.
          *
          * @param pf
          *   the partial function to apply
          * @return
          *   a new Span containing the elements transformed by pf
          */
        inline def collect[B: ClassTag](pf: PartialFunction[A, B]): Span[B] =
            val size = self.length
            if size == 0 then Span.empty[B]
            else
                val temp = new Array[B](size)
                @tailrec def loop(idx: Int, writeIdx: Int): Int =
                    if idx < size then
                        val elem = self(idx)
                        if pf.isDefinedAt(elem) then
                            temp(writeIdx) = pf(elem)
                            loop(idx + 1, writeIdx + 1)
                        else
                            loop(idx + 1, writeIdx)
                        end if
                    else writeIdx
                val actualSize = loop(0, 0)
                if actualSize == size then temp
                else if actualSize == 0 then Span.empty[B]
                else
                    val result = new Array[B](actualSize)
                    System.arraycopy(temp, 0, result, 0, actualSize)
                    result
                end if
            end if
        end collect

        /** Finds the first element of the Span for which the given partial function is defined, and applies the partial function to it.
          *
          * @param pf
          *   the partial function
          * @return
          *   an option value containing pf applied to the first value for which it is defined, or None if none found
          */
        inline def collectFirst[B](pf: PartialFunction[A, B]): Maybe[B] =
            val size = self.length
            @tailrec def loop(idx: Int): Maybe[B] =
                if idx >= size then Absent
                else
                    val elem = self(idx)
                    if pf.isDefinedAt(elem) then Present(pf(elem))
                    else loop(idx + 1)
            loop(0)
        end collectFirst

        /** Returns a new Span without duplicates.
          *
          * @return
          *   a new Span which contains the first occurrence of every element of this Span
          */
        def distinct(using ClassTag[A]): Span[A] =
            val size = self.length
            if size == 0 then Span.empty[A]
            else
                val seen = scala.collection.mutable.Set.empty[A]
                val temp = new Array[A](size)
                @tailrec def loop(idx: Int, writeIdx: Int): Int =
                    if idx < size then
                        val elem = self(idx)
                        if !seen.contains(elem) then
                            discard(seen.add(elem))
                            temp(writeIdx) = elem
                            loop(idx + 1, writeIdx + 1)
                        else
                            loop(idx + 1, writeIdx)
                        end if
                    else writeIdx
                val actualSize = loop(0, 0)
                if actualSize == size then temp
                else if actualSize == 0 then Span.empty[A]
                else
                    val result = new Array[A](actualSize)
                    System.arraycopy(temp, 0, result, 0, actualSize)
                    result
                end if
            end if
        end distinct

        /** Returns a new Span without duplicates, where uniqueness is determined by the transform function f.
          *
          * @param f
          *   the function to transform elements before comparing for equality
          * @return
          *   a new Span which contains the first occurrence of every element of this Span after transformation by f
          */
        inline def distinctBy[B](inline f: A => B)(using ClassTag[A]): Span[A] =
            val size = self.length
            if size == 0 then Span.empty[A]
            else
                val seen = scala.collection.mutable.Set.empty[B]
                val temp = new Array[A](size)
                @tailrec def loop(idx: Int, writeIdx: Int): Int =
                    if idx < size then
                        val elem = self(idx)
                        val key  = f(elem)
                        if !seen.contains(key) then
                            discard(seen.add(key))
                            temp(writeIdx) = elem
                            loop(idx + 1, writeIdx + 1)
                        else
                            loop(idx + 1, writeIdx)
                        end if
                    else writeIdx
                val actualSize = loop(0, 0)
                if actualSize == size then temp
                else if actualSize == 0 then Span.empty[A]
                else
                    val result = new Array[A](actualSize)
                    System.arraycopy(temp, 0, result, 0, actualSize)
                    result
                end if
            end if
        end distinctBy

        /** Groups elements in fixed size blocks by passing a "sliding window" over them.
          *
          * @param size
          *   the number of elements per group
          * @param step
          *   the distance between the first elements of successive groups
          * @return
          *   an iterator producing Spans of size size, except the last element which may be smaller
          */
        def sliding(size: Int, step: Int = 1)(using ct: ClassTag[A]): Iterator[Span[A]] =
            if size <= 0 then throw new IllegalArgumentException("size must be positive")
            if step <= 0 then throw new IllegalArgumentException("step must be positive")
            val offset = size
            new Iterator[Span[A]]:
                private var pos      = 0
                def hasNext: Boolean = pos < self.length
                def next(): Span[A] =
                    if !hasNext then throw new NoSuchElementException
                    val result = self.slice(pos, pos + offset)(using ct)
                    pos += step
                    result
                end next
            end new
        end sliding

        /** Computes a prefix scan of the elements of the Span.
          *
          * @param z
          *   the neutral element for the operator op
          * @param op
          *   the associative operator for the scan
          * @return
          *   a new Span containing the prefix scan of the elements in this Span
          */
        inline def scan(z: A)(inline op: (A, A) => A)(using ClassTag[A]): Span[A] =
            scanLeft(z)(op)

        /** Produces a Span containing cumulative results of applying the binary operator going left to right.
          *
          * @param z
          *   the start value
          * @param op
          *   the binary operator the binary operator
          * @return
          *   Span with intermediate results of inserting op between consecutive elements of this Span, going left to right with the start
          *   value z on the left
          */
        inline def scanLeft[B: ClassTag](z: B)(inline op: (B, A) => B): Span[B] =
            val size = self.length
            val r    = new Array[B](size + 1)
            r(0) = z
            @tailrec def loop(idx: Int, acc: B): Unit =
                if idx < size then
                    val newAcc = op(acc, self(idx))
                    r(idx + 1) = newAcc
                    loop(idx + 1, newAcc)
            loop(0, z)
            r
        end scanLeft

        /** Produces a Span containing cumulative results of applying the binary operator going right to left.
          *
          * @param z
          *   the start value the start value
          * @param op
          *   the binary operator
          * @return
          *   Span with intermediate results of inserting op between consecutive elements of this Span, going right to left with the start
          *   value z on the right
          */
        inline def scanRight[B: ClassTag](z: B)(inline op: (A, B) => B): Span[B] =
            val size = self.length
            val r    = new Array[B](size + 1)
            r(size) = z
            @tailrec def loop(idx: Int, acc: B): Unit =
                if idx >= 0 then
                    val newAcc = op(self(idx), acc)
                    r(idx) = newAcc
                    loop(idx - 1, newAcc)
            loop(size - 1, z)
            r
        end scanRight

        /** Flattens a two-dimensional Span by concatenating all its rows into a single Span.
          *
          * @param asIterable
          *   evidence that A can be converted to a Span
          * @param ct
          *   class tag for the element type
          * @return
          *   a new Span containing all elements from the nested structures
          */
        inline def flatten[B](using asSpan: A => Span[B], ct: ClassTag[B]): Span[B] =
            val size = self.length
            if size == 0 then Span.empty[B]
            else
                val spans     = new Array[Span[B]](size)
                var totalSize = 0
                @tailrec def collectLoop(idx: Int): Unit =
                    if idx < size then
                        val span = asSpan(self(idx))
                        spans(idx) = span
                        totalSize += span.size
                        collectLoop(idx + 1)
                collectLoop(0)

                if totalSize == 0 then Span.empty[B]
                else
                    val result = new Array[B](totalSize)
                    @tailrec def populateLoop(idx: Int, writeIdx: Int): Unit =
                        if idx < size then
                            val span     = spans(idx)
                            val spanSize = span.size
                            @tailrec def copySpan(elemIdx: Int, currentWriteIdx: Int): Int =
                                if elemIdx < spanSize then
                                    result(currentWriteIdx) = span(elemIdx)
                                    copySpan(elemIdx + 1, currentWriteIdx + 1)
                                else currentWriteIdx
                            val nextWriteIdx = copySpan(0, writeIdx)
                            populateLoop(idx + 1, nextWriteIdx)
                        end if
                    end populateLoop
                    populateLoop(0, 0)
                    result
                end if
            end if
        end flatten

        /** Returns a Span with a length at least the given length by padding the end with the given element.
          *
          * @param len
          *   the target length
          * @param elem
          *   the padding element
          * @return
          *   a new Span padded to the specified length
          */
        inline def padTo(len: Int, elem: A)(using ClassTag[A]): Span[A] =
            if len <= size then self
            else concat(Span.fill(len - size)(elem))

    end extension

    extension [B](x: B)
        def +:[A >: B: ClassTag](span: Span[A]): Span[A] = span.prepend(x)

    /** Returns a Span that contains the results of some element computation a number of times.
      *
      * @param n
      *   the number of elements in the Span
      * @param elem
      *   the element computation
      * @return
      *   a Span with n elements, each computed by the elem expression (which is computed n times)
      */
    def fill[A: ClassTag](n: Int)(elem: => A): Span[A] =
        if n <= 0 then empty[A]
        else
            val r = new Array[A](n)
            @tailrec def loop(idx: Int): Unit =
                if idx < n then
                    r(idx) = elem
                    loop(idx + 1)
            loop(0)
            r

    /** Returns a Span containing values of a given function over a range of integer values starting from 0.
      *
      * @param n
      *   the number of elements in the Span
      * @param f
      *   the function computing element values
      * @return
      *   a Span with elements f(0), f(1), ..., f(n-1)
      */
    inline def tabulate[A: ClassTag](n: Int)(inline f: Int => A): Span[A] =
        if n <= 0 then empty[A]
        else
            val r = new Array[A](n)
            @tailrec def loop(idx: Int): Unit =
                if idx < n then
                    r(idx) = f(idx)
                    loop(idx + 1)
            loop(0)
            r

    /** Returns a Span containing a sequence of increasing integers in a range.
      *
      * @param start
      *   the start value of the Span
      * @param end
      *   the end value of the Span, exclusive
      * @return
      *   the Span with values in range start, start + 1, ..., end - 1
      */
    def range(start: Int, end: Int): Span[Int] =
        range(start, end, 1)

    /** Returns a Span containing equally spaced values in some integer interval.
      *
      * @param start
      *   the start value of the Span
      * @param end
      *   the end value of the Span, exclusive
      * @param step
      *   the increment value of the Span (may not be zero)
      * @return
      *   the Span with values in start, start + step, ... up to, but excluding end
      */
    def range(start: Int, end: Int, step: Int): Span[Int] =
        if step == 0 then throw new IllegalArgumentException("step cannot be 0")
        else if step > 0 && start >= end then empty[Int]
        else if step < 0 && start <= end then empty[Int]
        else
            val len = math.max(0, ((end - start + step - step.sign) / step))
            val r   = new Array[Int](len)
            @tailrec def loop(idx: Int, value: Int): Unit =
                if idx < len then
                    r(idx) = value
                    loop(idx + 1, value + step)
            loop(0, start)
            r

    /** Returns a Span containing repeated applications of a function to a start value.
      *
      * @param start
      *   the start value of the Span
      * @param len
      *   the number of elements returned by the Span
      * @param f
      *   the function that is repeatedly applied
      * @return
      *   the Span returning len values in the sequence start, f(start), f(f(start)), ...
      */
    inline def iterate[A: ClassTag](start: A, len: Int)(inline f: A => A): Span[A] =
        if len <= 0 then empty[A]
        else
            val r = new Array[A](len)
            @tailrec def loop(idx: Int, value: A): Unit =
                if idx < len then
                    r(idx) = value
                    loop(idx + 1, f(value))
            loop(0, start)
            r

    /** Concatenates all Spans into a single Span.
      *
      * @param spans
      *   the given Spans
      * @return
      *   the Span created from concatenating spans
      */
    def concat[A: ClassTag](spans: Span[A]*): Span[A] =
        if spans.isEmpty then empty[A]
        else
            val totalSize = spans.map(_.length).sum
            if totalSize == 0 then empty[A]
            else
                val r = new Array[A](totalSize)
                @tailrec def loop(spanIdx: Int, targetIdx: Int): Unit =
                    if spanIdx < spans.length then
                        val span     = spans(spanIdx)
                        val spanSize = span.length
                        if spanSize > 0 then
                            System.arraycopy(span.toArrayUnsafe, 0, r, targetIdx, spanSize)
                        loop(spanIdx + 1, targetIdx + spanSize)
                loop(0, 0)
                r
            end if

    /** Checks if at least one pair of corresponding elements from two Spans satisfies the given predicate.
      *
      * Both Spans must have the same size. This method will throw an exception if the Spans have different lengths.
      *
      * @param a
      *   the first Span
      * @param b
      *   the second Span
      * @param f
      *   the predicate to test each pair of elements with
      * @return
      *   true if at least one pair satisfies the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the Spans have different lengths
      */
    inline def existsZip[A, B](a: Span[A], b: Span[B])(inline f: (A, B) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx != size && (f(a(idx), b(idx)) || loop(idx + 1))
        loop(0)
    end existsZip

    /** Checks if at least one triplet of corresponding elements from three Spans satisfies the given predicate.
      *
      * All Spans must have the same size. This method will throw an exception if the Spans have different lengths.
      *
      * @param a
      *   the first Span
      * @param b
      *   the second Span
      * @param c
      *   the third Span
      * @param f
      *   the predicate to test each triplet of elements with
      * @return
      *   true if at least one triplet satisfies the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the Spans have different lengths
      */
    inline def existsZip[A, B, C](a: Span[A], b: Span[B], c: Span[C])(inline f: (A, B, C) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx != size && (f(a(idx), b(idx), c(idx)) || loop(idx + 1))
        loop(0)
    end existsZip

    /** Checks if all pairs of corresponding elements from two Spans satisfy the given predicate.
      *
      * Both Spans must have the same size. This method will throw an exception if the Spans have different lengths.
      *
      * @param a
      *   the first Span
      * @param b
      *   the second Span
      * @param f
      *   the predicate to test each pair of elements with
      * @return
      *   true if all pairs satisfy the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the Spans have different lengths
      */
    inline def forallZip[A, B](a: Span[A], b: Span[B])(inline f: (A, B) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx == size || (f(a(idx), b(idx)) && loop(idx + 1))
        loop(0)
    end forallZip

    /** Checks if all triplets of corresponding elements from three Spans satisfy the given predicate.
      *
      * All Spans must have the same size. This method will throw an exception if the Spans have different lengths.
      *
      * @param a
      *   the first Span
      * @param b
      *   the second Span
      * @param c
      *   the third Span
      * @param f
      *   the predicate to test each triplet of elements with
      * @return
      *   true if all triplets satisfy the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the Spans have different lengths
      */
    inline def forallZip[A, B, C](a: Span[A], b: Span[B], c: Span[C])(inline f: (A, B, C) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx == size || (f(a(idx), b(idx), c(idx)) && loop(idx + 1))
        loop(0)
    end forallZip

    private object internal:

        val cachedEmpty =
            Map[ClassTag[?], Array[?]](
                summon[ClassTag[Boolean]] -> Array.emptyBooleanArray,
                summon[ClassTag[Byte]]    -> Array.emptyByteArray,
                summon[ClassTag[Char]]    -> Array.emptyCharArray,
                summon[ClassTag[Double]]  -> Array.emptyDoubleArray,
                summon[ClassTag[Float]]   -> Array.emptyFloatArray,
                summon[ClassTag[Int]]     -> Array.emptyIntArray,
                summon[ClassTag[Long]]    -> Array.emptyLongArray,
                summon[ClassTag[Short]]   -> Array.emptyShortArray
            )

    end internal

end Span
