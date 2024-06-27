package kyo2.kernel

import internal.*
import scala.quoted.*

final class Boundary[Ctx, S] private (dummy: Unit) extends AnyVal:

    inline def apply[A, B, S2, S3](inline v: A < (Ctx & S & S2))(
        inline f: A < (S & S2) => B < S3
    )(using inline _frame: Frame): B < (Ctx & S3) =
        new KyoDefer[B, Ctx & S3]:
            def frame = _frame
            def apply(dummy: Unit, context: Context)(using safepoint: Safepoint) =
                val state = safepoint.save(context)
                def boundaryLoop(v: A < (S & S2)): A < (S & S2) =
                    v match
                        case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                            new KyoSuspend[IX, OX, EX, Any, A, S & S2]:
                                val tag   = kyo.tag
                                val input = kyo.input
                                def frame = _frame
                                def apply(v: OX[Any], context: Context)(using Safepoint) =
                                    val parent = Safepoint.local.get()
                                    Safepoint.local.set(Safepoint(parent.depth, parent.interceptor, state))
                                    val r =
                                        try kyo(v, state.context)
                                        finally Safepoint.local.set(parent)
                                    boundaryLoop(r)
                                end apply
                        case _ =>
                            v
                f(Effect.defer(boundaryLoop(v.asInstanceOf[A < (S & S2)])))
            end apply
        end new
    end apply
end Boundary

object Boundary:

    inline given derive[Ctx, S]: Boundary[Ctx, S] = ${ boundaryImpl[Ctx, S] }

    def apply[Ctx, S](using boundary: Boundary[Ctx, S]): Boundary[Ctx, S] = boundary

    private def create[Ctx, S]: Boundary[Ctx, S] = new Boundary(())

    private def boundaryImpl[Ctx: Type, S: Type](using Quotes): Expr[Boundary[Ctx, S]] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right)        => flatten(left) ++ flatten(right)
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val s = flatten(TypeRepr.of[S])

        val r = flatten(TypeRepr.of[Ctx]).filter(tpe => !s.exists(_ <:< tpe))

        val nok = r.filterNot(tpe => (tpe <:< TypeRepr.of[ContextEffect[?]]) || (tpe =:= TypeRepr.of[Any]))
        if nok.nonEmpty then
            report.errorAndAbort(s"Expected context effects. Found: '${nok.map(_.show).mkString(" & ")}'")

        '{ create[Ctx, S] }
    end boundaryImpl
end Boundary
