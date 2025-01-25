package kyo.kernel

import kyo.Ansi.*
import kyo.Const
import kyo.Flat
import kyo.Frame
import kyo.internal.TypeIntersection
import kyo.kernel.Scope.Contextual
import scala.quoted.*

sealed abstract class Scope[Passthrough, Retain] //:
// def run[A, S2](v: A < (Retain & S2))(using Frame): A < (Retain & S2)

sealed trait CtxEffect extends ContextEffect[Int]
sealed trait ArrEffect extends ArrowEffect[Const[Int], Const[Int]]

object Scope:

    final class Contextual[Passthrough, Retain] extends Scope[Passthrough, Retain]

    object Contextual:
        private val cached = new Contextual[Any, Any]

        private def deriveImpl[P: Type, R: Type](using Quotes): Expr[Contextual[P, R]] =
            import quotes.reflect.*
            filter[P, R] match
                case Nil =>
                    '{ cached.asInstanceOf[Contextual[P, R]] }
                case list =>
                    report.errorAndAbort("Not contextual: " + list.map(_.show))
            end match
        end deriveImpl
    end Contextual

    abstract class Stateful[Passthrough, Retain] extends Scope[Passthrough, Retain]:
        self =>
        type State

        def run[A: Flat, S2](v: A < S2)(using Frame): A < (Retain & S2) =
            use(resume(_, v).map(restore(_, _)))

        def use[A, S2](f: State => A < S2)(using Frame): A < (Retain & S2)
        def resume[A: Flat, S2](state: State, v: A < (Retain & S2))(using Frame): (State, A) < S2
        def restore[A: Flat, S2](state: State, v: A < S2)(using Frame): A < (Retain & S2)
    end Stateful

    object Stateful:
        inline given derive[P, R]: Stateful[P, R] = ${ deriveImpl[P, R, Stateful](false) }

    private def filter[Passthrough: Type, Retain: Type](using q: Quotes): Seq[q.reflect.TypeRepr] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right)        => flatten(left) ++ flatten(right)
                case OrType(left, right)         => report.errorAndAbort("")
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val passthrough = flatten(TypeRepr.of[Passthrough])

        flatten(TypeRepr.of[Retain]).filterNot(tpe =>
            (tpe <:< TypeRepr.of[ContextEffect[?]]) ||
                (tpe =:= TypeRepr.of[Any]) ||
                passthrough.contains(tpe)
        )
    end filter

    private def deriveImpl[Passthrough: Type, Retain: Type, F[_, _] <: Scope[?, ?]: Type](isContextual: Boolean)(using
        Quotes
    ): Expr[F[Passthrough, Retain]] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right)        => flatten(left) ++ flatten(right)
                case OrType(left, right)         => report.errorAndAbort("")
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val s = flatten(TypeRepr.of[Retain])

        report.warning("blah")

        val notContext =
            s.filterNot(tpe => (tpe <:< TypeRepr.of[ContextEffect[?]]) || (tpe =:= TypeRepr.of[Any]))

        val Statefuls =
            notContext.map {
                _.asType match
                    case '[tpe] => Expr.summon[Stateful[Passthrough, tpe]]
            }

        notContext.zip(Statefuls) match
            case Nil =>
                report.warning("good, only context effects")
                '{ ??? }
            case (tpe, None) :: Nil =>
                report.errorAndAbort("missing one isolate: " + tpe.show)
            case (tpe, Some(isolate)) :: Nil =>
                report.warning("good, found one isolate " + tpe.show)
                '{ ??? }
            case _ =>
                report.errorAndAbort("missing multiple isolate: " + notContext.map(_.show))
        end match

    end deriveImpl
end Scope
