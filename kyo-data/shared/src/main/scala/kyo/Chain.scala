package kyo

import java.util.Arrays
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

/** An efficient, immutable array-backed sequence of elements.
  *
  * Chain is similar to Scala's built-in IArray (immutable array), but provides optimized methods for common operations and additional
  * utility functions. It offers better performance characteristics than standard Scala collections for many use cases while maintaining
  * immutability semantics. In practice, using Chain is akin to using Array directly but with a richer and safer immutable API.
  *
  * Chain is essentially a thin wrapper around arrays, making it ideal for scenarios where you need predictable, array-like performance with
  * O(1) indexing and iteration. It excels in memory-sensitive contexts due to its minimal overhead.
  *
  * A key advantage of Chain over Chunk is that Chain doesn't box primitive types, while Chunk does. This makes Chain more memory-efficient
  * when working with primitive values like Int, Long, Double, etc.
  *
  * Important: Unlike Chunk, Chain is NOT part of Scala's collection hierarchy. It doesn't extend Seq or any other collection trait, which
  * means it won't work with methods expecting standard Scala collections. Consider using Chunk instead when you need compatibility with
  * Scala's collection library or when you require efficient structural operations like slicing without copying data.
  *
  * @tparam A
  *   the type of elements in this Chain
  */
opaque type Chain[A] = Array[A]

object Chain:

    import internal.*

    /** Creates a new Chain containing the given elements.
      *
      * @param values
      *   the elements to include in the Chain
      * @return
      *   a new Chain containing the specified elements
      */
    def apply[A: ClassTag](values: A*): Chain[A] =
        values match
            case values: ArraySeq[A] => values.unsafeArray.asInstanceOf[Array[A]]
            case values              => values.toArray

    /** Returns an empty Chain.
      *
      * @return
      *   an empty Chain of type A
      */
    def empty[A: ClassTag as ct]: Chain[A] =
        if cachedEmpty.contains(ct) then
            cachedEmpty(ct).asInstanceOf[Array[A]]
        else
            Array.empty

    /** Creates a Chain from an Array without copying the array.
      *
      * Note: This method does not create a defensive copy of the array, so changes to the original array will be reflected in the Chain.
      * Use with caution.
      *
      * @param array
      *   the Array to create the Chain from
      * @return
      *   a new Chain sharing the same underlying array
      */
    def fromUnsafe[A](array: Array[A]): Chain[A] = array

    /** Creates a Chain from an IArray.
      *
      * Since IArray is already immutable, this operation is safe.
      *
      * @param array
      *   the IArray to create the Chain from
      * @return
      *   a new Chain containing the elements from the IArray
      */
    def from[A](array: IArray[A])(using Discriminator): Chain[A] =
        array.unsafeArray.asInstanceOf[Array[A]]

    /** Creates a Chain from an IterableOnce.
      *
      * @param seq
      *   the IterableOnce to create the Chain from
      * @return
      *   a new Chain containing the elements from the IterableOnce
      */
    def fromUnsafe[A: ClassTag](seq: IterableOnce[A]): Chain[A] =
        seq.iterator.toArray

    /** Creates a Chain from an Array by copying the array.
      *
      * @param array
      *   the Array to create the Chain from
      * @return
      *   a new Chain containing a copy of the elements from the Array
      */
    def from[A: ClassTag](array: Array[A]): Chain[A] =
        val copy = new Array[A](array.length)
        System.arraycopy(array, 0, copy, 0, array.length)
        copy
    end from

    /** Creates a Chain from an IterableOnce. This method is identical to fromUnsafe for IterableOnce since it always creates a new array.
      *
      * @param seq
      *   the IterableOnce to create the Chain from
      * @return
      *   a new Chain containing the elements from the IterableOnce
      */
    def from[A: ClassTag](seq: IterableOnce[A]): Chain[A] =
        seq.iterator.toArray

    extension [A](self: Chain[A])

        /** Returns the number of elements in this Chain.
          *
          * @return
          *   the number of elements in this Chain
          */
        def size: Int = self.length

        /** Checks if this Chain is empty.
          *
          * @return
          *   true if the Chain contains no elements, false otherwise
          */
        def isEmpty: Boolean = size == 0

        /** Returns the element at the specified index.
          *
          * @param idx
          *   the index of the element to return
          * @return
          *   the element at the specified index
          */
        inline def apply(idx: Int): A = self(idx)

        /** Checks if this Chain is equal to another Chain.
          *
          * @param other
          *   the Chain to compare with
          * @return
          *   true if the Chains contain the same elements in the same order, false otherwise
          */
        inline def is(other: Chain[A])(using CanEqual[A, A]): Boolean =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx == size || (self(idx) == other(idx) && loop(idx + 1))
            loop(0)
        end is

        /** Checks if all elements in this Chain satisfy the given predicate.
          *
          * @param f
          *   the predicate to test each element with
          * @return
          *   true if all elements satisfy the predicate, false otherwise
          */
        inline def forall(inline f: A => Boolean) =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx == size || (f(self(idx)) && loop(idx + 1))
            loop(0)
        end forall

        /** Checks if at least one element in this Chain satisfies the given predicate.
          *
          * @param f
          *   the predicate to test each element with
          * @return
          *   true if at least one element satisfies the predicate, false otherwise
          */
        inline def exists(inline f: A => Boolean) =
            val size = self.length
            @tailrec def loop(idx: Int): Boolean =
                idx != size && (f(self(idx)) || loop(idx + 1))
            loop(0)
        end exists

        /** Applies a function to each element of this Chain and returns a new Chain with the results.
          *
          * @param f
          *   the function to apply to each element
          * @return
          *   a new Chain containing the results of applying the function to each element
          */
        inline def map[B: ClassTag](inline f: A => B): Chain[B] =
            val size = self.length
            val r    = new Array[B](self.length)
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    r(idx) = f(self(idx))
                    loop(idx + 1)
            loop(0)
            r
        end map

        /** Converts this Chain to a String with the given separator between elements.
          *
          * @param separator
          *   the string to insert between each element
          * @return
          *   a string representation of this Chain
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

        /** Returns a copy of the underlying array.
          *
          * @return
          *   a new array containing all elements of this Chain
          */
        def toArray(using ClassTag[A]): Array[A] =
            val copy = new Array[A](self.length)
            System.arraycopy(self, 0, copy, 0, self.length)
            copy
        end toArray

        /** Returns the underlying array without copying.
          *
          * Note: This method does not create a defensive copy of the array, so changes to the returned array will be reflected in the
          * Chain. Use with caution.
          *
          * @return
          *   the underlying array of this Chain
          */
        def toArrayUnsafe: Array[A] = self

    end extension

    /** Checks if at least one pair of corresponding elements from two Chains satisfies the given predicate.
      *
      * Both chains must have the same size. This method will throw an exception if the chains have different lengths.
      *
      * @param a
      *   the first Chain
      * @param b
      *   the second Chain
      * @param f
      *   the predicate to test each pair of elements with
      * @return
      *   true if at least one pair satisfies the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the chains have different lengths
      */
    inline def existsZip[A, B](a: Chain[A], b: Chain[B])(inline f: (A, B) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx != size && (f(a(idx), b(idx)) || loop(idx + 1))
        loop(0)
    end existsZip

    /** Checks if at least one triplet of corresponding elements from three Chains satisfies the given predicate.
      *
      * All chains must have the same size. This method will throw an exception if the chains have different lengths.
      *
      * @param a
      *   the first Chain
      * @param b
      *   the second Chain
      * @param c
      *   the third Chain
      * @param f
      *   the predicate to test each triplet of elements with
      * @return
      *   true if at least one triplet satisfies the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the chains have different lengths
      */
    inline def existsZip[A, B, C](a: Chain[A], b: Chain[B], c: Chain[C])(inline f: (A, B, C) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx != size && (f(a(idx), b(idx), c(idx)) || loop(idx + 1))
        loop(0)
    end existsZip

    /** Checks if all pairs of corresponding elements from two Chains satisfy the given predicate.
      *
      * Both chains must have the same size. This method will throw an exception if the chains have different lengths.
      *
      * @param a
      *   the first Chain
      * @param b
      *   the second Chain
      * @param f
      *   the predicate to test each pair of elements with
      * @return
      *   true if all pairs satisfy the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the chains have different lengths
      */
    inline def forallZip[A, B](a: Chain[A], b: Chain[B])(inline f: (A, B) => Boolean) =
        val size = a.length
        require(size == b.length)
        @tailrec def loop(idx: Int): Boolean =
            idx == size || (f(a(idx), b(idx)) && loop(idx + 1))
        loop(0)
    end forallZip

    /** Checks if all triplets of corresponding elements from three Chains satisfy the given predicate.
      *
      * All chains must have the same size. This method will throw an exception if the chains have different lengths.
      *
      * @param a
      *   the first Chain
      * @param b
      *   the second Chain
      * @param c
      *   the third Chain
      * @param f
      *   the predicate to test each triplet of elements with
      * @return
      *   true if all triplets satisfy the predicate, false otherwise
      * @throws IllegalArgumentException
      *   if the chains have different lengths
      */
    inline def forallZip[A, B, C](a: Chain[A], b: Chain[B], c: Chain[C])(inline f: (A, B, C) => Boolean) =
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

end Chain
