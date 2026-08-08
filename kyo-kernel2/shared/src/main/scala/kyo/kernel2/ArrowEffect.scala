package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.EffectTrace
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import kyo.kernel2.internal.ResumeHandler
import kyo.kernel2.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** An effect whose operations are functions awaiting implementation.
  *
  * An `ArrowEffect[I, O]` declares a family of operations indexed by a type `C`: each operation takes an input `I[C]` and produces an
  * output `O[C]`. Suspending an operation describes it as data; a handler interprets it, choosing per handle site how much control it
  * needs: answer in place, stop the computation, or receive the continuation as a first-class [[Arrow]].
  *
  * Handling is a structural loop over the computation, the rotation law as execution: an operation of the handled effect is handled
  * where it surfaces, and a suspension of any other effect crosses the handler outward while the handler rotates into its continuation
  * (a [[ArrowEffect.Rotate]] step), staying wrapped around the rest of its computation. Fun-format handlers additionally register in
  * the threaded [[Handlers]] parameter, so their operations are answered locally at the point they surface, with no continuation built.
  *
  * Effects that never resume declare it in their output type: an effect with output `Const[Nothing]` cannot be resumed by any handler,
  * whatever format the handler uses.
  *
  * @tparam I
  *   The operation input constructor
  * @tparam O
  *   The operation output constructor
  */
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    /** Suspends an operation of the effect `E`.
      *
      * Returns the operation as a pending computation. The operation runs when a handler for `E` interprets it.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    ): O[A] < E =
        val in = operationInput
        val op: Kyo.Suspend[I, O, E, A, O[A], E] =
            new Kyo.Suspend[I, O, E, A, O[A], E]:
                def input               = in
                def tag                 = effectTag
                def frame               = _frame
                def cont                = Arrow[O[A]]
                private[kyo] def origin = this
        op
    end suspend

    /** Suspends an operation and maps its output in one step. */
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    )(
        inline f: O[A] => B < S
    ): B < (E & S) =
        suspend[A](using _frame)[I, O, E](effectTag, operationInput).map(f)

    /** The rotate step: the handler rotation law as a chain element.
      *
      * A suspension of a foreign effect crosses the handler outward and the handler rotates into the suspension's continuation, staying
      * wrapped around the rest of its computation: this step is that wrap. Running it applies the contained chain and folds the result
      * through the handle loop again, so the handler re-enters on every resumption, however the continuation is invoked. Each variant
      * closes over exactly the state its scope needs; the factories below build them.
      */
    abstract private[kyo] class Rotate(private[kyo] val inner: Arrow[Any, Any, Any]) extends Arrow.Transform[Any, Any, Any]

    private[kyo] object Rotate:

        /** Re-entry for a handle loop that changes no parameters (the ctl, final ctl, shallow, and stateful formats). */
        def plain(chain: Arrow[Any, Any, Any], loop: (Any < Any, Context, Handlers) => Any < Any, _frame: Frame): Rotate =
            new Rotate(chain):
                def frame = _frame
                def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(loop(chain(Kyo.lift(v), context, handlers), context, handlers), context, handlers)

        /** Re-entry for a fun-format handle loop: the handler registers around the contained application, so its operations are
          * answered where they surface.
          */
        def handler(
            chain: Arrow[Any, Any, Any],
            h: ResumeHandler[?, ?, ?, ?],
            loop: (Any < Any, Context, Handlers) => Any < Any,
            _frame: Frame
        ): Rotate =
            new Rotate(chain):
                def frame = _frame
                def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    val extended = handlers.add(new Handlers.Entry(h, context, handlers))
                    cont(loop(chain(Kyo.lift(v), context, extended), context, handlers), context, handlers)

        /** Re-entry for a binding: this scope's context is derived from the incoming one around the contained application. */
        def binding(
            chain: Arrow[Any, Any, Any],
            bind: Context => Context,
            loop: (Any < Any, Context, Handlers) => Any < Any,
            _frame: Frame
        ): Rotate =
            new Rotate(chain):
                def frame = _frame
                def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(loop(chain(Kyo.lift(v), bind(context), handlers), context, handlers), context, handlers)

        /** Re-entry for a guarded computation: a throw inside the contained chain lands in the rescue, which replaces the guarded
          * computation and is not re-guarded.
          */
        def guard(
            chain: Arrow[Any, Any, Any],
            rescue: Throwable => Any < Any,
            loop: (Any < Any, Context, Handlers) => Any < Any,
            _frame: Frame
        ): Rotate =
            new Rotate(chain):
                def frame = _frame
                def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    val w =
                        try chain(Kyo.lift(v), context, handlers)
                        catch
                            case ex if NonFatal(ex) =>
                                EffectTrace.attach(ex, "catching", _frame)
                                return cont(rescue(ex), context, handlers)
                    cont(loop(w, context, handlers), context, handlers)
                end run

        /** The answer bracket: the contained chain runs at the entry's parameters, the scope a handle function runs at, and keeps
          * doing so across resumptions.
          */
        def at(chain: Arrow[Any, Any, Any], entry: Handlers.Entry): Rotate =
            new Rotate(chain):
                def frame = entry.handler.frame
                def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(scoped(entry)(chain(Kyo.lift(v), entry.entryContext, entry.entryHandlers)), context, handlers)

    end Rotate

    /** The traversal every handle loop shares, after the operations of its own effect are handled: a foreign suspension rotates the
      * handler into its continuation,
      * a Defer and a Bracket carry the rotate step into theirs, and anything settled passes through for the loop's completion.
      */
    private[kernel2] def rewrap(
        v: Any < Any,
        rotated: Arrow[Any, Any, Any] => Arrow[Any, Any, Any],
        loop: (Any < Any, Context, Handlers) => Any < Any,
        context: Context,
        handlers: Handlers
    ): Any < Any =
        v match
            case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                s.continue(rotated(s.cont.asInstanceOf[Arrow[Any, Any, Any]]))
            case d: Kyo.Defer[?, ?, ?] =>
                new Kyo.Defer[Any, Any, Any](d.value.asInstanceOf[Any < Any], rotated(d.cont.asInstanceOf[Arrow[Any, Any, Any]]))
            case b: Kyo.Bracket[Any, Any, Any] @unchecked =>
                new Kyo.Bracket[Any, Any, Any]:
                    def acquire         = loop(b.acquire, context, handlers)
                    def release(r: Any) = loop(b.release(r), context, handlers).asInstanceOf[Unit < Any]
                    def cont            = rotated(b.cont.asInstanceOf[Arrow[Any, Any, Any]])
                    def frame           = b.frame
            case w => w
        end match
    end rewrap

    // handling is eager, so a throw while acting surfaces at the handle call itself: the
    // effect frames collected on the way out are installed here, the same decoration the
    // drive applies to throws it surfaces
    private def traced[A](frame: Frame)(body: => A): A =
        try body
        catch
            case ex: Throwable =>
                EffectTrace.attach(ex, "handle", frame)
                EffectTrace.install(ex)
                throw ex

    /** Answers a fun-format operation at the point it surfaced, consulting the threaded handlers parameter. Called from the one place
      * suspensions bubble ([[Arrow]]'s application on a pending computation); returns null when the suspension is not an operation of a
      * registered fun-format handler, and the caller takes the structural path.
      */
    private[kyo] def answerNow(kyo: Kyo[Any, Any], context: Context, handlers: Handlers): Any < Any =
        kyo match
            case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                val entry = handlers.resolve(s.erasedTag)
                if entry eq null then null.asInstanceOf[Any < Any]
                else
                    val w = entry.handler.answer(s.input)
                    val k = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                    if w.isInstanceOf[Kyo[?, ?]] then
                        // an effectful answer runs at the handler's scope, rotating across suspensions
                        k(scoped(entry)(w), context, handlers)
                    else
                        k(Kyo.lift(w), context, handlers)
                    end if
                end if
            case _ =>
                null.asInstanceOf[Any < Any]
    end answerNow

    /** Keeps an effectful fun-format answer executing at its handler's scope: the parameters captured at installation, not the
      * operation site's. Structure the answer already materialized bubbles outward untouched; remainders are wrapped in an `at` rotate
      * step, so parts that run after a resumption still execute at the handler's scope.
      */
    private def scoped(entry: Handlers.Entry)(w: Any < Any): Any < Any =
        rewrap(w, Rotate.at(_, entry), (v, _, _) => scoped(entry)(v), Context.empty, Handlers.empty)

    /** Handles `E` with the continuation as a function (the ctl format).
      *
      * The handle function receives each operation's input and the continuation from the operation to this handler. It decides
      * everything: invoke the continuation once to resume, not at all to abort, or several times. The handler is deep: effects of `E`
      * in the result, including through resumed continuations, dispatch back to this handler.
      */
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        def loop(v: Any < Any, context: Context, handlers: Handlers): Any < Any =
            v match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if effectTag.erased <:< s.erasedTag =>
                    val k      = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                    val resume = (x: Any) => k(Kyo.lift(x), context, handlers).asInstanceOf[A < (E & S & S2)]
                    // the recursive call stays in the arm as a direct self-call: that is what
                    // keeps deep eager handling stack safe
                    loop(
                        handle[Any](s.input.asInstanceOf[I[Any]], resume.asInstanceOf[O[Any] => A < (E & S & S2)])
                            .asInstanceOf[Any < Any],
                        context,
                        handlers
                    )
                case v =>
                    rewrap(v, Rotate.plain(_, loop, frame), loop, context, handlers)
            end match
        end loop
        traced(frame)(loop(v.asInstanceOf[Any < Any], Context.empty, Handlers.empty)).asInstanceOf[A < (S & S2)]
    end handle

    /** Handles `E` by answering each operation in place (the fun format).
      *
      * The handle function produces the operation's output; the kernel resumes the continuation exactly once with it. No continuation
      * is exposed or captured: the handler registers in the threaded handlers parameter and its operations are answered locally at the
      * point they surface. The handler is deep, and the handle function runs at the handler's scope: reads inside it resolve against
      * the bindings outside the handler.
      */
    def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => I[C] => O[C] < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        val h = new ResumeHandler[I, O, E, S & S2](effectTag, handle, frame)
        def loop(v: Any < Any, context: Context, handlers: Handlers): Any < Any =
            v match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if effectTag.erased <:< s.erasedTag =>
                    loop(s.cont.asInstanceOf[Arrow[Any, Any, Any]](h.answer(s.input), context, handlers), context, handlers)
                case v =>
                    rewrap(v, Rotate.handler(_, h, loop, frame), loop, context, handlers)
            end match
        end loop
        traced(frame)(loop(v.asInstanceOf[Any < Any], Context.empty, Handlers.empty)).asInstanceOf[A < (S & S2)]
    end handleResume

    /** Handles `E` by ending the computation at each operation (the final ctl format).
      *
      * The handle function produces the result directly; the continuation from the operation to this handler never runs. The handler
      * is deep: effects of `E` in the result dispatch back to this handler.
      */
    def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => I[C] => A < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        def loop(v: Any < Any, context: Context, handlers: Handlers): Any < Any =
            v match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if effectTag.erased <:< s.erasedTag =>
                    // the continuation to this handler never runs: the operation input is all the handle function needs
                    loop(handle[Any](s.input.asInstanceOf[I[Any]]).asInstanceOf[Any < Any], context, handlers)
                case v =>
                    rewrap(v, Rotate.plain(_, loop, frame), loop, context, handlers)
            end match
        end loop
        traced(frame)(loop(v.asInstanceOf[Any < Any], Context.empty, Handlers.empty)).asInstanceOf[A < (S & S2)]
    end handleStop

    /** Handles only the first operation of `E`, shallowly.
      *
      * The handle function receives the first operation's input and continuation and produces the final result; the handler is not
      * reinstalled, so later operations of `E` (including through the invoked continuation) are not handled by it. `done` transforms
      * the value when no operation occurs before completion.
      */
    def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
        done: A => B < S2
    )(using frame: Frame): B < (S & S2) =
        def loop(v: Any < Any, context: Context, handlers: Handlers): Any < Any =
            v match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if effectTag.erased <:< s.erasedTag =>
                    val k      = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                    val resume = (x: Any) => k(Kyo.lift(x), context, handlers).asInstanceOf[A < (E & S)]
                    // shallow: the handler leaves, so the result is not looped
                    handle[Any](s.input.asInstanceOf[I[Any]], resume.asInstanceOf[O[Any] => A < (E & S)]).asInstanceOf[Any < Any]
                case v if v.isInstanceOf[Kyo[?, ?]] =>
                    rewrap(v, Rotate.plain(_, loop, frame), loop, context, handlers)
                case v =>
                    done(Kyo.unnest(v).asInstanceOf[A]).asInstanceOf[Any < Any]
            end match
        end loop
        traced(frame)(loop(v.asInstanceOf[Any < Any], Context.empty, Handlers.empty)).asInstanceOf[B < (S & S2)]
    end handleFirst

    /** Handles `E` without handler state.
      *
      * The handle function decides per operation: `Loop.continue(next)` keeps handling `next` (typically the resumed continuation),
      * `Loop.done(result)` leaves, discarding the continuation. Effects raised while the outcome itself is computed dispatch to outer
      * handlers.
      */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2
    )(using frame: Frame): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, Unit](effectTag, (), v)(
            [C] =>
                (input, _, cont) =>
                    handle(input, cont).map {
                        case next: Loop.Continue[A < (E & S)] @unchecked => Loop.continue((), next._1)
                        case res                                         => res.asInstanceOf[Loop.Outcome2[Unit, A < (E & S), A]]
                },
            (_, a) => a
        )

    /** Handles `E` with handler state threaded through the operations, the value passing through unchanged. */
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), A] < S2
    )(using frame: Frame): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, State](effectTag, state, v)(handle, (_, a) => a)

    /** Handles `E` with handler state and custom completion.
      *
      * The handle function receives the operation's input, the current state, and the continuation, and decides:
      * `Loop.continue(nextState, next)` keeps handling `next` (typically the resumed continuation) with the new state,
      * `Loop.done(result)` leaves with a final result, discarding the continuation. `done` produces the result when the computation
      * completes normally, from the final state and value. The handler is deep for computations passed through Continue; effects
      * raised while the outcome itself is computed dispatch to outer handlers.
      */
    @nowarn("msg=anonymous")
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), B] < S2,
        done: (State, A) => B < (S & S2)
    )(using frame: Frame): B < (S & S2) =
        def loop(state: State, v: Any < Any, context: Context, handlers: Handlers): Any < Any =
            val stateLoop: (Any < Any, Context, Handlers) => Any < Any = (w, ctx, hs) => loop(state, w, ctx, hs)
            // interprets the outcome once it materializes; effects raised while it is computed pass through to outer handlers
            def outcome(w: Any < Any, context: Context, handlers: Handlers): Any < Any =
                w match
                    case kyo: Kyo[?, ?] =>
                        kyo.asInstanceOf[Kyo[Any, Any]].map(
                            new Arrow.Transform[Any, Any, Any]:
                                def frame = handleLoopFrame
                                def run[C, S3](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S3]): C < (Any & S3) =
                                    cont(outcome(Kyo.lift(v), context, handlers), context, handlers)
                        )
                    case out =>
                        Kyo.unnest(out) match
                            case next: Loop.Continue2[?, ?] @unchecked =>
                                loop(next._1.asInstanceOf[State], next._2.asInstanceOf[Any < Any], context, handlers)
                            case b =>
                                Kyo.lift(b)
            end outcome
            v match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if effectTag.erased <:< s.erasedTag =>
                    val k      = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                    val resume = (x: Any) => k(Kyo.lift(x), context, handlers).asInstanceOf[A < (E & S)]
                    outcome(
                        handle[Any](s.input.asInstanceOf[I[Any]], state, resume.asInstanceOf[O[Any] => A < (E & S)])
                            .asInstanceOf[Any < Any],
                        context,
                        handlers
                    )
                case v if v.isInstanceOf[Kyo[?, ?]] =>
                    rewrap(v, Rotate.plain(_, stateLoop, frame), stateLoop, context, handlers)
                case v =>
                    done(state, Kyo.unnest(v).asInstanceOf[A]).asInstanceOf[Any < Any]
            end match
        end loop
        traced(frame)(loop(state, v.asInstanceOf[Any < Any], Context.empty, Handlers.empty)).asInstanceOf[B < (S & S2)]
    end handleLoop

    // handleLoop's outcome transform is minted per interpretation; one internal frame identifies them all
    private val handleLoopFrame = Frame.internal

    /** Inspects the head of a computation without running it: if the first pending suspension is an operation of `E`, hands its input to
      * `f`. Used by the runtime to walk interrupted remainders without draining any user code.
      */
    private[kyo] def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        f: [C] => I[C] => Unit
    ): Unit =
        v match
            case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                if effectTag.erased <:< s.erasedTag then f(s.input.asInstanceOf[I[Any]])
            case _ => ()
        end match
    end dispatchFirst

    /** Handles `E` and intercepts non-fatal exceptions in one step.
      *
      * `recover` receives exceptions thrown at construction, in the handle function, or in any later step of the computation, including
      * after parks. `done` maps the completion value. The current kernel's `accept` input filter is not carried yet; it lands with the
      * effect that needs it.
      */
    private[kyo] def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, S3](
        effectTag: Tag[E],
        v: => A < (E & S)
    )(
        handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2),
        done: A => B < S3 = (v: A) => v,
        recover: Throwable => B < (S & S2 & S3)
    )(using frame: Frame): B < (S & S2 & S3) =
        Effect.catching(ArrowEffect.handle[I, O, E, A, S, S2](effectTag, v)(handle).map(done))(recover)

    /** Drives the computation, interpreting unmatched operations of `E` outside it (the runtime boundary).
      *
      * Unlike the installing handle APIs, this is a loop around the plain drive: the drive runs until it completes or parks, and when
      * the parked suspension is an operation of `E` that no installed handler matched, the handle function receives the operation input
      * and the full continuation (rotate steps included) and decides. `Present(next)` keeps driving this slice with `next`, deep across
      * operations; `Absent` returns the remainder with the suspension still pending, and re-entering with the same function resumes it
      * (the continuation received is fresh at every re-entry). This is the scheduler integration point: a task drives one slice per
      * call, parks by storing the returned remainder, and re-enters it on the next slice. Preemption polls inside the drive on its
      * usual cadence.
      */
    private[kyo] def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S),
        context: Context
    )(
        handle: [C] => (I[C], Arrow[O[C], A, E & S]) => Maybe[A < (E & S)]
    )(using frame: Frame): A < (E & S) =
        // this loop is the slice's Preemptible boundary: the inner drives cascade parks without consuming, and the request is
        // consumed exactly once here, when the slice decides to return the remainder. The context comes from the caller, the
        // current kernel's signature: a fiber passes its inherited context per slice
        @tailrec def slice(cur: A < (E & S)): A < (E & S) =
            inline def continue(next: A < (E & S)): A < (E & S) =
                if Safepoint.pollPreempt() then
                    val _ = Safepoint.clearPreempt()
                    next
                else slice(next)
            val r = `<`.evalLoop(cur.asInstanceOf[Any < Any], `<`.EvalCascade, context, Handlers.empty).asInstanceOf[A < (E & S)]
            r match
                case s: Kyo.Suspend[?, ?, ?, c, ?, ?] @unchecked if effectTag.erased <:< s.erasedTag =>
                    // the tag match justifies reading the operation at this handler's types; the
                    // suspension's own fused continuation is its continuation to this boundary
                    handle[c](
                        s.input.asInstanceOf[I[c]],
                        s.cont.asInstanceOf[Arrow[Any, Any, Any]].optimize.asInstanceOf[Arrow[O[c], A, E & S]]
                    ) match
                        case Maybe.Present(next) => continue(next)
                        case Maybe.Absent        => r
                case _ =>
                    if Safepoint.pollPreempt() then
                        val _ = Safepoint.clearPreempt()
                    r
            end match
        end slice
        slice(v)
    end handlePartial

end ArrowEffect
