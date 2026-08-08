package kyo.kernel2

import kyo.Frame
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import scala.annotation.nowarn

/** An effect that declares the need for a value provided by a handler's scope.
  *
  * Where [[ArrowEffect]] declares operations awaiting implementation, a `ContextEffect[V]` declares a value awaiting provision. Reads
  * observe the innermost binding installed by a handler; handlers install or transform bindings for a scope. Because the state is a plain
  * value with no write-back, context effects fork by copying, which is what makes them cheap to carry across execution boundaries.
  *
  * @tparam V
  *   The type of value provided by handlers of this effect
  */
abstract class ContextEffect[+V] extends Effect

object ContextEffect:

    /** Detaches a computation at a fork boundary, handing it the inherited context.
      *
      * The read is a Defer consuming the threaded context at its execution site, so bindings in scope where the fork executes are
      * visible. Noninheritable bindings are filtered before the fork sees them.
      */
    @nowarn("msg=anonymous")
    private[kyo] def runDetached[A, S](f: Context => A < S)(using _frame: Frame): A < S =
        Kyo.Defer(
            (),
            new Arrow.Transform[Unit, A, S]:
                def frame = _frame
                def run[C, S2](v: Unit, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (S & S2) =
                    cont(f(context.inherit), context, handlers)
        )

    /** A marker trait for context effects that do not persist across asynchronous boundaries.
      *
      * When a context effect extends this trait, its values will not be inherited by child fibers after an asynchronous operation.
      * Instead, child fibers start with fresh values, making these effects behave similarly to non-inheritable thread locals.
      */
    trait Noninheritable:
        self: ContextEffect[?] =>

    /** Reads the value of `E` from the innermost binding in scope.
      *
      * The read is a plain Defer consuming the threaded context in one lookup at its execution site. A read reaching a drive with no
      * binding in scope is a defect: the effect row guarantees a handler for well-typed programs.
      */
    @nowarn("msg=anonymous")
    inline def suspend[V, E <: ContextEffect[V]](
        inline effectTag: Tag[E]
    )(using inline _frame: Frame): V < E =
        // TODO how about Defer is an abstract class so we can override the value as () without creating a field?
        Kyo.Defer(
            (),
            new Arrow.Transform[Unit, V, E]:
                def frame = _frame
                def run[C, S2](v: Unit, context: Context, handlers: Handlers, cont: Arrow[V, C, S2]): C < (E & S2) =
                    cont(context.get[V, E](effectTag), context, handlers)
        )
    end suspend

    /** Reads the value of `E` and maps it in one step. */
    inline def suspendWith[V, E <: ContextEffect[V], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: V => B < S
    )(using inline _frame: Frame): B < (E & S) =
        suspend(effectTag).map(f)

    /** Reads the value of `E` if a binding is in scope, or the default otherwise.
      *
      * The read carries no effect requirement: with no binding in scope the default is used, so the computation runs unhandled.
      */
    @nowarn("msg=anonymous")
    inline def suspend[V, E <: ContextEffect[V]](
        inline effectTag: Tag[E],
        inline default: => V
    )(using inline _frame: Frame): V < Any =
        val fallback: () => V = () => default
        Kyo.Defer(
            (),
            new Arrow.Transform[Unit, V, Any]:
                def frame = _frame
                def run[C, S2](v: Unit, context: Context, handlers: Handlers, cont: Arrow[V, C, S2]): C < (Any & S2) =
                    cont(context.getOrElse[V, E, V](effectTag, fallback()), context, handlers)
        )
    end suspend

    /** Reads the value of `E` if a binding is in scope, or the default otherwise, and maps it in one step. */
    inline def suspendWith[V, E <: ContextEffect[V], B, S](
        inline effectTag: Tag[E],
        inline default: => V
    )(
        inline f: V => B < S
    )(using inline _frame: Frame): B < S =
        suspend(effectTag, default).map(f)

    /** Provides a constant binding for `E` within the computation's scope. */
    def handle[V, E <: ContextEffect[V], A, S](
        effectTag: Tag[E],
        value: V
    )(v: A < (E & S))(using frame: Frame): A < S =
        handle(effectTag, value, _ => value)(v)

    /** Provides a binding for `E`, transforming any outer binding.
      *
      * `ifUndefined` supplies the value when no outer binding exists; `ifDefined` derives this scope's value from the outer one, read
      * from the incoming context at each entry into the bound computation, so nested handlers compose: reads observe the innermost
      * binding, and each binding sees the resolution of the bindings outside it at execution time. The binding rotates with the
      * computation, wrapped around each suspended remainder, so every resumption re-derives it from the resume-time context and
      * multi-shot continuations re-run it per invocation.
      */
    def handle[V, E <: ContextEffect[V], A, S](
        effectTag: Tag[E],
        ifUndefined: => V,
        ifDefined: V => V
    )(v: A < (E & S))(using frame: Frame): A < S =
        val bind: Context => Context =
            context =>
                val value =
                    if context.contains(effectTag) then ifDefined(context.get[V, E](effectTag))
                    else ifUndefined
                context.set(effectTag, value)
        def loop(w: A < (E & S), context: Context, handlers: Handlers): A < S =
            w match
                case k: Kyo[A, E & S] @unchecked =>
                    ArrowEffect.rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S]) => ArrowEffect.Rotate.binding(chain, bind, loop, frame),
                        loop,
                        context,
                        handlers
                    )
                case v =>
                    // settled: every read of E inside the bound computation resolved against
                    // this binding through the threaded context, discharging E from the row
                    v.asInstanceOf[A < S]
        loop(v, Context.empty, Handlers.empty)
    end handle

end ContextEffect
