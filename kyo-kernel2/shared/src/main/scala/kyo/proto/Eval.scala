package kyo.proto

import kyo.Frame
import kyo.bug
import scala.annotation.tailrec

object Eval:

    def apply[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try loop(v)
        finally Safepoint.restore(slot, saved)
    end apply

    private type Susp    = Kyo.Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type Handler = Kyo.Handler[Nothing, Any, Any, Any]
    private type Cont    = Kyo.Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type Loop_   = Kyo.Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type LoopSt  = Kyo.Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]

    private given Frame = Frame.internal

    private def loop[A](v: A < Any): A =
        val stack = Stack.current()
        val base  = stack.size
        @tailrec def go(cur: Any < Any): Any =
            cur.lower(
                pending = kyo => go(step(kyo, stack, base)),
                done = value =>
                    if stack.size == base then value
                    else
                        val i = stack.size - 1
                        if stack.marked(i) then
                            // the region's body settled: the handler completes it, then the region's continuation
                            val handler = stack.handler(i)
                            val state   = stack.state(i)
                            val cont    = stack.pop()
                            go(cont(complete(handler, state, value), Arrow.id))
                        else go(stack.pop()(value))
                        end if
            )
        try go(v).asInstanceOf[A]
        finally stack.truncate(base)
    end loop

    private def complete(h: Handler, state: Any, v: Any): Any < Any =
        h match
            case hc: Cont @unchecked   => hc.complete(v)
            case hl: Loop_ @unchecked  => hl.complete(v)
            case hs: LoopSt @unchecked => hs.complete(state, v)

    // Kyo[Any, Nothing] is the computation of any row, the type every node is a subtype of
    private def step(kyo: Kyo[Any, Nothing], stack: Stack, base: Int): Any < Any =
        (kyo: @unchecked) match
            case d: Kyo.Defer[Any, Any, Any, Any] @unchecked =>
                stack.push(d.contB)
                stack.push(d.contA)
                d.value
            case h: Kyo.Handle[Nothing, Any, Any, Any, Any] @unchecked =>
                h.handler match
                    case hs: LoopSt @unchecked => stack.push(h.cont, h.handler, hs.initialState)
                    case _                     => stack.push(h.cont, h.handler, null)
                // erasure: E is Nothing in the pattern, so the row of h.v reads as Nothing
                h.v.asInstanceOf[Any < Any]
            case s: Susp @unchecked =>
                dispatch(s, stack, base)

    private def dispatch(s: Susp, stack: Stack, base: Int): Any < Any =
        val i = stack.find(s.tag.erased, base)
        if i < 0 then bug(s"unhandled suspension: $s")
        stack.handler(i) match
            case hc: Cont @unchecked =>
                // the clause receives the operation's continuation as a value; the region stays for
                // the clause's result. Erasure: E is Nothing in the pattern, so the row reads as Nothing
                val k = continuation(s, stack, i)
                hc.run(s.input, k(_)).asInstanceOf[Any < Any]
            case hl: Loop_ @unchecked =>
                hl.run(s.input).lower(
                    pending = clause => outcome(clause, i, s, stack),
                    done = {
                        case c: Loop.Continue[?] => answer(c._1.asInstanceOf[Any < Any], s, stack, i)
                        case done                => finish(done, i, stack)
                    }
                )
            case hs: LoopSt @unchecked =>
                hs.run(stack.state(i), s.input).lower(
                    pending = clause => outcome(clause, i, s, stack),
                    done = {
                        case c: Loop.Continue2[?, ?] =>
                            stack.setState(i, c._1)
                            answer(c._2.asInstanceOf[Any < Any], s, stack, i)
                        case done => finish(done, i, stack)
                    }
                )
        end match
    end dispatch

    // the operation's continuation: its own arrow with the interior above region `i` composed onto
    // it, innermost first. Consecutive continuations chain into one arrow, as they compose; a region
    // wraps what is above it as a Handle entered at the state it had. The interior is applied to the
    // remainder as a computation, with the two-argument apply: a pending remainder is deferred behind
    // the whole interior in one node, so it goes back on the stack as one entry, and the next capture
    // above this region folds one entry, not every continuation again (that is what kept
    // trailingMapsStayLinear linear, and what the one-argument apply, which decomposes a chain,
    // undid). The stack is cut back to the region: the continuation is a value now, and nothing of
    // it stays behind
    private def continuation(s: Susp, stack: Stack, i: Int): Arrow[Any, Any, Any] =
        val top = stack.size - 1
        if top <= i then s.cont
        else
            // walking up from the region: `outer` wraps a computation in everything below the
            // current entry, and `run` gathers consecutive continuations, each prepended so the run
            // chains right-deep with the innermost first. Right-deep matters: applying it alternates
            // through Transform's budgeted strict arm, so a long run unwinds onto the evaluator's
            // stack instead of the Java stack, as fold did before
            def flush(outer: (Any < Any) => Any < Any, run: Arrow[Any, Any, Any]): (Any < Any) => Any < Any =
                if run eq Arrow.Id then outer else x => outer(run(x, Arrow.id))
            @tailrec def loop(j: Int, outer: (Any < Any) => Any < Any, run: Arrow[Any, Any, Any]): (Any < Any) => Any < Any =
                if j > top then flush(outer, run)
                else if stack.marked(j) then
                    val h     = stack.handler(j)
                    val st    = stack.state(j)
                    val cont  = stack(j)
                    val below = flush(outer, run)
                    loop(j + 1, x => below(inside(h, st, cont)(x)), Arrow.id)
                else loop(j + 1, outer, stack(j).chain(run))
            val wrap = loop(i + 1, x => x, Arrow.id)
            stack.truncate(i + 1)
            Arrow.Transform[Any, Any, Any](o => wrap(s.cont(o)))
        end if
    end continuation

    // a computation inside a region: pending, it is the region's body; settled, the region completes on it
    private def inside(h: Handler, st: Any, k: Arrow[Any, Any, Any])(x: Any < Any): Any < Any =
        x.lower(
            pending = body =>
                new Kyo.Handle[Nothing, Any, Any, Any, Any]:
                    def v = body
                    val handler =
                        h match
                            case hs: LoopSt @unchecked => Kyo.Handler.HandleLoopState.resumed(hs, st)
                            case _                     => h
                    def cont =
                        k
            ,
            done = a => k(complete(h, st, a), Arrow.id)
        )

    // a settled answer goes into the operation's own arrow with the interior left on the stack, which
    // costs nothing; a pending answer is region currency: it runs under this handler with the interior
    // gone from the stack, and the continuation puts the interior back around the remainder, because
    // it is the remainder's wrapper
    private def answer(a: Any < Any, s: Susp, stack: Stack, i: Int): Any < Any =
        a.lower(
            pending = kyo => Kyo.Defer(kyo, continuation(s, stack, i)),
            done = x => s.cont(x)
        )

    // the same for an answer whose continuation is already a value, as in a parked outcome
    private def answered(a: Any < Any, k: Arrow[Any, Any, Any]): Any < Any =
        a.lower(pending = Kyo.Defer(_, k), done = k(_))

    // a clause that suspends before its outcome runs outside its region: the region and its interior
    // leave the stack, and the outcome re-wraps the region around the answer, or drops it on done
    private def outcome(clause: Kyo[Any, Any], i: Int, s: Susp, stack: Stack): Any < Any =
        val k    = continuation(s, stack, i)
        val h    = stack.handler(i)
        val st   = stack.state(i)
        val cont = stack(i)
        stack.truncate(i)
        Kyo.Defer(
            clause,
            Arrow.Transform[Any, Any, Any] {
                case c: Loop.Continue[?]     => inside(h, st, cont)(answered(c._1.asInstanceOf[Any < Any], k))
                case c: Loop.Continue2[?, ?] => inside(h, c._1, cont)(answered(c._2.asInstanceOf[Any < Any], k))
                case done                    => cont(done, Arrow.id)
            }
        )
    end outcome

    // Loop.done: the region ends, complete is bypassed, the payload flows into the region's continuation as it came
    private def finish(done: Any, i: Int, stack: Stack): Any < Any =
        val cont = stack(i)
        stack.truncate(i)
        cont(done, Arrow.id)
    end finish

end Eval
