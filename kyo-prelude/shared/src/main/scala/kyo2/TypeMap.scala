package kyo2

import kyo.Tag
import kyo.Tag.Intersection
import scala.collection.immutable.HashMap

opaque type TypeMap[+A] = HashMap[Tag[Any], Any]

object TypeMap:
    extension [A](self: TypeMap[A])

        private inline def fatal[T](using t: Tag[T]): Nothing =
            throw new RuntimeException(s"fatal: kyo.TypeMap of contents [${self.show}] missing value of type: [${t.showTpe}].")

        def get[B >: A](using t: Tag[B]): B =
            val b = self.getOrElse(t.erased, null)
            if !isNull(b) then b.asInstanceOf[B]
            else
                var sub: B = null.asInstanceOf[B]
                val it     = self.iterator
                try // iterator should never throw given type constraint on B
                    while isNull(sub) do
                        val (tag, item) = it.next()
                        if tag <:< t then
                            sub = item.asInstanceOf[B]
                    end while
                catch
                    case _: NoSuchElementException => fatal
                end try
                sub
            end if
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

        private[kyo2] inline def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)

        private[kyo2] inline def <:<[T](tag: Tag[T]): Boolean =
            self.keySet.exists(_ <:< tag)
    end extension

    val empty: TypeMap[Any] = HashMap.empty

    def apply[A](a: A)(using ta: Tag[A]): TypeMap[A] =
        HashMap(ta.erased -> a)
    def apply[A, B](a: A, b: B)(using ta: Tag[A], tb: Tag[B]): TypeMap[A & B] =
        HashMap(ta.erased -> a, tb.erased -> b)
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C)(using ta: Tag[A], tb: Tag[B], tc: Tag[C]): TypeMap[A & B & C] =
        HashMap(ta.erased -> a, tb.erased -> b, tc.erased -> c)
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        td: Tag[D]
    ): TypeMap[A & B & C & D] =
        HashMap(
            ta.erased -> a,
            tb.erased -> b,
            tc.erased -> c,
            td.erased -> d
        )
end TypeMap
