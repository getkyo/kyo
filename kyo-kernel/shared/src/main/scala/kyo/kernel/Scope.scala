package kyo.kernel

import kyo.Ansi.*
import kyo.Const
import kyo.Flat
import kyo.Frame
import kyo.internal.TypeIntersection
import scala.quoted.*

sealed abstract class Scope[S] //:
// def run[A, S2](v: A < (S & S2))(using Frame): A < (S & S2)

sealed trait CtxEffect extends ContextEffect[Int]
sealed trait ArrEffect extends ArrowEffect[Const[Int], Const[Int]]

val a = summon[Scope.Contextual[CtxEffect]]

object Scope:

    class Contextual[S]

    object Contextual:
        type IsContextEffect[E] <: Boolean =
            E match
                case CtxEffect => true
                case _         => false
        inline given derive[S: TypeIntersection as ts]: Contextual[ts.Filter[IsContextEffect]] = null
    end Contextual

    abstract class Stateful[S]:
        self =>
        type State

        def run[A: Flat, S2](v: A < S2)(using Frame): A < (S & S2) =
            use(resume(_, v).map(restore(_, _)))

        def use[A, S2](f: State => A < S2)(using Frame): A < (S & S2)
        def resume[A: Flat, S2](state: State, v: A < (S & S2))(using Frame): (State, A) < S2
        def restore[A: Flat, S2](state: State, v: A < S2)(using Frame): A < (S & S2)
    end Stateful

    inline given derive[S]: Scope[S] = ${ deriveImpl[S] }

    private def deriveImpl[S: Type](using Quotes): Expr[Scope[S]] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right)        => flatten(left) ++ flatten(right)
                case OrType(left, right)         => report.errorAndAbort("")
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val s = flatten(TypeRepr.of[S])

        val notContext =
            s.filterNot(tpe => (tpe <:< TypeRepr.of[ContextEffect[?]]) || (tpe =:= TypeRepr.of[Any]))

        val Statefuls =
            notContext.map {
                _.asType match
                    case '[tpe] => Expr.summon[Stateful[tpe]]
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
