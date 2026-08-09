package kyo.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.kernel.internal.Context
import kyo.kernel.internal.EffectTrace
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Handlers
import kyo.kernel.internal.Kyo
import kyo.kernel.internal.LiftMacro.defaultLift
import kyo.kernel.internal.ResumeHandler
import kyo.kernel.internal.Safepoint
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
  * (a [[ArrowEffect.Rotate]] step), staying wrapped around the rest of its computation. Every handler additionally registers in the
  * threaded [[Handlers]] parameter, so execution at a suspension point knows the operation's handler: a resume entry answers in place
  * with no continuation captured, a stop entry makes the suspension pass through bare, with nothing stacked on the way to its loop,
  * and a shadow entry masks outer entries for the formats whose operations must travel structurally.
  *
  * Effects that never resume declare it in their output type: an operation of an effect with output `Const[Nothing]` cannot be
  * resumed by any handler, so its suspension travels untouched by construction, regardless of registration.
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
      * Returns the operation as a pending computation. The operation runs when a handler for `E` interprets it. An operation whose
      * output type is `Nothing` is minted never-resuming, so nothing is ever stacked onto it.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    ): O[A] < E =
        val in = operationInput
        inline scala.compiletime.erasedValue[O[A]] match
            case _: Nothing =>
                val op: Kyo.Suspend[I, O, E, A, O[A], E] =
                    new Kyo.NeverResumed[I, O, E, A, O[A], E]:
                        def input               = in
                        def tag                 = effectTag
                        def frame               = _frame
                        def cont                = Arrow[O[A]]
                        private[kyo] def origin = this
                op
            case _ =>
                val op: Kyo.Suspend[I, O, E, A, O[A], E] =
                    new Kyo.Suspend[I, O, E, A, O[A], E]:
                        def input               = in
                        def tag                 = effectTag
                        def frame               = _frame
                        def cont                = Arrow[O[A]]
                        private[kyo] def origin = this
                op
        end match
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
      * closes over exactly the state its scope needs and re-establishes its format's registration around the contained application;
      * the factories below build them.
      *
      * `A` is the contained chain's input (the foreign operation's output type at the wrap site) and `B` the handle loop's result.
      * The `inner` chain is kept at its element types for the finalization walk, which is structural.
      */
    abstract private[kyo] class Rotate[A, B, S](private[kyo] val inner: Arrow[A, ?, ?]) extends Arrow.Transform[A, B, S]

    private[kyo] object Rotate:

        /** Re-entry for a handle loop that registers nothing. */
        def plain[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            loop: (M < S, Context, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    cont(loop(chain(defaultLift(v), context, handlers), context, handlers), context, handlers)

        /** Re-entry for a fun-format handle loop: the handler registers around the contained application, so its operations are
          * answered where they surface, and the loop receives the extension so the matched arm's resumptions run under it too.
          */
        def handler[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            h: ResumeHandler[?, ?, ?, ?],
            loop: (M < S, Context, Handlers, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    val extended = handlers.add(new Handlers.Entry.Resume(h, context, handlers))
                    cont(loop(chain(defaultLift(v), context, extended), context, handlers, extended), context, handlers)

        /** Re-entry for a stop-format handle loop: the shared per-call entry registers around the contained application, so an
          * operation of the handled effect passes through bare to the loop, with nothing stacked on the way.
          */
        def stop[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            entry: Handlers.Entry.Stop,
            loop: (M < S, Context, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    cont(loop(chain(defaultLift(v), context, handlers.add(entry)), context, handlers), context, handlers)

        /** Re-entry for a format whose operations travel structurally (ctl, first, loop): when an outer entry of the same tag is
          * visible, a shadow entry masks it for the contained application, so innermost-wins holds across formats. The entry is
          * minted only when there is an outer entry to mask, so the common un-nested case allocates nothing. The loop receives the
          * masked parameter for the resumptions its region includes.
          */
        def masked[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            tag: Tag[Any],
            loop: (M < S, Context, Handlers, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    val masked = if handlers.resolve(tag).isEmpty then handlers else handlers.add(new Handlers.Entry.Shadow(tag))
                    cont(loop(chain(defaultLift(v), context, masked), context, handlers, masked), context, handlers)

        /** Re-entry for a binding: this scope's context is derived from the incoming one around the contained application. */
        def binding[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            bind: Context => Context,
            loop: (M < S, Context, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    cont(loop(chain(defaultLift(v), bind(context), handlers), context, handlers), context, handlers)

        /** Re-entry for a guarded computation: a throw inside the contained chain lands in the rescue, which replaces the guarded
          * computation and is not re-guarded.
          */
        def guard[X, M, S, A, S2](
            chain: Arrow[X, M, S],
            rescue: Throwable => A < S2,
            loop: (M < S, Context, Handlers) => A < S2,
            _frame: Frame
        ): Rotate[X, A, S2] =
            new Rotate[X, A, S2](chain):
                def frame = _frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
                    val w =
                        try chain(defaultLift(v), context, handlers)
                        catch
                            case ex if NonFatal(ex) =>
                                EffectTrace.attach(ex, "catching", _frame)
                                return cont(rescue(ex), context, handlers)
                    cont(loop(w, context, handlers), context, handlers)
                end run

        /** The answer bracket: the contained chain runs at the entry's parameters, the scope a handle function runs at, and keeps
          * doing so across resumptions.
          */
        def at[X, M, S, S2](chain: Arrow[X, M, S], entry: Handlers.Entry.Resume): Rotate[X, M, S2] =
            new Rotate[X, M, S2](chain):
                def frame = entry.handler.frame
                def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[M, C, S3]): C < (S2 & S3) =
                    cont(scoped[M, S, S2](entry)(chain(defaultLift(v), entry.entryContext, entry.entryHandlers)), context, handlers)

    end Rotate

    /** The traversal every handle loop shares once its own effect's operations are handled and the value is still pending: a foreign
      * suspension rotates the handler into its continuation, a Defer and a Bracket carry the rotate step into theirs. A suspension
      * that can never resume, or whose tag resolves to a stop entry in the loop's ambient, passes through untouched: its rotation
      * would be discarded unrun by the stop loop's matched arm. Settled values never reach here; each loop's own settled arm
      * completes them.
      */
    private[kernel] def rotate[A, B, S, S2](
        v: Kyo[A, S],
        rotated: [X] => Arrow[X, A, S] => Arrow[X, B, S2],
        loop: (A < S, Context, Handlers) => B < S2,
        context: Context,
        handlers: Handlers
    ): B < S2 =
        v match
            case s: Kyo.Suspend[?, ?, ?, ?, A, S] @unchecked =>
                if s.origin.isInstanceOf[Kyo.NeverResumed[?, ?, ?, ?, ?, ?]] then
                    v.asInstanceOf[B < S2]
                else if handlers.isEmpty then
                    s.continue(rotated(s.cont))
                else
                    handlers.resolve(s.erasedTag) match
                        case Maybe.Present(_: Handlers.Entry.Stop) => v.asInstanceOf[B < S2]
                        case _                                     => s.continue(rotated(s.cont))
            case d: Kyo.Defer[a, A, S] @unchecked =>
                // the deferred value's residual row after this handler is the loop's output row: the fold's premise, not provable
                Kyo.Defer(d.value.asInstanceOf[a < S2], rotated(d.cont))
            case b: Kyo.Bracket[r, A, S] @unchecked =>
                rotateBracket(b, rotated, loop, loop, context, handlers)
            case seq: Kyo.Sequenced[r, a, A, S] @unchecked =>
                // the handler rotates into the region and through its downstream: chains the
                // rotation wraps extend with `after` first, and the value fold re-sequences
                // `after` onto rebuilt region values structurally. The release fold keeps the
                // raw loop: its completion value is discarded and never meets `after`
                rotateBracket[r, a, B, S, S2](
                    seq.bracket,
                    [X] => (chain: Arrow[X, a, S]) => rotated(chain.map(seq.after)),
                    (w, c, hs) => loop(seq.after(w, c, hs), c, hs),
                    (w, c, hs) => loop(w.asInstanceOf[A < S], c, hs),
                    context,
                    handlers
                )
        end match
    end rotate

    /** The bracket arm of [[rotate]], out of line: brackets are rare at the traversal's call sites and the rebuild machinery is
      * large enough to push the traversal past the JIT's inlining threshold, costing its callers the escape analysis of their
      * per-rotation closures. The release fold takes its own loop: release runs at its own currency and its completion value is
      * discarded by the drive, so a caller sequencing a downstream through the value loop must not route release through it.
      */
    private def rotateBracket[R, A, B, S, S2](
        b: Kyo.Bracket[R, A, S],
        rotated: [X] => Arrow[X, A, S] => Arrow[X, B, S2],
        loop: (A < S, Context, Handlers) => B < S2,
        releaseLoop: (A < S, Context, Handlers) => B < S2,
        context: Context,
        handlers: Handlers
    ): B < S2 =
        if b.settled then
            new Kyo.Bracket[R, B, S2]:
                // acquire is settled, so only release and the use continuation carry the handler. The release fold runs
                // at the bracket's value types: its completion value is discarded by the drive, so the loop's currency
                // standing in for it stays contained; the casts mark that seam.
                def acquire = b.acquire.asInstanceOf[R < S2]
                def release(x: R, outcome: Maybe[Result.Error[Any]]) =
                    releaseLoop(b.release(x, outcome).asInstanceOf[A < S], context, handlers).asInstanceOf[Unit < S2]
                def cont                                 = rotated(b.cont)
                def frame                                = b.frame
                override private[kyo] def settled        = true
                override private[kyo] def crossingBuried = true
        else
            // acquire is sequenced before the bracket: fold it as the head of the computation and rebuild the bracket
            // around the settled resource. The continuation an operation inside acquire presents therefore spans the
            // rest of acquire, the bracket, and its use: a handler that ends the computation there discards the bracket
            // (nothing acquired, release never runs), and a transform after resume applies to the bracket's result, the
            // old kernel's semantics for effects inside acquisition. The fold is deferred to the drive so the acquire
            // thunk keeps running per drive, exactly once each; the rebuilt bracket re-enters this traversal marked
            // settled.
            val rebuild = new Arrow.Transform[R, A, S]:
                def frame = b.frame
                def run[C, S3](v: R, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S & S3) =
                    val rebuilt = new Kyo.Bracket[R, A, S]:
                        def acquire                                          = defaultLift(v)
                        def release(x: R, outcome: Maybe[Result.Error[Any]]) = b.release(x, outcome)
                        def cont                                             = b.cont
                        def frame                                            = b.frame
                        override private[kyo] def settled                    = true
                    cont(rebuilt, context, handlers)
                end run
            Kyo.Defer(
                (),
                new Arrow.Transform[Unit, B, S2]:
                    def frame = b.frame
                    def run[C, S3](u: Unit, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                        cont(loop(rebuild(b.acquire, context, handlers), context, handlers), context, handlers)
            )
        end if
    end rotateBracket

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

    /** Answers a fun-format operation at the point it surfaced, at the drive's currency, given the entry its tag resolved to. The
      * caller applies the suspension's continuation result forward; an effectful answer runs at the handler's scope.
      */
    private[kyo] def answerNow(
        s: Kyo.Suspend[?, ?, ?, ?, ?, ?],
        entry: Handlers.Entry.Resume,
        context: Context,
        handlers: Handlers
    ): Any < Any =
        val w = entry.handler.answer(s.input)
        val k = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
        if w.isInstanceOf[Kyo[?, ?]] then
            // an effectful answer runs at the handler's scope, rotating across suspensions
            k(scoped(entry)(w), context, handlers)
        else
            k(defaultLift(w), context, handlers)
        end if
    end answerNow

    /** Keeps an effectful fun-format answer executing at its handler's scope: the parameters captured at installation, not the
      * operation site's. Structure the answer already materialized bubbles outward untouched; remainders are wrapped in an `at` rotate
      * step, so parts that run after a resumption still execute at the handler's scope.
      */
    private def scoped[A, S, S2](entry: Handlers.Entry.Resume)(w: A < S): A < S2 =
        w match
            case k: Kyo[A, S] @unchecked =>
                rotate(
                    k,
                    [X] => (chain: Arrow[X, A, S]) => Rotate.at[X, A, S, S2](chain, entry),
                    (v, _, _) => scoped[A, S, S2](entry)(v),
                    Context.empty,
                    Handlers.empty
                )
            case v =>
                v.asInstanceOf[A < S2]
    end scoped

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
        def loop(v: A < (E & S & S2), context: Context, handlers: Handlers, masked: Handlers): A < (S & S2) =
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                    val k      = s.cont
                    val resume = (o: O[x]) => k(defaultLift(o), context, masked)
                    // the recursive call stays in the arm as a direct self-call: that is what
                    // keeps deep eager handling stack safe
                    loop(handle[x](s.input, resume), context, handlers, masked)
                case k: Kyo[A, E & S & S2] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.masked(chain, effectTag.erased, loop, frame),
                        (w, c, hs) =>
                            loop(
                                w,
                                c,
                                hs,
                                if hs.resolve(effectTag.erased).isEmpty then hs else hs.add(new Handlers.Entry.Shadow(effectTag.erased))
                            ),
                        context,
                        handlers
                    )
                case v =>
                    // settled: the handled effect is discharged, the value untouched
                    v.asInstanceOf[A < (S & S2)]
            end match
        end loop
        traced(frame)(loop(v, Context.empty, Handlers.empty, Handlers.empty))
    end handle

    /** Handles `E` by answering each operation in place (the fun format).
      *
      * The handle function produces the operation's output; the kernel resumes the continuation exactly once with it. No continuation
      * is exposed or captured: the handler registers in the threaded handlers parameter, so its operations are answered locally at the
      * point they surface, including during the loop's own resumptions. The handler is deep, and the handle function runs at the
      * handler's scope: reads inside it resolve against the bindings outside the handler.
      */
    def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => I[C] => O[C] < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        val h = new ResumeHandler[I, O, E, S & S2](effectTag, handle, frame)
        // extended is the loop invocation's registration: the ambient plus the entry capturing this invocation's scope pair. The
        // matched arm resumes under it, so operations of E surfacing during the resumption are answered where they surface instead
        // of stacking nodes back to the loop, and an outer same-tag entry cannot capture them.
        def loop(v: A < (E & S & S2), context: Context, handlers: Handlers, extended: Handlers): A < (S & S2) =
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                    loop(s.cont(h.handle[x](s.input), context, extended), context, handlers, extended)
                case k: Kyo[A, E & S & S2] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.handler(chain, h, loop, frame),
                        (w, c, hs) => loop(w, c, hs, hs.add(new Handlers.Entry.Resume(h, c, hs))),
                        context,
                        handlers
                    )
                case v =>
                    v.asInstanceOf[A < (S & S2)]
            end match
        end loop
        traced(frame)(
            loop(v, Context.empty, Handlers.empty, Handlers.empty.add(new Handlers.Entry.Resume(h, Context.empty, Handlers.empty)))
        )
    end handleResume

    /** Handles `E` by ending the computation at each operation (the final ctl format).
      *
      * The handle function produces the result directly; the continuation from the operation to this handler never runs, and with
      * the handler registered it is never built either: an operation surfacing anywhere in the region passes through bare to this
      * loop. The handler is deep: effects of `E` in the result dispatch back to this handler.
      */
    def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        handle: [C] => I[C] => A < (E & S & S2)
    )(using frame: Frame): A < (S & S2) =
        val entry = new Handlers.Entry.Stop(effectTag.erased)
        def loop(v: A < (E & S & S2), context: Context, handlers: Handlers): A < (S & S2) =
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                    // the continuation to this handler never runs: the operation input is all the handle function needs
                    loop(handle[x](s.input), context, handlers)
                case k: Kyo[A, E & S & S2] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.stop(chain, entry, loop, frame),
                        loop,
                        context,
                        handlers
                    )
                case v =>
                    v.asInstanceOf[A < (S & S2)]
            end match
        end loop
        traced(frame)(loop(v, Context.empty, Handlers.empty))
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
        def loop(v: A < (E & S), context: Context, handlers: Handlers, masked: Handlers): B < (S & S2) =
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S] @unchecked if effectTag.erased <:< s.erasedTag =>
                    val k = s.cont
                    // shallow: the resume closure deliberately uses the plain ambient, not the masked one. Once the handler
                    // leaves, the resumed region's operations of E belong to outer handlers, as the result type says.
                    val resume = (o: O[x]) => k(defaultLift(o), context, handlers)
                    handle[x](s.input, resume)
                case k: Kyo[A, E & S] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S]) => Rotate.masked(chain, effectTag.erased, loop, frame),
                        (w, c, hs) =>
                            loop(
                                w,
                                c,
                                hs,
                                if hs.resolve(effectTag.erased).isEmpty then hs else hs.add(new Handlers.Entry.Shadow(effectTag.erased))
                            ),
                        context,
                        handlers
                    )
                case v =>
                    done(Kyo.settled(v))
            end match
        end loop
        traced(frame)(loop(v, Context.empty, Handlers.empty, Handlers.empty))
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
        handleLoop(effectTag, (), v)(
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
        def loop(state: State, v: A < (E & S), context: Context, handlers: Handlers, masked: Handlers): B < (S & S2) =
            val stateLoop: (A < (E & S), Context, Handlers, Handlers) => B < (S & S2) =
                (w, ctx, hs, m) => loop(state, w, ctx, hs, m)
            // interprets a pending outcome once it materializes; effects raised while it is computed pass through to outer
            // handlers, unshadowed by design. Settled outcomes are interpreted inline in the matched arm so the continue cycle
            // stays a direct self-tail call, the same mechanism that keeps the plain ctl loop stack safe: the mutual hop through
            // this transform exists only for pending outcomes, which return through the drive and cannot stack.
            def pending(kyo: Kyo[Loop.Outcome2[State, A < (E & S), B], S & S2]): B < (S & S2) =
                kyo.map(
                    new Arrow.Transform[Loop.Outcome2[State, A < (E & S), B], B, S & S2]:
                        def frame = handleLoopFrame
                        def run[C, S3](
                            v: Loop.Outcome2[State, A < (E & S), B],
                            context: Context,
                            handlers: Handlers,
                            cont: Arrow[B, C, S3]
                        ): C < (S & S2 & S3) =
                            val next =
                                Kyo.unnest(v) match
                                    case next: Loop.Continue2[State, A < (E & S)] @unchecked =>
                                        loop(next._1, next._2, context, handlers, masked)
                                    case b =>
                                        defaultLift(b.asInstanceOf[B])
                            cont(next, context, handlers)
                        end run
                )
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S] @unchecked if effectTag.erased <:< s.erasedTag =>
                    val k      = s.cont
                    val resume = (o: O[x]) => k(defaultLift(o), context, masked)
                    val w      = handle[x](s.input, state, resume)
                    if w.isInstanceOf[Kyo[?, ?]] then
                        pending(w.asInstanceOf[Kyo[Loop.Outcome2[State, A < (E & S), B], S & S2]])
                    else
                        Kyo.unnest(w) match
                            case next: Loop.Continue2[State, A < (E & S)] @unchecked =>
                                loop(next._1, next._2, context, handlers, masked)
                            case b =>
                                // the outcome union's completion side: a raw B at the opaque boundary
                                defaultLift(b.asInstanceOf[B])
                        end match
                    end if
                case k: Kyo[A, E & S] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S]) => Rotate.masked(chain, effectTag.erased, stateLoop, frame),
                        (w, c, hs) =>
                            loop(
                                state,
                                w,
                                c,
                                hs,
                                if hs.resolve(effectTag.erased).isEmpty then hs else hs.add(new Handlers.Entry.Shadow(effectTag.erased))
                            ),
                        context,
                        handlers
                    )
                case v =>
                    done(state, Kyo.settled(v))
            end match
        end loop
        traced(frame)(loop(state, v, Context.empty, Handlers.empty, Handlers.empty))
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
            case s: Kyo.Suspend[I, O, E, x, A, E & S] @unchecked if effectTag.erased <:< s.erasedTag =>
                f(s.input)
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
        accept: [C] => I[C] => Boolean = [C] => (_: I[C]) => true,
        recover: Throwable => B < (S & S2 & S3)
    )(using frame: Frame): B < (S & S2 & S3) =
        // the ctl loop with the accept input filter on the matched arm: an operation of E the
        // filter rejects is not handled here, so it crosses this handler structurally through
        // the rotation, exactly like a foreign effect, and outer handlers see it
        def loop(v: A < (E & S & S2), context: Context, handlers: Handlers, masked: Handlers): A < (S & S2) =
            v match
                case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag && accept(s.input) =>
                    val k      = s.cont
                    val resume = (o: O[x]) => k(defaultLift(o), context, masked)
                    loop(handle[x](s.input, resume), context, handlers, masked)
                case k: Kyo[A, E & S & S2] @unchecked =>
                    rotate(
                        k,
                        [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.masked(chain, effectTag.erased, loop, frame),
                        (w, c, hs) =>
                            loop(
                                w,
                                c,
                                hs,
                                if hs.resolve(effectTag.erased).isEmpty then hs else hs.add(new Handlers.Entry.Shadow(effectTag.erased))
                            ),
                        context,
                        handlers
                    )
                case v =>
                    v.asInstanceOf[A < (S & S2)]
            end match
        end loop
        Effect.catching(loop(v, Context.empty, Handlers.empty, Handlers.empty).map(done))(recover)
    end handleCatching

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
            val r = Eval.evalLoop(cur.asInstanceOf[Any < Any], Eval.Cascade, context, Handlers.empty).asInstanceOf[A < (E & S)]
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
