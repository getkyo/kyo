package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.TreeSeqMap

/** `TypeMap` provides a type-safe heterogeneous map implementation, allowing you to store and retrieve values of different types using
  * their types as keys.
  *
  * @tparam A
  *   The type of values in the map
  */
opaque type TypeMap[+A] = TreeSeqMap[Tag[Any], Any]

object TypeMap:
    extension [A](self: TypeMap[A])

        private inline def fatal[T](using t: Tag[T]): Nothing =
            throw new RuntimeException(s"fatal: kyo.TypeMap of contents [${self.show}] missing value of type: [${t.showTpe}].")

        /** Retrieves a value of type B from the TypeMap.
          *
          * @tparam B
          *   The type of the value to retrieve (must be a supertype of A)
          * @param t
          *   An implicit Tag for type B
          * @return
          *   The value of type B
          * @throws RuntimeException
          *   if the value is not found
          */
        def get[B >: A](using t: Tag[B]): B =
            def search: Any =
                val it = self.iterator
                while it.hasNext do
                    val (tag, item) = it.next()
                    if tag <:< t then
                        return item
                end while
                fatal
            end search
            if isEmpty && t =:= Tag[Any] then ().asInstanceOf[B]
            else self.getOrElse(t.erased, search).asInstanceOf[B]
        end get

        /** Adds a new key-value pair to the TypeMap.
          *
          * @param b
          *   The value to add
          * @tparam B
          *   The type of the value to add
          * @param t
          *   An implicit Tag for type B
          * @return
          *   A new TypeMap with the added key-value pair
          */
        inline def add[B](b: B)(using inline t: Tag[B]): TypeMap[A & B] =
            self.updated(t.erased, b)

        /** Combines this TypeMap with another TypeMap.
          *
          * @param that
          *   The TypeMap to combine with
          * @tparam B
          *   The type of values in the other TypeMap
          * @return
          *   A new TypeMap containing all key-value pairs from both TypeMaps
          */
        inline def union[B](that: TypeMap[B]): TypeMap[A & B] =
            self ++ that

        /** Filters the TypeMap to only include key-value pairs where the key is a subtype of the given type.
          *
          * @tparam B
          *   The type to filter by (must be a supertype of A)
          * @param t
          *   An implicit Tag for type B
          * @return
          *   A new TypeMap containing only the filtered key-value pairs
          */
        def prune[B >: A](using t: Tag[B]): TypeMap[B] =
            if t =:= Tag[Any] then self
            else self.filter { case (tag, _) => tag <:< t }

        /** Returns the number of key-value pairs in the TypeMap.
          *
          * @return
          *   The size of the TypeMap
          */
        inline def size: Int = self.size

        /** Checks if the TypeMap is empty.
          *
          * @return
          *   true if the TypeMap is empty, false otherwise
          */
        inline def isEmpty: Boolean = self.isEmpty

        /** Returns a string representation of the TypeMap.
          *
          * @return
          *   A string describing the contents of the TypeMap
          */
        def show: String = self.map { case (tag, value) => s"${tag.showTpe} -> $value" }.toList.sorted.mkString("TypeMap(", ", ", ")")

        private[kyo] inline def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)

        private[kyo] inline def <:<[T](tag: Tag[T]): Boolean =
            self.keySet.exists(_ <:< tag)
    end extension

    /** An empty TypeMap. */
    val empty: TypeMap[Any] = TreeSeqMap.empty(TreeSeqMap.OrderBy.Modification)

    /** Creates a TypeMap with a single key-value pair.
      *
      * @param a
      *   The value to add
      * @tparam A
      *   The type of the value
      * @param ta
      *   An implicit Tag for type A
      * @return
      *   A new TypeMap with the single key-value pair
      */
    def apply[A](a: A)(using ta: Tag[A]): TypeMap[A] =
        TreeSeqMap(ta.erased -> a).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates a TypeMap with two key-value pairs.
      *
      * @param a
      *   The first value to add
      * @param b
      *   The second value to add
      * @tparam A
      *   The type of the first value
      * @tparam B
      *   The type of the second value
      * @param ta
      *   An implicit Tag for type A
      * @param tb
      *   An implicit Tag for type B
      * @return
      *   A new TypeMap with the two key-value pairs
      */
    def apply[A, B](a: A, b: B)(using ta: Tag[A], tb: Tag[B]): TypeMap[A & B] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates a TypeMap with three key-value pairs.
      */
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C)(using ta: Tag[A], tb: Tag[B], tc: Tag[C]): TypeMap[A & B & C] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b, tc.erased -> c).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates a TypeMap with four key-value pairs.
      */
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        td: Tag[D]
    ): TypeMap[A & B & C & D] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b, tc.erased -> c, td.erased -> d).orderingBy(TreeSeqMap.OrderBy.Modification)
end TypeMap
