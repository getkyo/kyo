package kyo

import kyo.Tag.Intersection
import scala.annotation.implicitNotFound
import scala.collection.immutable.HashMap
import scala.util.NotGiven

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

        inline def add[B](b: B)(using
            inline t: Tag[B],
            @implicitNotFound("add requires distinct types. Use replace instead.") inline ev1: NotGiven[A <:< B],
            @implicitNotFound("add requires distinct types. Use replace instead.") inline ev2: NotGiven[B <:< A]
        ): TypeMap[A & B] =
            self.updated(t.erased, b)

        inline def union[B](that: TypeMap[B])(
            using
            @implicitNotFound("union requires distinct types. Use merge instead.")
            inline ev1: NotGiven[A <:< B],
            @implicitNotFound("union requires distinct types. Use merge instead.")
            inline ev2: NotGiven[B <:< A]
        ): TypeMap[A & B] =
            self ++ that

        // TODO: This O(n*m)
        inline def merge[B](that: TypeMap[B]): TypeMap[A & B] =
            self.view.filterKeys(key => that.keysIterator.forall(_ <:!< key)).to[TypeMap[A]](HashMap) ++ that

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

    extension [A, B](self: TypeMap[A & B])
        def replace[C <: A](c: C)(using
            tA: Tag[A],
            tC: Tag[C],
            evA: NotGiven[A =:= Any],
            evB: NotGiven[B =:= Any]
        ): TypeMap[B & C] =
            self.removed(tA.erased).updated(tC.erased, c)

        def replaceAll[C <: A](c: C)(using
            t: Tag[C],
            evA: NotGiven[A =:= Any],
            evB: NotGiven[B =:= Any]
        ): TypeMap[B & C] =
            self.view.filterKeys(_ >:!> t).to[TypeMap[B]](HashMap).updated(t.erased, c)
    end extension

    given flat[A]: Flat[TypeMap[A]] = Flat.unsafe.bypass

    val empty: TypeMap[Any] = HashMap.empty

    def apply[A](a: A)(using ta: Tag[A]): TypeMap[A] =
        HashMap(ta.erased -> a)
    def apply[A, B](a: A, b: B)(using
        ta: Tag[A],
        tb: Tag[B],
        @implicitNotFound("TypeMap requires distinct types.")
        ev1: NotGiven[A <:< B],
        @implicitNotFound("TypeMap requires distinct types.")
        ev2: NotGiven[B <:< A]
    ): TypeMap[A & B] =
        HashMap(ta.erased -> a, tb.erased -> b)
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        @implicitNotFound("TypeMap requires distinct types.")
        ev1: NotGiven[A <:< B],
        @implicitNotFound("TypeMap requires distinct types.")
        ev2: NotGiven[B <:< A],
        @implicitNotFound("TypeMap requires distinct types.")
        ev3: NotGiven[B <:< C],
        @implicitNotFound("TypeMap requires distinct types.")
        ev4: NotGiven[C <:< B]
    ): TypeMap[A & B & C] =
        HashMap(ta.erased -> a, tb.erased -> b, tc.erased -> c)
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        td: Tag[D],
        @implicitNotFound("TypeMap requires distinct types.")
        ev1: NotGiven[A <:< B],
        @implicitNotFound("TypeMap requires distinct types.")
        ev2: NotGiven[B <:< A],
        @implicitNotFound("TypeMap requires distinct types.")
        ev3: NotGiven[B <:< C],
        @implicitNotFound("TypeMap requires distinct types.")
        ev4: NotGiven[C <:< B],
        @implicitNotFound("TypeMap requires distinct types.")
        ev5: NotGiven[C <:< D],
        @implicitNotFound("TypeMap requires distinct types.")
        ev6: NotGiven[D <:< C]
    ): TypeMap[A & B & C & D] =
        HashMap(
            ta.erased -> a,
            tb.erased -> b,
            tc.erased -> c,
            td.erased -> d
        )
end TypeMap
