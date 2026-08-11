package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.kernel.*
import scala.annotation.tailrec

// public: the internal package carries the visibility intent. Any private
// qualifier, including private[kyo] that is public in bytecode, makes the
// compiler emit inline accessors for references from public inline bodies,
// and those materialize the package prefix as a runtime value, failing with
// NoClassDefFoundError: kyo/kernel/internal at every eval call site
object Eval:

    // a suspension whose scan walked deeper than this over chain-y layer
    // storage flattens the storage before proceeding
    private inline def CompactThreshold = 8

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, slot, partial = false)
        Safepoint.restore(slot, saved)
        res
    end apply

    // the scheduler entry: evaluates like apply but yields instead of
    // throwing or spinning. It returns the standing computation reified with
    // its remaining layers when a Safepoint.stop request is pending on this
    // thread's slot or when a suspension has no handler; the result resumes
    // by evaluating it again. Preemption is dispatched only through the
    // thread's slot, and ArrowEffect.handlePartial cannot take this role: it
    // parks at region nodes by design, so evaluating regions without
    // throwing on a miss needs this entry
    def partial[A, S](v: A < S): A < S =
        val slot = Safepoint.get()
        if Safepoint.stopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            val res   = evalLoop(v, slot, partial = true)
            Safepoint.restore(slot, saved)
            res
        end if
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
    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot, partial: Boolean): A < S =
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
                                if !partial then throw new IllegalStateException(s"unhandled suspension: $kyo")
                                else rebuildFrom(0, v.asInstanceOf[Any < Any], hs, exits).asInstanceOf[A < S]
                    else
                        hs(idx) match
                            case h: Handler.Loop[[B] =>> Any, [B] =>> Any, Nothing, Any, Any] @unchecked =>
                                val outcome = h[Any](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Any < Any].map {
                                            case c: Loop.Continue[?] =>
                                                rebuildFrom(idx, walk(kCont, c._1.asInstanceOf[Any < Any]), hsAll, exAll)
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        }(using Frame.internal)
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx), Math.min(flatBelow, idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Any < Any].map { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        }(using Frame.internal)
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
                            case h: Handler.LoopState[[B] =>> Any, [B] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                val outcome = h[Any](kyo.input, h.state)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Any < Any].map {
                                            case c: Loop.Continue2[?, ?] =>
                                                rebuildFrom(
                                                    idx,
                                                    walk(kCont, c._2.asInstanceOf[Any < Any]),
                                                    hsAll.updated(idx, h.withState(c._1)),
                                                    exAll
                                                )
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        }(using Frame.internal)
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx), Math.min(flatBelow, idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue2[?, ?] =>
                                                // the reference check is only an optimization: a
                                                // false negative rebuilds an identical successor
                                                val hs2 =
                                                    if c._1.asInstanceOf[AnyRef] eq h.state.asInstanceOf[AnyRef] then hs
                                                    else hs.updated(idx, h.withState(c._1))
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs2
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Any < Any].map { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        }(using Frame.internal)
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
                            case h: Handler.Cont[[B] =>> Any, [B] =>> Any, Nothing, Any, Any] @unchecked =>
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
                                val body = h[Any](kyo.input, cont)
                                loop(body.asInstanceOf[A < S], hs.take(idx + 1), exits.take(idx + 1), Math.min(flatBelow, idx + 1))
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    if partial && Safepoint.stopped(slot) then
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

    // When a computation leaves the evaluated region structure as a plain
    // value, the layers it sat under must travel with it or their handlers
    // and exits would be lost. This happens in three places: a clause that
    // suspends before producing its outcome (the resumed outcome must still
    // run under the layers outside its own scope), a Cont handler's captured
    // continuation (each call re-enters the crossed layers), and a partial
    // evaluation yielding a residual. The loop wraps the value back into one
    // Handled region node per layer, innermost first, so evaluating the
    // result re-enters the same layers with the same exits: a residual is
    // ordinary data and resumes by evaluation alone. The casts keep the
    // erased construction out of the implicit lift, which would nest the
    // computation as data
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
