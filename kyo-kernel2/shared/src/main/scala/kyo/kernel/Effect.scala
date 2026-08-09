package kyo.kernel

import kyo.Frame
import kyo.kernel.internal.Context
import kyo.kernel.internal.EffectTrace
import kyo.kernel.internal.Handlers
import kyo.kernel.internal.Kyo
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** The common parent of the two effect kinds.
  *
  * An effect kind is a declaration of what a handler provides. [[ArrowEffect]] declares operations, interpreted by a handle function per
  * operation. [[ContextEffect]] declares a value, provided by a handler for a scope. The kinds differ in boundary semantics: context state
  * is copyable across forks, control state lives in the handler's interpretation.
  */
abstract class Effect private[kernel] ()

object Effect:

    /** Wraps a computation with error handling.
      *
      * The error handler `f` is called if a non-fatal exception is thrown during the initial evaluation or during any later step of the
      * guarded computation, including steps that run after the computation suspends and resumes: the guard rotates with the computation,
      * wrapped around each suspended remainder. A throw in steps appended AFTER this call is outside the guarded computation and is not
      * intercepted: scope is structural.
      */
    def catching[A, S, B >: A, S2](v: => A < S)(
        f: Throwable => B < S2
    )(using _frame: Frame): B < (S & S2) =
        def loop(w: B < (S & S2), context: Context, handlers: Handlers): B < (S & S2) =
            w match
                case k: Kyo[B, S & S2] @unchecked =>
                    ArrowEffect.rotate(
                        k,
                        [X] => (chain: Arrow[X, B, S & S2]) => ArrowEffect.Rotate.guard(chain, f, loop, _frame),
                        loop,
                        context,
                        handlers
                    )
                case v =>
                    v
        try loop(v, Context.empty, Handlers.empty)
        catch
            case ex if NonFatal(ex) =>
                EffectTrace.attach(ex, "catching", _frame)
                f(ex)
        end try
    end catching

    /** Suspends a computation so it runs when driven, not when constructed.
      *
      * The thunk is evaluated each time the resulting computation is driven past this point, on the driver's stack, inside the trampoline.
      * This is the kernel's lazy primitive: side effects wrapped in `defer` do not run at construction time.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def defer[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        val thunk = new Arrow.Transform[Unit, A, S]:
            def frame = _frame
            def run[C, S2](v: Unit, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (S & S2) =
                cont(f, context, handlers)
        Kyo.Defer((), thunk)
    end defer

    /** Acquires a resource, uses it, and guarantees release.
      *
      * `release` runs exactly once when `use` completes, fails with an exception, or when the computation is discarded after a park. If the
      * computation suspends while the resource is held, the release is carried in the parked continuation and runs when the resumed
      * computation completes or is discarded.
      */
    @nowarn("msg=anonymous")
    private[kyo] inline def bracket[R, A, S](
        inline acquireF: => R < S
    )(
        inline releaseF: R => Unit < S
    )(
        inline useF: R => A < S
    )(using inline _frame: Frame): A < S =
        new Kyo.Bracket[R, A, S]:
            val useArrow: Arrow[R, A, S] =
                new Arrow.Transform[R, A, S]:
                    def frame = _frame
                    def run[C, S2](v: R, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (S & S2) =
                        cont(useF(v), context, handlers)
            def acquire       = acquireF
            def release(r: R) = releaseF(r)
            def cont          = useArrow
            def frame         = _frame

end Effect
