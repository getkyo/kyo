package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.proto.Arrow
import kyo.proto.Arrow.Step
import kyo.proto.Arrow.Transform
import kyo.proto.kernel.internal.*
import language.implicitConversions
import scala.annotation.nowarn

opaque type <[+A, -S] = A | Pending[A, S]

object `<` extends Implicits:
    implicit def fromKyo[A, S](kyo: Pending[A, S]): A < S = kyo

    extension [A, S](inline self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then

                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f(Nested.unnest(v)), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end map

        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then

                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f(Nested.unnest(v)), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then

                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f, cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end andThen

        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            def run[C, S3](v: A < S3, cont: Arrow[Unit, C, S3]): C < S3 =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then

                    new Kyo.DeferTransform[A, C, S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head((), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end unit

        inline def handle[B](inline f: (=> A < S) => B): B =
            def h1 = self
            f(h1)
        end handle

        inline def handle[B, C](inline f1: (=> A < S) => B, inline f2: (=> B) => C): C =
            def h1 = self
            def h2 = f1(h1)
            f2(h2)
        end handle

        inline def handle[B, C, D](inline f1: (=> A < S) => B, inline f2: (=> B) => C, inline f3: (=> C) => D): D =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            f3(h3)
        end handle

        inline def handle[B, C, D, E](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            f4(h4)
        end handle

        inline def handle[B, C, D, E, F](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            f5(h5)
        end handle

        inline def handle[B, C, D, E, F, G](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            f6(h6)
        end handle

        inline def handle[B, C, D, E, F, G, H](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H
        ): H =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            f7(h7)
        end handle

        inline def handle[B, C, D, E, F, G, H, I](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I
        ): I =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            def h8 = f7(h7)
            f8(h8)
        end handle

        inline def handle[B, C, D, E, F, G, H, I, J](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J
        ): J =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            def h8 = f7(h7)
            def h9 = f8(h8)
            f9(h9)
        end handle

        inline def handle[B, C, D, E, F, G, H, I, J, K](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J,
            inline f10: (=> J) => K
        ): K =
            def h1  = self
            def h2  = f1(h1)
            def h3  = f2(h2)
            def h4  = f3(h3)
            def h5  = f4(h4)
            def h6  = f5(h5)
            def h7  = f6(h6)
            def h8  = f7(h7)
            def h9  = f8(h8)
            def h10 = f9(h9)
            f10(h10)
        end handle

        private[kyo] inline def evalNow: Maybe[A] =
            val v = self
            v match
                case _: Pending[?, ?] => Maybe.empty
                case _                => Maybe(Nested.unnest(v))
        end evalNow
    end extension

    extension [A, S](self: A < S)

        def chain[B, S2](cont: Arrow[A, B, S2]): B < (S & S2) =
            self match
                case kyo: Pending[A, S] @unchecked =>
                    if cont.isInstanceOf[Arrow.Id[?]] then

                        kyo.asInstanceOf[B < (S & S2)]
                    else
                        kyo match
                            case kyo: Kyo.Suspend[?, A, S] @unchecked if kyo.cont.isInstanceOf[Arrow.Id[?]] =>

                                kyo.withCont(cont.asInstanceOf[Arrow[kyo.Op, B, S & S2]])
                            case kyo: Kyo.Handle[e, x, b, A, S, st] @unchecked if kyo.cont.isInstanceOf[Arrow.Id[?]] =>

                                Kyo.Handle[e, x, b, B, S & S2, st](
                                    kyo.value,
                                    kyo.handler,
                                    kyo.state,
                                    cont.asInstanceOf[Arrow[b, B, S & S2]]
                                )
                            case kyo: Kyo.Defer[a, b, A, S] @unchecked
                                if kyo.contB.isInstanceOf[Arrow.Id[?]] && !(kyo.contA eq kyo) =>

                                Effect.defer(kyo.value, kyo.contA, cont.asInstanceOf[Arrow[b, B, S & S2]])
                            case _ =>
                                Effect.defer(kyo, cont)
                case _ =>
                    cont.head(self.asInstanceOf[A], cont.tail)
            end match
        end chain
    end extension

    extension [A, S, S2](self: A < S < S2)

        @nowarn("msg=anonymous")
        def flatten(using _frame: Frame): A < (S & S2) =
            def arrow: Arrow[A < S, A, S] =
                new Step[A < S, A, S]:
                    def frame                                                = _frame
                    def apply[C, S3](v: (A < S) < S3, cont: Arrow[A, C, S3]) = run(v, cont)
            def run[C, S3](v: (A < S) < S3, cont: Arrow[A, C, S3]): C < (S & S3) =
                v match
                    case kyo: Pending[A < S, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head(Nested.unnest[A < S](v), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end flatten
    end extension

    extension [A](inline v: A < Any)

        inline def eval(using inline frame: Frame): A =

            val v0 = v
            v0 match
                case _: Pending[?, ?] => Nested.unnest[A](Eval(v0.asInstanceOf[A < Any]))
                case _                => Nested.unnest(v0)
        end eval
    end extension

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String =
            value match
                case kyo: Pending[?, ?] => kyo.toString
                case nested: Nested[?]  => s"Kyo(${nested.value})"
                case a: A @unchecked    => s"Kyo(${ra.asString(a)})"
    end given
end `<`
