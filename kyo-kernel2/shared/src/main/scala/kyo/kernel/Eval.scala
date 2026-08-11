package kyo.kernel

import kyo.Chunk
import kyo.Frame
import scala.annotation.tailrec

private[kernel] object Eval:

    private type Exits = Chunk[Arrow[Any, Any, Any]]

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, slot)
        Safepoint.restore(slot, saved)
        res
    end apply

    def partial[A, S](v: A < S, stop: () => Boolean): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        @tailrec def loop(v: A < S): A < S =
            if stop() then v
            else
                (v: @unchecked) match
                    case kyo: Kyo.Defer[?, ?, ?] =>
                        Safepoint.restore(slot, 0L)
                        val step = kyo.cont.step
                        loop(step.head(kyo.value, step.tail).asInstanceOf[A < S])
                    case v =>
                        v
        val res = loop(v)
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
    // stack, and answering allocates nothing.
    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot): A < S =
        @tailrec def loop(v: A < S, hs: Handlers, exits: Exits): A < S =
            (v: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] @unchecked =>
                    loop(
                        kyo.value.asInstanceOf[A < S],
                        hs.add(kyo.handler),
                        exits.append(kyo.cont.asInstanceOf[Arrow[Any, Any, Any]])
                    )
                case kyo: Kyo.Suspend[i, o, e, x, ?, ?] @unchecked =>
                    val idx = hs.indexOf(kyo.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    else
                        hs(idx) match
                            case h: Handler.Loop[?, ?, ?, ?, ?] =>
                                val outcome = h.asInstanceOf[Handler.Loop[i, o, Nothing, Any, Any]][x](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Kyo[Any, Any]].map(transform {
                                            case c: Handler.Loop.Continue[?] =>
                                                rebuildFrom(idx, walk(kCont, c._1.asInstanceOf[Any < Any]), hsAll, exAll)
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Handler.Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        })
                                                        loop(chained.asInstanceOf[A < S], hs.take(idx + 1), exits.take(idx + 1))
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            hs,
                                                            exits
                                                        )
                                            case done =>
                                                loop(
                                                    walk(exits(idx), Nested.lift(done)).asInstanceOf[A < S],
                                                    hs.take(idx),
                                                    exits.take(idx)
                                                )
                                end match
                            case h: Handler.LoopState[?, ?, ?, ?, ?] =>
                                val outcome = h.asInstanceOf[Handler.LoopState[i, o, Nothing, Any, Any]][x](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        val chained = pending.asInstanceOf[Kyo[Any, Any]].map(transform {
                                            case c: Handler.Loop.Continue2[?, ?] =>
                                                rebuildFrom(
                                                    idx,
                                                    walk(kCont, c._2.asInstanceOf[Any < Any]),
                                                    hsAll.updated(idx, c._1.asInstanceOf[Handler[?, ?, ?]]),
                                                    exAll
                                                )
                                            case done =>
                                                walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Handler.Loop.Continue2[?, ?] =>
                                                val hs2 =
                                                    c._1.asInstanceOf[Handler[?, ?, ?]] match
                                                        case next if next eq h => hs
                                                        case next              => hs.updated(idx, next)
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs2
                                                        val exAll = exits
                                                        val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, Nested.lift(a)), hsAll, exAll)
                                                        })
                                                        loop(chained.asInstanceOf[A < S], hs2.take(idx + 1), exits.take(idx + 1))
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            hs2,
                                                            exits
                                                        )
                                                end match
                                            case done =>
                                                loop(
                                                    walk(exits(idx), Nested.lift(done)).asInstanceOf[A < S],
                                                    hs.take(idx),
                                                    exits.take(idx)
                                                )
                                end match
                            case other =>
                                throw new IllegalStateException(s"cannot handle: $other")
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    Safepoint.restore(slot, 0L)
                    val step = kyo.cont.step
                    loop(step.head(kyo.value, step.tail).asInstanceOf[A < S], hs, exits)
                case v =>
                    val n = hs.size
                    if n == 0 then v
                    else loop(walk(exits(n - 1), v.asInstanceOf[Any < Any]).asInstanceOf[A < S], hs.take(n - 1), exits.take(n - 1))
        loop(v0, Handlers.empty, Chunk.empty)
    end evalLoop

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Any): Any < Any =
        val step = cont.step
        step.head(v, step.tail)

    // one arrow step over erased currency applying f to the settled value,
    // in the suspendWith shape: a pending input re-suspends the step via map
    // and the budget defers deep chains
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
    private def rebuildFrom(from: Int, value: Any < Any, hs: Handlers, exits: Exits): Any < Any =
        @tailrec def wrap(i: Int, acc: Any < Any): Any < Any =
            if i < from then acc
            else
                wrap(
                    i - 1,
                    new Kyo.Handled[[B] =>> Any, [B] =>> Any, Nothing, Any, Any, Any](
                        acc.asInstanceOf[Any < Nothing],
                        hs(i).asInstanceOf[Handler[[B] =>> Any, [B] =>> Any, Nothing]],
                        exits(i)
                    )
                )
        wrap(hs.size - 1, value)
    end rebuildFrom

end Eval
