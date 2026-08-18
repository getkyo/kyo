package kyo.proto

import kyo.Frame
import kyo.Loop
import kyo.Span
import kyo.bug
import scala.annotation.tailrec

object Eval:

    def apply[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try loop(v)
        finally Safepoint.restore(slot, saved)
    end apply

    private type Susp     = Kyo.Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type Handler  = Kyo.Handler[Nothing, Any, Any, Any]
    private type Cont     = Kyo.Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type Loop_    = Kyo.Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    private type LoopSt   = Kyo.Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]
    private type Entries  = Span[Arrow[?, ?, ?]]
    private type Handlers = Span[Kyo.Handler[?, ?, ?, ?]]
    private type States   = Span[Any]

    private given Frame = Frame.internal

    private def loop[A](v: A < Any): A =
        val stack = new Stack
        @tailrec def go(cur: Any < Any): Any =
            cur.lower(
                pending = kyo => go(step(kyo, stack)),
                done = value =>
                    if stack.isEmpty then value
                    else
                        val i = stack.size - 1
                        if stack.marked(i) then
                            // the region's body settled: the handler completes it, then the region's continuation
                            val handler = stack.handler(i)
                            val state   = stack.state(i)
                            val cont    = stack.pop()
                            go(cont(complete(handler, state, value), Arrow.id))
                        else go(stack.pop()(value))
            )
        go(v).asInstanceOf[A]
    end loop

    private def complete(h: Handler, state: Any, v: Any): Any < Any =
        h match
            case hc: Cont @unchecked   => hc.complete(v)
            case hl: Loop_ @unchecked  => hl.complete(v)
            case hs: LoopSt @unchecked => hs.complete(state, v)

    // Kyo[Any, Nothing] is the computation of any row, the type every node is a subtype of
    private def step(kyo: Kyo[Any, Nothing], stack: Stack): Any < Any =
        kyo match
            case c: Kyo.Continue[Any, Any, Any, Any] @unchecked =>
                if c.contB ne Arrow.Id then stack.push(c.contB)
                if c.contA ne Arrow.Id then stack.push(c.contA)
                c.value
            case d: Kyo.Defer[Any, Any, Any, Any] @unchecked =>
                if d.contB ne Arrow.Id then stack.push(d.contB)
                if d.contA ne Arrow.Id then stack.push(d.contA)
                d.value
            case p: Kyo.Park[Any, Any, Any] @unchecked =>
                stack.pushAll(p.entries, p.handlers, p.states)
                p.value
            case h: Kyo.Handle[Nothing, Any, Any, Any, Any] @unchecked =>
                h.handler match
                    case hs: LoopSt @unchecked => stack.push(h.cont, h.handler, hs.initialState)
                    case _                     => stack.push(h.cont, h.handler, null)
                // erasure: E is Nothing in the pattern, so the row of h.v reads as Nothing
                h.v.asInstanceOf[Any < Any]
            case s: Susp @unchecked =>
                dispatch(s, stack)

    private def dispatch(s: Susp, stack: Stack): Any < Any =
        val i = stack.find(s.tag.erased)
        if i < 0 then bug(s"unhandled suspension: $s")
        stack.handler(i) match
            case hc: Cont @unchecked =>
                // the clause receives the operation's continuation as a value: the interior parked
                // around the suspension's own continuation; the region stays for the clause's result
                val resume: Any => Any < Any =
                    if stack.size == i + 1 then s.cont(_)
                    else
                        val entries  = stack.copyEntries(i + 1)
                        val handlers = stack.copyHandlers(i + 1)
                        val states   = stack.copyStates(i + 1)
                        stack.truncate(i + 1)
                        o => new Kyo.Park(entries, handlers, states, s.cont(o))
                // erasure: E is Nothing in the pattern, so the row reads as Nothing
                hc.run(s.input, resume).asInstanceOf[Any < Any]
            case hl: Loop_ @unchecked =>
                val out = hl.run(s.input)
                out.lower(
                    pending = clause => parkedOutcome(clause, i, s, stack),
                    done = {
                        case c: Loop.Continue[?] =>
                            answer(c._1.asInstanceOf[Any < Any], i, s, stack)
                        case _ =>
                            // Loop.done: the region ends, complete is bypassed, the payload flows
                            // into the region's continuation as it came
                            val cont = stack(i)
                            stack.truncate(i)
                            cont(out, Arrow.id)
                    }
                )
            case hs: LoopSt @unchecked =>
                val out = hs.run(stack.state(i), s.input)
                out.lower(
                    pending = clause => parkedOutcome(clause, i, s, stack),
                    done = {
                        case c: Loop.Continue2[?, ?] =>
                            stack.setState(i, c._1)
                            answer(c._2.asInstanceOf[Any < Any], i, s, stack)
                        case _ =>
                            val cont = stack(i)
                            stack.truncate(i)
                            cont(out, Arrow.id)
                    }
                )
        end match
    end dispatch

    // a settled answer goes into the operation's continuation with the interior in place; a pending
    // answer is region currency: it runs under this handler with the interior parked, and the
    // interior receives its value
    private def answer(a: Any < Any, i: Int, s: Susp, stack: Stack): Any < Any =
        a.lower(
            pending = k =>
                if stack.size == i + 1 then Kyo.Continue(k, s.cont)
                else
                    val entries  = stack.copyEntries(i + 1)
                    val handlers = stack.copyHandlers(i + 1)
                    val states   = stack.copyStates(i + 1)
                    stack.truncate(i + 1)
                    Kyo.Continue(k, s.cont, restore(entries, handlers, states))
            ,
            done = x => s.cont(x)
        )

    // a clause that suspends before its outcome runs with the region and its interior parked; its
    // outcome rebuilds them around the answer, or drops them on done
    private def parkedOutcome(clause: Kyo[Any, Any], i: Int, s: Susp, stack: Stack): Any < Any =
        val iEntries  = stack.copyEntries(i + 1)
        val iHandlers = stack.copyHandlers(i + 1)
        val iStates   = stack.copyStates(i + 1)
        stack.truncate(i + 1)
        val rEntries  = stack.copyEntries(i)
        val rHandlers = stack.copyHandlers(i)
        val rStates   = stack.copyStates(i)
        stack.truncate(i)
        def rebuild(states: States, a: Any < Any): Any < Any =
            a.lower(
                pending = k => new Kyo.Park(rEntries, rHandlers, states, Kyo.Continue(k, s.cont, restore(iEntries, iHandlers, iStates))),
                done = x => new Kyo.Park(rEntries, rHandlers, states, park(iEntries, iHandlers, iStates, s.cont(x)))
            )
        Kyo.Continue(
            clause,
            Arrow.Transform[Any, Any, Any] {
                case c: Loop.Continue[?]     => rebuild(rStates, c._1.asInstanceOf[Any < Any])
                case c: Loop.Continue2[?, ?] => rebuild(Span.fromUnsafe(Array[Any](c._1)), c._2.asInstanceOf[Any < Any])
                case done                    => rEntries(0).asInstanceOf[Arrow[Any, Any, Any]](done, Arrow.id)
            }
        )
    end parkedOutcome

    private def park(entries: Entries, handlers: Handlers, states: States, value: Any < Any): Any < Any =
        if entries.isEmpty then value
        else new Kyo.Park(entries, handlers, states, value)

    // the arrow that puts a parked interior back and delivers its input into it
    private def restore(entries: Entries, handlers: Handlers, states: States): Arrow[Any, Any, Any] =
        Arrow.Transform[Any, Any, Any](y => new Kyo.Park(entries, handlers, states, y))

end Eval
