package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.kernel.*
import scala.annotation.tailrec

// visibility is the internal package itself: referenced from public inline
// bodies, so a private modifier would force an inline accessor that
// materializes the package prefix as a runtime value
// TODO private[kyo] becomes public in the bytecode. Qualified privates are just public at the bytecode level
object Eval:

    // a suspension whose scan walked deeper than this over chain-y layer
    // storage flattens the storage before proceeding
    private inline def CompactThreshold = 8

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, slot, null)
        Safepoint.restore(slot, saved)
        res
    end apply

    // the scheduler entry: evaluates like apply but yields instead of
    // throwing or spinning. It returns the standing computation reified with
    // its remaining layers when the stop check answers true, when a
    // Safepoint.stop request is pending on this thread's slot, or when a
    // suspension has no handler; the result resumes by evaluating it again
    // TODO if we'll dispatcg interruption/preemption via the thread, I think we can remove the stop function here? In fact, we don't need this method and ArrowEffect.handlePartial is enough?
    def partial[A, S](v: A < S, stop: () => Boolean): A < S =
        if stop() then v
        else
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            val res   = evalLoop(v, slot, stop)
            Safepoint.restore(slot, saved)
            res
    end partial

    // One flat loop carries the value with its region layers, a layer being a
    // handler and its scope's exit continuation, appended on entry. A settled
    // value pops the innermost exit; done feeds its own layer's exit and
    // discards the layers it climbs past; a clause that suspends before
    // producing its outcome is chained onto the computation and runs under
    // the layers outside its own, with the crossed layers rebuilt around the
    // resumption. Nothing recurses, so scope depth never reaches the Java
    // stack, and answering allocates nothing. Layer entry and settle are
    // constant-time chain nodes; a suspension whose handler scan walks deep
    // flattens the storage once and later reads stay flat.
    // TODO remove the stop function?
    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot, stop: () => Boolean): A < S =
        @tailrec def loop(v: A < S, hs: Handlers, exits: Chunk[Arrow[Any, Any, Any]], flatBelow: Int): A < S =
            (v: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] @unchecked =>
                    loop(
                        kyo.value.asInstanceOf[A < S],
                        hs.add(kyo.handler),
                        exits.append(kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]),
                        flatBelow
                    )
                case kyo: Kyo.Suspend[i, o, e, x, ?, ?] @unchecked =>
                    val idx = hs.indexOf(kyo.tag)
                    // a scan that walked deep over chain-y storage flattens it and
                    // re-dispatches; layers below flatBelow already read as a flat
                    // array, so the second bound keeps flat storage as is and makes
                    // the re-dispatch proceed
                    if (hs.size - 1 - idx > CompactThreshold) && (hs.size - flatBelow > CompactThreshold) then
                        loop(v, hs.compact, exits.toIndexed, hs.size)
                    else if idx < 0 then
                        kyo.root match
                            case d: Kyo.Defaulted =>
                                loop(
                                    walk(kyo.cont.asInstanceOf[Arrow[Any, Any, Any]], Nested.lift(d.default)).asInstanceOf[A < S],
                                    hs,
                                    exits,
                                    flatBelow
                                )
                            case _ =>
                                if stop == null then throw new IllegalStateException(s"unhandled suspension: $kyo")
                                else rebuildFrom(0, v.asInstanceOf[Any < Any], hs, exits).asInstanceOf[A < S]
                    else
                        hs(idx) match
                            case h: Handler.Loop[?, ?, ?, ?, ?] =>
                                val outcome = h.asInstanceOf[Handler.Loop[i, o, Nothing, Any, Any]].clause[x](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Kyo[Any, Any]].map(transform {
                                            case c: Loop.Continue[?] =>
                                                rebuildFrom(idx, walk(kCont, c._1.asInstanceOf[Any < Any]), hsAll, exAll)
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx), Math.min(flatBelow, idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        })
                                                        loop(
                                                            chained.asInstanceOf[A < S],
                                                            hs.take(idx + 1),
                                                            exits.take(idx + 1),
                                                            Math.min(flatBelow, idx + 1)
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            hs,
                                                            exits,
                                                            flatBelow
                                                        )
                                            case done =>
                                                loop(
                                                    walk(exits(idx), Nested.lift(done)).asInstanceOf[A < S],
                                                    hs.take(idx),
                                                    exits.take(idx),
                                                    Math.min(flatBelow, idx)
                                                )
                                end match
                            case h0: Handler.LoopState[?, ?, ?, ?, ?, ?] =>
                                val h       = h0.asInstanceOf[Handler.LoopState[i, o, Nothing, Any, Any, Any]]
                                val outcome = h.clause[x](kyo.input, h.state)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Kyo[Any, Any]].map(transform {
                                            case c: Loop.Continue2[?, ?] =>
                                                rebuildFrom(
                                                    idx,
                                                    walk(kCont, c._2.asInstanceOf[Any < Any]),
                                                    hsAll.updated(
                                                        idx,
                                                        new Handler.LoopState[i, o, Nothing, Any, Any, Any](h.tag, c._1, h.clause)
                                                    ),
                                                    exAll
                                                )
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx), Math.min(flatBelow, idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue2[?, ?] =>
                                                // the reference check is only an optimization: a
                                                // false negative rebuilds an identical successor
                                                val hs2 =
                                                    if c._1.asInstanceOf[AnyRef] eq h.state.asInstanceOf[AnyRef] then hs
                                                    else
                                                        hs.updated(
                                                            idx,
                                                            new Handler.LoopState[i, o, Nothing, Any, Any, Any](h.tag, c._1, h.clause)
                                                        )
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs2
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        })
                                                        loop(
                                                            chained.asInstanceOf[A < S],
                                                            hs2.take(idx + 1),
                                                            exits.take(idx + 1),
                                                            Math.min(flatBelow, idx + 1)
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            hs2,
                                                            exits,
                                                            Math.min(flatBelow, idx)
                                                        )
                                                end match
                                            case done =>
                                                loop(
                                                    walk(exits(idx), Nested.lift(done)).asInstanceOf[A < S],
                                                    hs.take(idx),
                                                    exits.take(idx),
                                                    Math.min(flatBelow, idx)
                                                )
                                end match
                            case h: Handler.Cont[?, ?, ?, ?, ?] =>
                                val hsAll = hs
                                val exAll = exits
                                val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                // the continuation rebuilds the crossed layers around the
                                // resumption; each call builds a fresh value, so capture
                                // is multi-shot by construction. The clause runs under
                                // its own layer and the outer ones, so its re-raises are
                                // answered by this handler and its exit applies on settle
                                val cont: Any => Any < Any =
                                    o => rebuildFrom(idx + 1, walk(kCont, Nested.lift(o)), hsAll, exAll)
                                val body = h.asInstanceOf[Handler.Cont[i, o, Nothing, Any, Any]].clause[x](kyo.input, cont)
                                loop(body.asInstanceOf[A < S], hs.take(idx + 1), exits.take(idx + 1), Math.min(flatBelow, idx + 1))
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    if (stop != null) && (Safepoint.stopped(slot) || stop()) then
                        rebuildFrom(0, v.asInstanceOf[Any < Any], hs, exits).asInstanceOf[A < S]
                    else
                        Safepoint.restore(slot, 0L)
                        val step = kyo.cont.step
                        loop(step.head(kyo.value, step.tail).asInstanceOf[A < S], hs, exits, flatBelow)
                case v =>
                    val n = hs.size
                    if n == 0 then v
                    else
                        loop(
                            walk(exits(n - 1), v.asInstanceOf[Any < Any]).asInstanceOf[A < S],
                            hs.take(n - 1),
                            exits.take(n - 1),
                            Math.min(flatBelow, n - 1)
                        )
                    end if
        loop(v0, Handlers.empty, Chunk.empty, 0)
    end evalLoop

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Any): Any < Any =
        val step = cont.step
        step.head(v, step.tail)

    // one arrow step over erased currency applying f to the settled value,
    // in the suspendWith shape: a pending input re-suspends the step via map
    // and the budget defers deep chains

    // TODO isn't this <.map? let's avoid having this code if possible
    private def transform(f: Any => Any < Any): Arrow.Transform[Any, Any, Any] =
        def mapLoop[C, S3](v: Any < S3, next: Arrow[Any, C, S3]): C < S3 =
            def arrow: Arrow.Transform[Any, C, S3] =
                new Arrow.Transform[Any, C, S3]:
                    def frame = Frame.internal
                    def apply[D, S4](v2: Any < S4, next2: Arrow[C, D, S4]) =
                        mapLoop(v2, next.chain(next2))
            v match
                case kyo: Kyo[?, ?] =>
                    kyo.asInstanceOf[Kyo[Any, S3]].map(arrow).asInstanceOf[C < S3]
                case v =>
                    val res  = Nested.unnest[Any](v)
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        new Kyo.Defer(v, arrow)
                    else
                        val step = next.step
                        val out  = step.head(f(res).asInstanceOf[Any < S3], step.tail)
                        Safepoint.exit(slot)
                        out
                    end if
            end match
        end mapLoop
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]) =
                mapLoop(v, next)
        end new
    end transform

    // the crossed layers are restored as plain region nodes around the
    // resumed computation; the casts keep the erased construction out of the
    // implicit lift, which would nest the computation as data
    // TODO not sure I understand the need for this nor why it loops
    private def rebuildFrom(from: Int, value: Any < Any, hs0: Handlers, exits0: Chunk[Arrow[Any, Any, Any]]): Any < Any =
        // reads every layer once, so chain-y storage flattens first; a no-op
        // when the storage is already flat
        val hs    = hs0.compact
        val exits = exits0.toIndexed
        @tailrec def wrap(i: Int, acc: Any < Any): Any < Any =
            if i < from then acc
            else
                wrap(
                    i - 1,
                    new Kyo.Handled[[B] =>> Any, [B] =>> Any, Nothing, Any, Any, Any](
                        acc.asInstanceOf[Any < Nothing],
                        hs(i).asInstanceOf[Handler[[B] =>> Any, [B] =>> Any, Nothing, Any, Any]],
                        exits(i)
                    )
                )
        wrap(hs.size - 1, value)
    end rebuildFrom

end Eval
