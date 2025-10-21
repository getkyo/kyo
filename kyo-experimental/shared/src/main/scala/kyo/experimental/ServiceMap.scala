package kyo.experimental

import kyo.Const
import kyo.Tag
import scala.collection.immutable.TreeSeqMap

opaque type ServiceMap[+R[-_], -S] = TreeSeqMap[Tag[Any], Any]

object ServiceMap:

    extension [R[-_], S](self: ServiceMap[R, S])

        private inline def fatal[T[-_]](using t: Tag[T[Any]]): Nothing =
            throw new RuntimeException(s"fatal: kyo.experimental.HKTMap of contents [${self.show}] missing value of type: [${t.show}].")

        /** Retrieves a value of higher-kinded type R2 from the HKTMap.
          *
          * @tparam R2
          *   The higher-kinded type to retrieve (must be a supertype of R)
          * @param t
          *   An implicit Tag for type R2[Any]
          * @param ev
          *   Evidence that R[Any] is a subtype of R2[Any]
          * @return
          *   The value of type R2[S]
          * @throws RuntimeException
          *   if the value is not found
          */
        def get[R2[-_]](using t: Tag[R2[Any]], ev: R[Any] <:< R2[Any]): R2[S] =
            def search: Any =
                val it = self.iterator
                while it.hasNext do
                    val (tag, item) = it.next()
                    if tag <:< t then
                        return item
                end while
                fatal
            end search
            if isEmpty && t =:= Tag[Any] then ().asInstanceOf[R2[S]]
            else self.getOrElse(t.erased, search).asInstanceOf[R2[S]]
        end get

        /** Adds a new HKT-value pair to the HKTMap.
          *
          * @param r2
          *   The HKT instance to add
          * @tparam R2
          *   The higher-kinded type of the value to add
          * @param t
          *   An implicit Tag for type R2[Any]
          * @return
          *   A new HKTMap with the added HKT-value pair
          */
        inline def add[R2[-_], S2](r2: R2[S2])(using inline t: Tag[R2[Any]]): ServiceMap[R && R2, S & S2] =
            self.updated(t.erased, r2)

        /** Combines this HKTMap with another HKTMap.
          *
          * @param that
          *   The HKTMap to combine with
          * @tparam R2
          *   The higher-kinded type of values in the other HKTMap
          * @tparam S2
          *   The type parameter of the other HKTMap
          * @return
          *   A new HKTMap containing all HKT-value pairs from both HKTMaps
          */
        inline def union[R2[-_], S2](that: ServiceMap[R2, S2]): ServiceMap[R && R2, S & S2] =
            self ++ that

        /** Filters the HKTMap to only include HKT-value pairs where the HKT is a subtype of the given type.
          *
          * @tparam R2
          *   The higher-kinded type to filter by (must be a supertype of R)
          * @param t
          *   An implicit Tag for type R2[Any]
          * @return
          *   A new HKTMap containing only the filtered HKT-value pairs
          */
        def prune[R2[-_] >: R](using t: Tag[R2[Any]]): ServiceMap[R2, S] =
            if t =:= Tag[Any] then self
            else self.filter { case (tag, _) => tag <:< t }

        /** Returns the number of HKT-value pairs in the HKTMap.
          *
          * @return
          *   The size of the HKTMap
          */
        inline def size: Int = self.size

        /** Checks if the HKTMap is empty.
          *
          * @return
          *   true if the HKTMap is empty, false otherwise
          */
        inline def isEmpty: Boolean = self.isEmpty

        /** Returns a string representation of the HKTMap.
          *
          * @return
          *   A string describing the contents of the HKTMap
          */
        def show: String = self.map { case (tag, value) => s"${tag.show} -> $value" }.toList.sorted.mkString("HKTMap(", ", ", ")")

        private[kyo] inline def <:<[T[-_]](tag: Tag[T[Any]]): Boolean =
            self.keySet.exists(_ <:< tag)
    end extension

    /** An empty HKTMap. */
    val empty: ServiceMap[Const[Any], Any] = TreeSeqMap.empty(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with a single HKT-value pair.
      *
      * @param r
      *   The HKT instance to add
      * @tparam R
      *   The higher-kinded type of the value
      * @tparam S
      *   The type parameter of the HKT
      * @param tr
      *   An implicit Tag for type R[Any]
      * @return
      *   A new HKTMap with the single HKT-value pair
      */
    def apply[R[-_], S](r: R[S])(using tr: Tag[R[Any]]): ServiceMap[R, S] =
        TreeSeqMap(tr.erased -> r).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with two HKT-value pairs.
      *
      * @param r1
      *   The first HKT instance to add
      * @param r2
      *   The second HKT instance to add
      * @tparam R1
      *   The higher-kinded type of the first value
      * @tparam R2
      *   The higher-kinded type of the second value
      * @tparam S
      *   The type parameter of both HKTs
      * @param tr1
      *   An implicit Tag for type R1[Any]
      * @param tr2
      *   An implicit Tag for type R2[Any]
      * @return
      *   A new HKTMap with the two HKT-value pairs
      */
    def apply[R1[-_], R2[-_], S](r1: R1[S], r2: R2[S])(using tr1: Tag[R1[Any]], tr2: Tag[R2[Any]]): ServiceMap[R1 && R2, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with three HKT-value pairs.
      */
    def apply[R1[-_], R2[-_], R3[-_], S](r1: R1[S], r2: R2[S], r3: R3[S])(using
        tr1: Tag[R1[Any]],
        tr2: Tag[R2[Any]],
        tr3: Tag[R3[Any]]
    ): ServiceMap[R1 && R2 && R3, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2, tr3.erased -> r3).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with four HKT-value pairs.
      */
    def apply[R1[-_], R2[-_], R3[-_], R4[-_], S](r1: R1[S], r2: R2[S], r3: R3[S], r4: R4[S])(using
        tr1: Tag[R1[Any]],
        tr2: Tag[R2[Any]],
        tr3: Tag[R3[Any]],
        tr4: Tag[R4[Any]]
    ): ServiceMap[R1 && R2 && R3 && R4, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2, tr3.erased -> r3, tr4.erased -> r4).orderingBy(TreeSeqMap.OrderBy.Modification)

end ServiceMap
