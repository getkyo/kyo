package kyo

import Layers.internal.*
import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap
import kyo.core.*
import scala.annotation.targetName

sealed trait Layer[+Out, -S]:
    self =>

    infix def to[Out2, S2, In2](that: Layer[Out2, Envs[In2] & S2]): Layer[Out2, S & S2]          = To(self, that)
    infix def and[Out2, S2](that: Layer[Out2, S2]): Layer[Out & Out2, S & S2]                    = And(self, that)
    infix def using[Out2, S2, In2](that: Layer[Out2, Envs[In2] & S2]): Layer[Out & Out2, S & S2] = self and (self to that)

    private[kyo] def doRun(memoMap: JConcurrentHashMap[Layer[?, ?], Any] = JConcurrentHashMap()): TypeMap[Out] < (S & IOs) =
        type Expected = TypeMap[Out] < (S & IOs)
        memoMap.get(self) match
            case nullable if isNull(nullable) =>
                self match
                    case And(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- rhs.doRun(memoMap)
                            yield leftResult.union(rightResult)
                        }.asInstanceOf[Expected]

                    case To(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- Envs.runTypeMap(leftResult)(rhs.doRun(memoMap))(using summon, Envs.bypass)
                            yield rightResult
                        }.asInstanceOf[Expected]

                    case FromKyo(kyo) =>
                        kyo().map(result =>
                            IOs {
                                memoMap.putIfAbsent(self, result)
                                result
                            }
                        ).asInstanceOf[Expected]

            case result => result.asInstanceOf[Expected]
        end match
    end doRun

end Layer

object Layer:
    import Envs.HasEnvs

    extension [In, Out, S](layer: Layer[Out, Envs[In] & S])
        def run[In1, R](using HasEnvs[In1, In] { type Remainder = R }): TypeMap[Out] < (S & R & IOs) =
            layer.doRun().asInstanceOf
end Layer

object Layers:
    private[kyo] object internal:
        case class And[Out1, Out2, S1, S2](lhs: Layer[Out1, S1], rhs: Layer[Out2, S2]) extends Layer[Out1 & Out2, S1 & S2]
        case class To[Out1, Out2, S1, S2](lhs: Layer[?, ?], rhs: Layer[?, ?])          extends Layer[Out1 & Out2, S1 & S2]
        case class FromKyo[In, Out, S](kyo: () => TypeMap[Out] < (Envs[In] & S))(using val tag: Tag[Out]) extends Layer[Out, S]
    end internal

    val empty: Layer[Any, Any] = FromKyo { () => TypeMap.empty }

    def apply[A: Tag, S](kyo: => A < S): Layer[A, S] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    def from[A: Tag, B: Tag, S](f: A => B < S): Layer[B, Envs[A] & S] =
        apply {
            Envs.get[A].map(f)
        }

    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S): Layer[C, Envs[A & B] & S] =
        apply {
            zip(Envs.get[A], Envs.get[B]).map { case (a, b) => f(a, b) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S): Layer[D, Envs[A & B & C] & S] =
        apply {
            zip(Envs.get[A], Envs.get[B], Envs.get[C])
                .map { case (a, b, c) => f(a, b, c) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S): Layer[E, Envs[A & B & C & D] & S] =
        apply {
            zip(Envs.get[A], Envs.get[B], Envs.get[C], Envs.get[D]).map { case (a, b, c, d) => f(a, b, c, d) }
        }

end Layers
