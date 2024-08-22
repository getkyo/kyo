package kyo

import Layer.internal.*
import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap
import kyo.Tag
import kyo.kernel.Reducible
import scala.annotation.targetName

abstract class Layer[+Out, -S]:
    self =>

    final infix def to[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out2, S & S2]          = To(self, that)
    final infix def and[Out2, S2](that: Layer[Out2, S2]): Layer[Out & Out2, S & S2]                   = And(self, that)
    final infix def using[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out & Out2, S & S2] = self and (self to that)

end Layer

object Layer:

    extension [In, Out, S](layer: Layer[Out, Env[In] & S])
        def run[R](using reduce: Reducible[Env[In]]): TypeMap[Out] < (S & reduce.SReduced & Memo) =
            reduce(doRun(layer))

    val empty: Layer[Any, Any] = FromKyo { () => TypeMap.empty }

    def apply[A: Tag, S](kyo: => A < S)(using Frame): Layer[A, S] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    def from[A: Tag, B: Tag, S](f: A => B < S)(using Frame): Layer[B, Env[A] & S] =
        apply {
            Env.get[A].map(f)
        }

    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S)(using Frame): Layer[C, Env[A & B] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B]).map { case (a, b) => f(a, b) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S)(using Frame): Layer[D, Env[A & B & C] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C])
                .map { case (a, b, c) => f(a, b, c) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S)(using Frame): Layer[E, Env[A & B & C & D] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D]).map { case (a, b, c, d) => f(a, b, c, d) }
        }

    transparent inline def init[Target](inline layers: Layer[?, ?]*): Layer[Target, ?] =
        kyo.internal.LayerMacros.make[Target](layers*)

    private[kyo] object internal:
        case class And[Out1, Out2, S1, S2](lhs: Layer[Out1, S1], rhs: Layer[Out2, S2])                   extends Layer[Out1 & Out2, S1 & S2]
        case class To[Out1, Out2, S1, S2](lhs: Layer[?, ?], rhs: Layer[?, ?])                            extends Layer[Out1 & Out2, S1 & S2]
        case class FromKyo[In, Out, S](kyo: () => TypeMap[Out] < (Env[In] & S))(using val tag: Tag[Out]) extends Layer[Out, S]

        private given Frame = Frame.internal

        class DoRun[Out, S]:
            private val memo = Memo[Layer[Out, S], TypeMap[Out], S & Memo] { self =>
                type Expected = TypeMap[Out] < (S & Memo)
                self match
                    case And(lhs, rhs) =>
                        {
                            for
                                leftResult  <- doRun(lhs)
                                rightResult <- doRun(rhs)
                            yield leftResult.union(rightResult)
                        }.asInstanceOf[Expected]

                    case To(lhs, rhs) =>
                        {
                            for
                                leftResult  <- doRun(lhs)
                                rightResult <- Env.runTypeMap(leftResult)(doRun(rhs))
                            yield rightResult
                        }.asInstanceOf[Expected]

                    case FromKyo(kyo) =>
                        kyo().asInstanceOf[Expected]
                end match
            }
            def apply(layer: Layer[Out, S]): TypeMap[Out] < (S & Memo) = memo(layer)
        end DoRun

        private val _doRun               = new DoRun
        def doRun[Out, S]: DoRun[Out, S] = _doRun.asInstanceOf[DoRun[Out, S]]

    end internal

end Layer
