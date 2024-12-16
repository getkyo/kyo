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
sealed abstract class Boundary[Ctx, +S]
object Boundary:
    private val instance = new Boundary[Any, Any] {}
    private def unsafe[Ctx, S]: Boundary[Ctx, S] =
        instance.asInstanceOf[Boundary[Ctx, S]]

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

    private def boundaryImpl[Ctx: Type, S: Type](using Quotes): Expr[Boundary[Ctx, S]] =
        import quotes.reflect.*
        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right) => flatten(left) ++ flatten(right)
                case OrType(left, right) => report.errorAndAbort(
                        s"Boundary: Unsupported type union in Pending Effects: ${tpe.show}\n".red +
                            "This should be unreachable, please open an issue 🥹 https://github.com/getkyo/kyo/issues".yellow
                    )
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
                    |You have two options to handle these effects:
                    |
                    |1. Handle the effects before the async operation:
                    |   Instead of:
                    |     Async.race(computation1, computation2) // where computations have pending MyEffect
                    |
                    |   Handle the effect first:
                    |     Async.race(MyEffect.run(computation1), MyEffect.run(computation2))
                    |
                    |2. Use isolates to manage effect state:
                    |   Instead of:
                    |     Async.race(computation1, computation2) // where computations have MyEffect
                    |
                    |   Use an isolate:
                    |     val isolate = MyEffect.isolate.someStrategy  // Check effect companion objects for available isolates
                    |     Async.race(isolate)(computation1, computation2)
                    |
                    |Isolates can be composed for multiple effects:
                    |  val combined = effect1.isolate.someStrategy.andThen(effect2.isolate.someStrategy)
                    |  Async.race(combined)(computation1, computation2)
                    |""".stripMargin
            )
        end if

        '{ unsafe[Ctx, S] }
    end boundaryImpl
end Boundary
