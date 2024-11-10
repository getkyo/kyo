package kyo.kernel

import internal.*
import kyo.Ansi.*
import kyo.Frame
import scala.annotation.implicitNotFound
import scala.annotation.nowarn
import scala.quoted.*

/** Ensures type-safe transitions of effects across asynchronous boundaries.
  *
  * When code requests a Boundary (typically through implicit resolution), it means that effects need to be handled before crossing an
  * asynchronous boundary. Only [[ContextEffect]]s can cross boundaries, while [[ArrowEffect]]s must be handled first because they represent
  * operations that require explicit interpretation.
  *
  * If the compiler requests a Boundary, either:
  *   - Handle any non-ContextEffects in the computation before the boundary
  *   - Or split nested boundary operations into separate statements if you see the "nested Async operations" error
  *
  * @tparam Ctx
  *   The context effects that will cross the boundary
  * @tparam S
  *   The source effects that must be handled before crossing
  */
@implicitNotFound("""
Could not create an Async boundary for effects: ${Ctx}

Note: If you're seeing this error with nested Async operations (like Async.run(Async.run(v))),
this is due to a current limitation. As a workaround, you can break them into separate statements:

Instead of:
  Async.run(Async.run(v))

Use:
  val x = Async.run(v)
  Async.run(x)
""")
final class Boundary[Ctx, +S] private (dummy: Unit) extends AnyVal

object Boundary:

    extension [Ctx, S](boundary: Boundary[Ctx, S])
        @nowarn("msg=anonymous")
        private[kyo] inline def apply[A, S2](inline f: (Trace, Context) => A < S2)(using inline _frame: Frame): A < (S & S2) =
            new KyoDefer[A, S & S2]:
                def frame = _frame
                def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
                    f(safepoint.saveTrace(), context.inherit)

    inline given derive[Ctx, S]: Boundary[Ctx, S] = ${ boundaryImpl[Ctx, S] }

    private[kyo] inline def restoring[Ctx, A, S](trace: Trace, interceptor: Safepoint.Interceptor)(
        inline v: => A < (Ctx & S)
    )(using frame: Frame, safepoint: Safepoint): A < (Ctx & S) =
        Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))

    private def create[Ctx, S]: Boundary[Ctx, S] = new Boundary(())

    private def boundaryImpl[Ctx: Type, S: Type](using Quotes): Expr[Boundary[Ctx, S]] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right)        => flatten(left) ++ flatten(right)
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val s = flatten(TypeRepr.of[S])

        val r = flatten(TypeRepr.of[Ctx]).filter(tpe => !s.exists(tpe <:< _))

        val nok = r.filterNot(tpe => (tpe <:< TypeRepr.of[ContextEffect[?]]) || (tpe =:= TypeRepr.of[Any]))
        if nok.nonEmpty then
            report.errorAndAbort(
                s"""|The computation you're trying to fork with Async has pending effects that aren't supported: 
                    |
                    |  ${nok.map(_.show.red).mkString(" & ")}
                    |
                    |You need to handle these effects before using Async operations. For example:
                    |
                    |Instead of:
                    |  Async.run(computation) // where computation has pending MyEffect
                    |
                    |Handle the effect first:
                    |  Async.run(MyEffect.run(computation))
                    |
                    |Note: There's currently a limitation with nested Async operations (like Async.run(Async.run(v))).
                    |As a workaround, you can break them into separate statements:
                    |
                    |Instead of:
                    |  Async.run(Async.run(v))
                    |
                    |Use:
                    |  val x = Async.run(v)
                    |  Async.run(x)
                    |""".stripMargin
            )
        end if

        '{ create[Ctx, S] }
    end boundaryImpl
end Boundary
