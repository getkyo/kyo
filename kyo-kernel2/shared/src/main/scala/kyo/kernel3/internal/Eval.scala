package kyo.kernel3.internal

import kyo.Frame
import kyo.bug
import kyo.kernel3.*
import kyo.kernel3.`<`.fromKyo
import kyo.kernel3.Loop
import scala.annotation.tailrec

object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try run(v, slot)
        finally Safepoint.restore(slot, saved)
    end apply

    private def run[A, S](v: A < S, slot: Safepoint.Slot): A < S =
        val stack = Stack.current()
        val base  = stack.size

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
                                        c._1
                                    case b =>
                                        stack.truncate(i)
                                        Nested.lift(b)
                    case h: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        val seg = stack.copyFrom(i + 1)
                        val out = h.run(kyo.input, o => rebuild(seg, Nested.lift(o)))
                        stack.truncate(i)
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
            try
                f match
                    case at: Arrow.AndThen[?, ?, ?, ?] =>
                        stack.push(at.b)
                        stack.push(at.a)
                        settled
                    case a: Arrow[Any, Any, Nothing] @unchecked =>
                        val step = a.step
                        step.head(settled, step.tail)
                    case h: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        h.complete(Nested.unnest[Any](settled))
                    case h: Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        h.complete(Nested.unnest[Any](settled))
                    case f =>
                        bug(s"eval stack corruption: cannot settle a value against frame $f")
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, f, stack, base)
                    throw ex

        var cur: Any < Nothing = v
        var running            = true
        try
            while running do
                cur match
                    case kyo: Kyo.Defer[?, ?, ?] =>
                        Safepoint.reset(slot)
                        stack.push(kyo.cont)
                        cur = kyo.value
                    case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                        val i = stack.find(kyo.tag.erased, base)
                        if i >= 0 then cur = dispatch(kyo, i)
                        else unhandled(kyo)
                    case kyo: Kyo.HandleCont[?, ?, ?, ?, ?, ?] =>
                        stack.push(kyo, kyo.tag.erased)
                        cur = kyo.value
                    case kyo: Kyo.HandleLoop[?, ?, ?, ?, ?, ?] =>
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
                                    rebuild(seg, c._1)
                                case b =>
                                    Nested.lift(b)
                        (r: @unchecked) match
                            case kyo: Kyo[Any, Nothing] =>
                                kyo.map(next).asInstanceOf[C < S2]
                            case r =>
                                val step = next.step
                                step.head(r.asInstanceOf[Any < S2], step.tail)
                        end match

    private def rebuild(seg: Array[Stack.Entry], v: Any < Nothing): Any < Nothing =
        @tailrec def loop(i: Int, acc: Any < Nothing): Any < Nothing =
            if i < 0 then acc
            else
                val next: Any < Nothing =
                    seg(i) match
                        case a: Arrow[Any, Any, Nothing] @unchecked =>
                            Kyo.defer(acc, a)
                        case hc: Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                            new Kyo.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]:
                                def tag                                            = hc.tag
                                def value                                          = acc
                                def run[X](input: Any, cont: Any => Any < Nothing) = hc.run(input, cont)
                                def complete(v: Any)                               = hc.complete(v)
                        case hl: Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                            new Kyo.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]:
                                def tag                = hl.tag
                                def value              = acc
                                def run[X](input: Any) = hl.run(input)
                                def complete(v: Any)   = hl.complete(v)
                        case f =>
                            bug(s"eval stack corruption: cannot rebuild captured frame $f into a computation")
                loop(i - 1, next)
        loop(seg.length - 1, v)
    end rebuild

end Eval
