package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.TreeSeqMap

opaque type TypeMap[+A] = TreeSeqMap[Tag[Any], Any]

object TypeMap:
    extension [A](self: TypeMap[A])

        private inline def fatal[T](using t: Tag[T]): Nothing =
            throw new RuntimeException(s"fatal: kyo.TypeMap of contents [${self.show}] missing value of type: [${t.showTpe}].")

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
            self.getOrElse(t.erased, search).asInstanceOf[B]
        end get

        inline def add[B](b: B)(using inline t: Tag[B]): TypeMap[A & B] =
            self.updated(t.erased, b)

        inline def union[B](that: TypeMap[B]): TypeMap[A & B] =
            self ++ that

        def prune[B >: A](using t: Tag[B]): TypeMap[B] =
            if t =:= Tag[Any] then self
            else self.filter { case (tag, _) => tag <:< t }

        inline def size: Int        = self.size
        inline def isEmpty: Boolean = self.isEmpty

        def show: String = self.map { case (tag, value) => s"${tag.showTpe} -> $value" }.toList.sorted.mkString("TypeMap(", ", ", ")")

        private[kyo] inline def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)

        private[kyo] inline def <:<[T](tag: Tag[T]): Boolean =
            self.keySet.exists(_ <:< tag)
    end extension

    given flat[A]: Flat[TypeMap[A]] = Flat.unsafe.bypass

    val empty: TypeMap[Any] = TreeSeqMap.empty(TreeSeqMap.OrderBy.Modification)

    def apply[A](a: A)(using ta: Tag[A]): TypeMap[A] =
        TreeSeqMap(ta.erased -> a).orderingBy(TreeSeqMap.OrderBy.Modification)
    def apply[A, B](a: A, b: B)(using ta: Tag[A], tb: Tag[B]): TypeMap[A & B] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b).orderingBy(TreeSeqMap.OrderBy.Modification)
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C)(using ta: Tag[A], tb: Tag[B], tc: Tag[C]): TypeMap[A & B & C] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b, tc.erased -> c).orderingBy(TreeSeqMap.OrderBy.Modification)
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        td: Tag[D]
    ): TypeMap[A & B & C & D] =
        TreeSeqMap(ta.erased -> a, tb.erased -> b, tc.erased -> c, td.erased -> d).orderingBy(TreeSeqMap.OrderBy.Modification)
end TypeMap
