package kyo.kernel.internal

import kyo.Arrow
import kyo.Loop.Continue
import kyo.Loop.Continue2
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Tag
import kyo.kernel.*

sealed abstract private[kyo] class Handler[E <: ArrowEffect[?, ?], A, B, -S] extends Arrow.Region[A, B, S]:
    def tag: Tag[E]
    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        v match
            case kyo: Kyo[A, S2] @unchecked =>
                Effect.defer(kyo, this, next)
            case _ =>
                val slot = Safepoint.get()
                if !Safepoint.enter(slot) then
                    Effect.defer(v, this, next)
                else
                    val out = next.head(apply(Nested.unnest(v)), next.tail)
                    Safepoint.exit(slot)
                    out
                end if
end Handler

// Public object, private[kyo] members: see the note on Safepoint for the accessor the other shape emits.
object Handler:

    /** How a stateful answer crossed back, carried without an allocation.
      *
      * A clause's outcome bundles the new state with the answer, and returning that bundle through a
      * virtual call is a heap allocation per answer that escape analysis cannot remove once more than one
      * handler class exists (the morphism probe measured the loss at exactly one object per answer). The
      * cell splits the bundle: the answer travels as the return value, the state and the branch taken
      * travel here. One cell per stack, reused; only the eval and the generated answer method ever touch
      * it, and the user's clause receives only its declared arguments, so nothing user-written can reach
      * it. Everything it holds is on its way into the state slot or the eval's own dispatch, so it is
      * never the only copy of anything.
      */
    final private[kyo] class Out:
        private[kyo] var kind: Int  = 0
        private[kyo] var state: Any = null
        // stage 2 lanes: the continuation a bailing answer loop hands back unconsumed, and the input the
        // decompose hook reports for the next iteration. Both hold references already in hand, never a
        // fresh allocation, and neither survives past the dispatch that wrote it
        private[kyo] var cont: Arrow[Any, Any, Any] = null
        private[kyo] var input: Any                 = null
    end Out

    // the erased shapes the two hooks below build and match; the node classes are not nameable at the
    // expansion sites that need them, so the matching lives here, kernel side, behind two small calls
    private type AnyK[X] = Any

    /** Splits `v` into a same-tag suspension's input and effective continuation, where it has that shape.
      *
      * Returns 1 with `out.input` and `out.cont` set when it does; 0 when `v` is not that shape and was
      * not touched; 2 when `v` was a deferral whose payload had to be read to decide and did not match:
      * the payload is a by-name that must not run twice, so `out.input` then carries an equivalent
      * deferral rebuilt over the value read, and the caller continues with that instead of `v`.
      */
    private[kyo] def nextAnswer(t: Tag[Any], v: Any, out: Out): Int =
        v match
            case kyo: Kyo.Suspend[AnyK, AnyK, Nothing, Any, Any, Any] @unchecked if kyo.tag.erased =:= t =>
                out.input = kyo.input
                out.cont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                1
            case d: Kyo.Defer[Any, Any, Any, Any] @unchecked =>
                val v0 = d.value
                v0 match
                    case kyo: Kyo.Suspend[AnyK, AnyK, Nothing, Any, Any, Any] @unchecked
                        if kyo.tag.erased =:= t && (d.contB eq Arrow.Id) =>
                        val sc = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                        val ca = d.contA.asInstanceOf[Arrow[Any, Any, Any]]
                        if sc eq Arrow.Id then
                            out.input = kyo.input
                            out.cont = ca
                            1
                        else if ca eq Arrow.Id then
                            out.input = kyo.input
                            out.cont = sc
                            1
                        else
                            // composing the two would allocate per answer; hand back what was read instead
                            out.input = Effect.defer(v0, d.contA, d.contB)
                            2
                        end if
                    case _ =>
                        out.input = Effect.defer(v0, d.contA, d.contB)
                        2
                end match
            case _ => 0

    /** Rebuilds the suspension a bailing answer loop still owes: the input under the tag, with the
      * continuation attached, exactly what the loop consumed to get where it is.
      */
    private[kyo] def resuspend(t: Tag[Any], in: Any, k: Arrow[Any, Any, Any]): Any =
        new Kyo.Suspend[AnyK, AnyK, Nothing, Any, Any, Any]:
            def frame = kyo.Frame.internal
            def tag   = t.asInstanceOf[Tag[Nothing]]
            def input = in
            def cont  = k

    /** The per-answer templates the region combinators expand.
      *
      * The text lives here because the protocol is the eval's: what the cell means, when state commits,
      * how a bail re-enters the eval. The combinator expansions wire their clause into these and stay
      * wiring. Each call site still emits its own copy with the clause statically bound, which is the
      * mechanism the design exists for; only the source location is shared. The loops are flag-driven
      * rather than early-returning because an inline body cannot `return`.
      */
    inline def answerStep[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B] < S,
        input: I[C],
        out: Out
    ): Any =
        handle[C](input) match
            case kyo: Kyo[Outcome[O[C] < (E & S), B], S] @unchecked =>
                out.kind = 2
                kyo
            case c: Continue[O[C] < (E & S)] @unchecked =>
                out.kind = 1
                c._1
            case o =>
                out.kind = 3
                o

    inline def answersLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B] < S,
        input0: I[C],
        k0: Arrow[Any, Any, Any],
        armed: Boolean,
        slot: Safepoint.Slot,
        out: Out
    ): Any =
        var in: Any                 = input0
        var k: Arrow[Any, Any, Any] = k0
        var n: Int                  = 128
        var result: Any             = null
        var running                 = true
        while running do
            if armed && Safepoint.stopped(slot) then
                out.kind = 1
                out.cont = null
                result = Effect.defer(resuspend(effectTag.erased, in, k).asInstanceOf[Any < Any], Arrow.id[Any])
                running = false
            else
                val o =
                    try handle[C](in.asInstanceOf[I[C]])
                    catch
                        case ex: Throwable =>
                            out.kind = Out.ClauseThrew
                            out.cont = k
                            throw ex
                o match
                    case kyo: Kyo[Outcome[O[C] < (E & S), B], S] @unchecked =>
                        out.kind = 2
                        out.cont = k
                        result = kyo
                        running = false
                    case c: Continue[O[C] < (E & S)] @unchecked =>
                        val ans = c._1
                        ans match
                            case ansKyo: Kyo[?, ?] @unchecked =>
                                out.kind = 1
                                out.cont = null
                                result = ans.map(a => k(a))
                                running = false
                            case _ =>
                                val next =
                                    try k(Nested.unnest[Any](ans))
                                    catch
                                        case ex: Throwable =>
                                            out.kind = 1
                                            out.cont = k
                                            throw ex
                                n -= 1
                                if n <= 0 then
                                    out.kind = 1
                                    out.cont = null
                                    result = Effect.defer(next, Arrow.id[Any])
                                    running = false
                                else
                                    nextAnswer(effectTag.erased, next, out) match
                                        case 1 =>
                                            in = out.input
                                            k = out.cont
                                        case 2 =>
                                            out.kind = 1
                                            out.cont = null
                                            result = out.input
                                            running = false
                                        case _ =>
                                            out.kind = 1
                                            out.cont = null
                                            result = next
                                            running = false
                                end if
                        end match
                    case o2 =>
                        out.kind = 3
                        out.cont = null
                        result = o2
                        running = false
                end match
        end while
        result
    end answersLoop

    inline def answerStepState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, C](
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B] < S,
        st: State,
        input: I[C],
        out: Out
    ): Any =
        handle[C](st, input) match
            case kyo: Kyo[Outcome2[State, O[C] < (E & S), B], S] @unchecked =>
                out.kind = 2
                kyo
            case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                out.kind = 1
                out.state = c._1
                c._2
            case o =>
                out.kind = 3
                o

    inline def answersLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B] < S,
        state0: State,
        input0: I[C],
        k0: Arrow[Any, Any, Any],
        armed: Boolean,
        slot: Safepoint.Slot,
        out: Out
    ): Any =
        var st: State               = state0
        var in: Any                 = input0
        var k: Arrow[Any, Any, Any] = k0
        var n: Int                  = 128
        var result: Any             = null
        var running                 = true
        while running do
            if armed && Safepoint.stopped(slot) then
                out.kind = 1
                out.state = st
                out.cont = null
                result = Effect.defer(resuspend(effectTag.erased, in, k).asInstanceOf[Any < Any], Arrow.id[Any])
                running = false
            else
                val o =
                    try handle[C](st, in.asInstanceOf[I[C]])
                    catch
                        case ex: Throwable =>
                            out.kind = Out.ClauseThrew
                            out.state = st
                            out.cont = k
                            throw ex
                o match
                    case kyo: Kyo[Outcome2[State, O[C] < (E & S), B], S] @unchecked =>
                        out.kind = 2
                        out.state = st
                        out.cont = k
                        result = kyo
                        running = false
                    case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case ansKyo: Kyo[?, ?] @unchecked =>
                                out.kind = 1
                                out.state = st
                                out.cont = null
                                result = ans.map(a => k(a))
                                running = false
                            case _ =>
                                val next =
                                    try k(Nested.unnest[Any](ans))
                                    catch
                                        case ex: Throwable =>
                                            out.kind = 1
                                            out.state = st
                                            out.cont = k
                                            throw ex
                                n -= 1
                                if n <= 0 then
                                    out.kind = 1
                                    out.state = st
                                    out.cont = null
                                    result = Effect.defer(next, Arrow.id[Any])
                                    running = false
                                else
                                    nextAnswer(effectTag.erased, next, out) match
                                        case 1 =>
                                            in = out.input
                                            k = out.cont
                                        case 2 =>
                                            out.kind = 1
                                            out.state = st
                                            out.cont = null
                                            result = out.input
                                            running = false
                                        case _ =>
                                            out.kind = 1
                                            out.state = st
                                            out.cont = null
                                            result = next
                                            running = false
                                end if
                        end match
                    case o2 =>
                        out.kind = 3
                        out.cont = null
                        result = o2
                        running = false
                end match
        end while
        result
    end answersLoopState

    /** The continuation-handler sibling of [[answersLoop]]: no outcome to destructure, because the
      * clause consumes the continuation itself and returns region currency directly. The loop keeps
      * answering while the region's next step is a suspension of its own tag whose continuation
      * [[nextAnswer]] can take apart; the handler stays installed on the stack throughout, so every
      * bail hands back a computation the eval continues with the region still positioned. The cell's
      * cont lane is the exception lane only: it carries the continuation the clause never consumed.
      */
    inline def answersCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (I[X], Arrow[O[X], A, E & S]) => A < (E & S),
        input0: I[C],
        k0: Arrow[Any, Any, Any],
        armed: Boolean,
        slot: Safepoint.Slot,
        out: Out
    ): Any =
        var in: Any                 = input0
        var k: Arrow[Any, Any, Any] = k0
        var n                       = 128
        var result: Any             = null
        var running                 = true
        while running do
            if armed && Safepoint.stopped(slot) then
                out.kind = 1
                out.cont = null
                result = Effect.defer(resuspend(effectTag.erased, in, k).asInstanceOf[Any < Any], Arrow.id[Any])
                running = false
            else
                val next =
                    try handle[C](in.asInstanceOf[I[C]], k.asInstanceOf[Arrow[O[C], A, E & S]])
                    catch
                        case ex: Throwable =>
                            out.kind = Out.ClauseThrew
                            out.cont = k
                            throw ex
                n -= 1
                if n <= 0 then
                    out.kind = 1
                    out.cont = null
                    result = Effect.defer(next.asInstanceOf[Any < Any], Arrow.id[Any])
                    running = false
                else
                    nextAnswer(effectTag.erased, next, out) match
                        case 1 =>
                            in = out.input
                            k = out.cont
                        case 2 =>
                            out.kind = 1
                            out.cont = null
                            result = out.input
                            running = false
                        case _ =>
                            out.kind = 1
                            out.cont = null
                            result = next
                            running = false
                end if
        end while
        result
    end answersCont

    private[kyo] object Out:
        // the branch the answer took: the clause answered and the region continues, the clause itself
        // suspended, or the clause completed the region
        inline def Answered: Int  = 1
        inline def Suspended: Int = 2
        inline def Finished: Int  = 3
        // the exception lane's discriminator: a clause that threw, as opposed to a continuation
        // application that threw. A clause's failure escapes the region (the interior entries release
        // and are passed over), where a continuation's failure is the region body's and the interior
        // participates; only the dispatch catches read this, alongside the rethrow
        inline def ClauseThrew: Int = 4
    end Out

    abstract private[kyo] class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        /** Answers once and hands the result back; the expansion overrides this with the clause
          * statically bound so consecutive same-tag answers loop inside one compiled method. The
          * cell's cont lane carries the continuation for the exception path only.
          */
        def answers[X](input: I[X], k: Arrow[Any, Any, Any], armed: Boolean, slot: Safepoint.Slot, out: Out): Any =
            out.kind = Out.Answered
            out.cont = k
            try run(input, k.asInstanceOf[Arrow[O[X], A, E & S]])
            catch
                case ex: Throwable =>
                    out.kind = Out.ClauseThrew
                    throw ex
            end try
        end answers
    end HandlerCont

    abstract private[kyo] class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S

        /** The stateless siblings of [[HandlerLoopState.answer]] and [[HandlerLoopState.answers]]: same
          * protocol through the same cell, no state lane.
          */
        def answer[X](input: I[X], out: Out): Any =
            run(input) match
                case kyo: Kyo[Outcome[O[X] < (E & S), B], S] @unchecked =>
                    out.kind = Out.Suspended
                    kyo
                case c: Continue[O[X] < (E & S)] @unchecked =>
                    out.kind = Out.Answered
                    c._1
                case o =>
                    out.kind = Out.Finished
                    o

        def answers[X](input: I[X], k: Arrow[Any, Any, Any], armed: Boolean, slot: Safepoint.Slot, out: Out): Any =
            out.cont = k
            try answer(input, out)
            catch
                case ex: Throwable =>
                    out.kind = Out.ClauseThrew
                    throw ex
            end try
        end answers
    end HandlerLoop

    abstract private[kyo] class HandlerLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S]:
        def initialState: State
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
        def apply(state: State, v: A): B < S
        final override def apply(v: A): B < S = apply(initialState, v)

        /** Runs the clause and takes its outcome apart, in the class the call site generated.
          *
          * The eval used to do both: call `run` and destructure the `Outcome2`, which made the outcome an
          * allocation crossing a virtual call. Here the clause call and the destructuring compile together
          * in the per-call-site class, so the outcome is born and consumed in one method and a JIT can
          * erase it regardless of how many handler classes the program has. The answer returns as the
          * value; the state and the branch taken go through `out`.
          *
          * The return type is the eval's own currency: the three branches return the region's answer, the
          * suspended clause, and the completed value, which only the eval's dispatch tells apart, by the
          * branch recorded in `out`.
          *
          * This body is the generic form; the expansion in `ArrowEffect.handleLoopState` overrides it with
          * the clause statically bound.
          */
        def answer[X](state: State, input: I[X], out: Out): Any =
            run(state, input) match
                case kyo: Kyo[Outcome2[State, O[X] < (E & S), B], S] @unchecked =>
                    out.kind = Out.Suspended
                    kyo
                case c: Continue2[State, O[X] < (E & S)] @unchecked =>
                    out.kind = Out.Answered
                    out.state = c._1
                    c._2
                case o =>
                    out.kind = Out.Finished
                    o

        /** Consecutive settled answers with the state in a local, while the region's next step keeps being
          * a suspension of its own tag; the expansion overrides this with the clause statically bound so
          * the per-answer box is born and dies inside one compiled method. This generic body answers once
          * and hands the continuation back unconsumed, which is the same protocol a bailing loop uses, so
          * the eval treats both alike.
          */
        def answers[X](state: State, input: I[X], k: Arrow[Any, Any, Any], armed: Boolean, slot: Safepoint.Slot, out: Out): Any =
            out.cont = k
            try answer(state, input, out)
            catch
                case ex: Throwable =>
                    out.kind = Out.ClauseThrew
                    out.state = state
                    throw ex
            end try
        end answers
    end HandlerLoopState

    private[kyo] object HandlerLoopState:
        def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
            h: HandlerLoopState[I, O, E, A, B, S, State],
            state: State
        ): HandlerLoopState[I, O, E, A, B, S, State] =
            new HandlerLoopState[I, O, E, A, B, S, State]:
                def frame                             = h.frame
                def tag                               = h.tag
                def initialState                      = state
                def run[X](state: State, input: I[X]) = h.run(state, input)
                def apply(state: State, v: A)         = h.apply(state, v)
                // the wrap changes only what the region starts from; the per-site answer bodies, with
                // their statically bound clause, must survive a clause suspension's re-entry
                override def answer[X](state: State, input: I[X], out: Out) = h.answer(state, input, out)
                override def answers[X](
                    state: State,
                    input: I[X],
                    k: Arrow[Any, Any, Any],
                    armed: Boolean,
                    slot: Safepoint.Slot,
                    out: Out
                ) =
                    h.answers(state, input, k, armed, slot, out)
    end HandlerLoopState

end Handler
