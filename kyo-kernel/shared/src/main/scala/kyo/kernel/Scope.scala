package kyo.kernel

import kyo.Ansi.*
import kyo.Const
import kyo.Flat
import kyo.Frame
import kyo.Tag
import kyo.internal.TypeIntersection
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.quoted.*

object Scope:

    sealed abstract class Contextual[S]:
        def run[A, S2](v: A < (S & S2)): A < S2
        @nowarn("msg=anonymous")
        private[kyo] inline def runInternal[A, S2](inline f: (Trace, Context) => A < S2)(using inline _frame: Frame): A < (S & S2) =
            new KyoDefer[A, S & S2]:
                def frame = _frame
                def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
                    f(safepoint.saveTrace(), context.inherit)
    end Contextual

    object Contextual:
        private val cached: Contextual[Any] =
            new Contextual[Any]:
                def run[A, S2](v: A < S2): A < S2 = v

        def init[A, S <: ContextEffect[A]]: Contextual[S] =
            cached.asInstanceOf[Contextual[S]]

        inline given derive[S]: Contextual[S] = ${ deriveImpl[S] }

        private def deriveImpl[S: Type](using Quotes): Expr[Scope.Contextual[S]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case AndType(left, right)        => flatten(left) ++ flatten(right)
                    case OrType(left, right)         => report.errorAndAbort("")
                    case t if t =:= TypeRepr.of[Any] => Nil
                    case t                           => List(t)

            val s = flatten(TypeRepr.of[S])

            val missing =
                flatten(TypeRepr.of[S]).filter { t =>
                    t.asType match
                        case '[tpe] => Expr.summon[Scope.Contextual[tpe]].isEmpty
                }

            if missing.nonEmpty then
                report.errorAndAbort("missing! " + missing.map(_.show))

            '{ cached.asInstanceOf[Scope.Contextual[S]] }
        end deriveImpl
    end Contextual

    abstract class Stateful[S]:
        self =>

        type State

        def run[A: Flat, S2](v: A < (S & S2))(using Frame): A < (S & S2) =
            use(resume(_, v).map(restore(_, _)))

        def use[A, S2](f: State => A < S2)(using Frame): A < (S & S2)

        def resume[A: Flat, S2](state: State, v: A < (S & S2))(using Frame): (State, A) < S2

        def restore[A: Flat, S2](state: State, v: A < S2)(using Frame): A < (S & S2)

        def andThen[S2](next: Scope.Stateful[S2]): Scope.Stateful[S & S2] =
            new Stateful[S & S2]:
                type State = (self.State, next.State)
                def use[A, S2](f: State => A < S2)(using Frame) =
                    self.use(s1 => next.use(s2 => f((s1, s2))))
                def resume[A: Flat, S3](state: (self.State, next.State), v: A < (S & S2 & S3))(using Frame) =
                    self.resume(state._1, next.resume(state._2, v)).map {
                        case (s1, (s2, r)) => ((s1, s2), r)
                    }
                def restore[A: Flat, S2](state: (self.State, next.State), v: A < S2)(using Frame) =
                    self.restore(state._1, next.restore(state._2, v))
            end new
        end andThen
    end Stateful

    object Stateful:

        val noop: Stateful[Any] = new Stateful[Any]:
            type State = Unit
            def use[A, S2](f: State => A < S2)(using Frame)               = f(())
            def resume[A: Flat, S2](state: Unit, v: A < S2)(using Frame)  = v.map(((), _))
            def restore[A: Flat, S2](state: Unit, v: A < S2)(using Frame) = v
            override def andThen[S2](next: Stateful[S2])                  = next

        inline given derive[S]: Stateful[S] = ${ deriveImpl[S] }

        private def deriveImpl[S: Type](using Quotes): Expr[Scope.Stateful[S]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case AndType(left, right)        => flatten(left) ++ flatten(right)
                    case OrType(left, right)         => report.errorAndAbort("")
                    case t if t =:= TypeRepr.of[Any] => Nil
                    case t                           => List(t)

            val s = flatten(TypeRepr.of[S])

            val scopes =
                flatten(TypeRepr.of[S]).map { t =>
                    t.asType match
                        case '[tpe] =>
                            t ->
                                Expr.summon[Scope.Contextual[tpe]].map(Left(_)).orElse(
                                    Expr.summon[Scope.Stateful[tpe]].map(Right(_))
                                )
                }

            scopes.filter(_._2.isEmpty) match
                case Nil =>
                case missing =>
                    report.errorAndAbort("missing! " + missing.map(_._1.show))
            end match

            val statefulScopes =
                scopes.flatMap {
                    case (_, Some(Right(scope))) => Some(scope)
                    case _                       => None
                }

            statefulScopes.foldLeft('{ noop.asInstanceOf[Stateful[S]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Scope.Stateful[S]]) }
            ).asExprOf[Scope.Stateful[S]]
        end deriveImpl
    end Stateful

end Scope
