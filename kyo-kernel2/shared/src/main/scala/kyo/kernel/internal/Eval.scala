package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.bug
import kyo.kernel.*
import kyo.kernel.`<`.fromKyo
import scala.annotation.tailrec

object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try run(v, slot, partial = false)
        finally Safepoint.restore(slot, saved)
    end apply

    def partial[A, S](v: A < S): A < S =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            Safepoint.arm(slot)
            try run(v, slot, partial = true)
            finally Safepoint.restore(slot, saved)
        end if
    end partial

    private def run[A, S](v: A < S, slot: Safepoint.Slot, partial: Boolean): A < S =
        val stack = Stack.current()
        val base  = stack.size

        // the most recent operation answered in place: a throw in a frame that
        // continues it is attributed to the suspension, whose identity the
        // in-place answer otherwise drops
        var suspended: Kyo.Suspend[?, ?, ?, ?, ?, ?] | Null = null

        def dispatch(kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?], i: Int): Any < Nothing =
            try
                stack(i) match
                    case h: Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        h.run(kyo.input) match
                            case p: Kyo[Any, Nothing] @unchecked =>
                                val seg = stack.copyFrom(i)
                                stack.truncate(i)
                                stack.push(interpret(seg))
                                p
                            case v =>
                                Nested.unnest[Loop.Outcome[Any < Nothing, Any]](v) match
                                    case c: Loop.Continue[Any < Nothing] @unchecked =>
                                        c._1 match
                                            case p: Kyo[?, ?] if i + 1 < stack.size =>
                                                // an effectful answer sees the region, whose successor
                                                // answers a re-raise, but not the region's interior:
                                                // that is the operation's continuation and receives
                                                // the answer's result
                                                val seg = stack.copyFrom(i + 1)
                                                stack.truncate(i + 1)
                                                stack.push(resume(seg, 0))
                                                c._1
                                            case _ =>
                                                c._1
                                    case b =>
                                        stack.truncate(i)
                                        Nested.lift(b)
                    case h: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                        val seg = stack.copyFrom(i + 1)
                        val out = h.run(kyo.input, o => rebuild(seg, 0, Nested.lift(o)))
                        if h.deep then stack.truncate(i + 1)
                        else stack.truncate(i)
                        out
                    case f =>
                        bug(s"eval stack corruption: found $f where a handler was expected")
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, kyo, stack, base)
                    throw ex

        def unhandled(kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?]): Nothing =
            try bug(s"unhandled suspension: $kyo, no handler for its effect is installed in the current evaluation")
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, kyo, stack, base)
                    throw ex

        def settle(f: Stack.Entry, settled: Any < Nothing): Any < Nothing =
            var applied: Any = f
            try
                f match
                    case a: Arrow[Any, Any, Nothing] @unchecked =>
                        // the contiguous arrow run below rides in the tail, so the whole
                        // run applies through the nested protocol in one iteration and a
                        // pending value mid-run carries the unconsumed rest by reference.
                        // The fold runs upward so each transform chains as a right-nested
                        // Step; the run stops at the base and at region entries
                        val top = stack.size
                        var i   = top
                        while i > base && stack(i - 1).isInstanceOf[Arrow[?, ?, ?]] do i -= 1
                        var whole = a
                        if i < top then
                            var acc = stack(i).asInstanceOf[Arrow[Any, Any, Nothing]]
                            var j   = i + 1
                            while j < top do
                                acc = stack(j).asInstanceOf[Arrow[Any, Any, Nothing]].chain(acc)
                                j += 1
                            stack.truncate(i)
                            whole = a.chain(acc)
                            applied = whole
                        end if
                        val step = whole.step
                        step.head(settled, step.tail)
                    case h: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                        h.complete(Nested.unnest[Any](settled))
                    case h: Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        h.complete(Nested.unnest[Any](settled))
                    case f =>
                        bug(s"eval stack corruption: cannot settle a value against frame $f")
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, suspended, applied, stack, base)
                    throw ex
            end try
        end settle

        var cur: Any < Nothing = v
        var running            = true
        try
            while running do
                cur match
                    case kyo: Kyo.Defer[?, ?, ?] =>
                        if partial && Safepoint.consumeStopped(slot) then
                            cur = rebuild(stack.copyFrom(base), 0, kyo)
                            running = false
                        else
                            suspended = null
                            Safepoint.reset(slot)
                            stack.push(kyo.cont)
                            cur = kyo.value
                    case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                        val i = stack.find(kyo.tag.erased, base)
                        if i >= 0 then
                            suspended = kyo
                            kyo match
                                case cont: Arrow[?, ?, ?] =>
                                    // a fused suspendWith node is its own continuation:
                                    // it rides the interior as an arrow so the answer
                                    // resumes through it
                                    stack.push(cont)
                                case _ =>
                                    ()
                            end match
                            cur = dispatch(kyo, i)
                        else if partial then
                            cur = rebuild(stack.copyFrom(base), 0, kyo)
                            running = false
                        else unhandled(kyo)
                        end if
                    case kyo: Kyo.HandleCont[?, ?, ?, ?, ?, ?, ?] =>
                        suspended = null
                        stack.push(kyo, kyo.tag.erased)
                        cur = kyo.value
                    case kyo: Kyo.HandleLoop[?, ?, ?, ?, ?, ?] =>
                        suspended = null
                        stack.push(kyo, kyo.tag.erased)
                        cur = kyo.value
                    case settled =>
                        if stack.size == base then running = false
                        else cur = settle(stack.pop(), settled)
            end while
        catch
            case ex: Throwable =>
                EffectTrace.splice(ex)
                throw ex
        finally stack.truncate(base)
        end try
        cur.asInstanceOf[A < S]
    end run

    private def interpret(seg: Array[Stack.Entry]): Arrow.Transform[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                (v: @unchecked) match
                    case _: Kyo[?, ?] =>
                        Kyo.defer(v, this.chain(next))
                    case v =>
                        val r =
                            Nested.unnest[Loop.Outcome[Any < Nothing, Any]](v) match
                                case c: Loop.Continue[Any < Nothing] @unchecked =>
                                    c._1 match
                                        case p: Kyo[?, ?] if seg.length > 1 =>
                                            renode(seg(0), Kyo.defer(c._1, resume(seg, 1)))
                                        case _ =>
                                            rebuild(seg, 0, c._1)
                                case b =>
                                    Nested.lift(b)
                        (r: @unchecked) match
                            case kyo: Kyo[Any, Nothing] =>
                                kyo.map(next).asInstanceOf[C < S2]
                            case r =>
                                val step = next.step
                                step.head(r.asInstanceOf[Any < S2], step.tail)
                        end match

    private def resume(seg: Array[Stack.Entry], from: Int): Arrow.Transform[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                (v: @unchecked) match
                    case _: Kyo[?, ?] =>
                        Kyo.defer(v, this.chain(next))
                    case v =>
                        rebuild(seg, from, v.asInstanceOf[Any < Nothing]) match
                            case kyo: Kyo[Any, Nothing] @unchecked =>
                                kyo.map(next).asInstanceOf[C < S2]
                            case r =>
                                val step = next.step
                                step.head(r.asInstanceOf[Any < S2], step.tail)

    private def rebuild(seg: Array[Stack.Entry], from: Int, v: Any < Nothing): Any < Nothing =
        @tailrec def loop(i: Int, acc: Any < Nothing): Any < Nothing =
            if i < from then acc
            else loop(i - 1, renode(seg(i), acc))
        loop(seg.length - 1, v)
    end rebuild

    private def renode(entry: Stack.Entry, acc: Any < Nothing): Any < Nothing =
        entry match
            case a: Arrow[Any, Any, Nothing] @unchecked =>
                Kyo.defer(acc, a)
            case hc: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                new Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]:
                    def tag                                            = hc.tag
                    def value                                          = acc
                    def run[X](input: Any, cont: Any => Any < Nothing) = hc.run(input, cont)
                    def complete(v: Any)                               = hc.complete(v)
                    override def deep                                  = hc.deep
            case hl: Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                new Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]:
                    def tag                = hl.tag
                    def value              = acc
                    def run[X](input: Any) = hl.run(input)
                    def complete(v: Any)   = hl.complete(v)
            case f =>
                bug(s"eval stack corruption: cannot rebuild captured frame $f into a computation")
    end renode

end Eval
