package kyo.kernel.proto

import kyo.Loop
import kyo.bug
import kyo.kernel.proto.Arrow.*

object Eval:

    def apply[A](v: A < Any): A =
        val stack = Stack.current()
        val base  = stack.size

        def dump(): Arrow[Any, Any, Any] =
            val top = stack.size
            var i   = top
            while i > base && !stack.marked(i - 1) do i -= 1
            if i == top then Arrow[Any]
            else
                var acc = stack(i).asInstanceOf[Arrow[Any, Any, Any]]
                var j   = i + 1
                while j < top do
                    acc = stack(j).asInstanceOf[Arrow[Any, Any, Any]].chain(acc)
                    j += 1
                stack.truncate(i)
                acc
            end if
        end dump

        def resume(s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]): Arrow[Any, Any, Any] =
            new Transform[Any, Any, Any]:
                def frame = kyo.Frame.internal
                def apply[C2, S2](v: Any < S2, next: Arrow[Any, C2, S2]): C2 < S2 =
                    v match
                        case p: Arrow[Any, Any, S2] @unchecked =>
                            Chain(p, this.chain(next))
                        case o =>
                            Identity(s(o), next)

        var cur: Any = v
        var running  = true
        try
            while running do
                cur match
                    case c: Chain[?, ?, ?, ?] =>
                        stack.push(c.b)
                        cur = c.a
                    case b: Bind[?, ?, ?] =>
                        stack.push(b.cont)
                        cur = b.value
                    case s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        val i = stack.find(s.tag.erased, base)
                        if i < 0 then bug(s"unhandled suspension: $s")
                        stack(i).asInstanceOf[Handle[Nothing, Any, Any, Any, Any]].handler match
                            case hc: Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                val top = stack.size
                                var j   = i + 1
                                while j < top && !stack.marked(j) do j += 1
                                if j == top then
                                    if i + 1 == top then cur = hc.run(s.input, s)
                                    else
                                        var k: Arrow[Any, Any, Any] = Arrow[Any]
                                        var m                       = i + 1
                                        while m < top do
                                            k = stack(m).asInstanceOf[Arrow[Any, Any, Any]].chain(k)
                                            m += 1
                                        stack.truncate(i + 1)
                                        val cont = k
                                        cur = hc.run(s.input, o => Identity(s(o), cont))
                                else
                                    val entries = stack.copyEntries(i + 1)
                                    val tags    = stack.copyTags(i + 1)
                                    val states  = stack.copyStates(i + 1)
                                    stack.truncate(i + 1)
                                    cur = hc.run(s.input, o => Arrow.Eval(entries, tags, states, s(o)))
                                end if
                            case hl: Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                hl.run(s.input) match
                                    case out: Arrow[?, ?, ?] => ???
                                    case c: Loop.Continue[?] =>
                                        c._1 match
                                            case p: Arrow[Any, Any, Any] @unchecked =>
                                                stack.push(resume(s))
                                                cur = p
                                            case o =>
                                                cur = s(o)
                                    case done =>
                                        stack.truncate(i)
                                        cur = done
                            case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                                hls.run(stack.state(i), s.input) match
                                    case out: Arrow[?, ?, ?] => ???
                                    case c: Loop.Continue2[?, ?] =>
                                        stack.setState(i, c._1.asInstanceOf[AnyRef])
                                        c._2 match
                                            case p: Arrow[Any, Any, Any] @unchecked =>
                                                stack.push(resume(s))
                                                cur = p
                                            case o =>
                                                cur = s(o)
                                        end match
                                    case done =>
                                        stack.truncate(i)
                                        cur = done
                        end match
                    case h: Handle[Nothing, Any, Any, Any, Any] @unchecked =>
                        stack.push(h.cont)
                        h.handler match
                            case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                                stack.push(h, h.handler.tag.erased, hls.initialState.asInstanceOf[AnyRef])
                            case _ =>
                                stack.push(h, h.handler.tag.erased)
                        end match
                        cur = h.v
                    case e: Arrow.Eval[?, ?, ?] =>
                        stack.pushAll(e.entries, e.tags, e.states)
                        cur = e.value
                    case a: Arrow[?, ?, ?] =>
                        val s = a.asInstanceOf[Arrow[Any, Any, Any]].step
                        cur = s.head(().asInstanceOf[Any < Any], s.tail.chain(dump()))
                    case settled =>
                        if stack.size == base then running = false
                        else
                            val marked = stack.marked(stack.size - 1)
                            val st     = stack.state(stack.size - 1)
                            stack.pop() match
                                case c: Chain[?, ?, ?, ?] =>
                                    stack.push(c.b)
                                    stack.push(c.a)
                                case h: Handle[Nothing, Any, Any, Any, Any] @unchecked if marked =>
                                    h.handler match
                                        case hc: Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                            cur = hc.complete(settled)
                                        case hl: Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                            cur = hl.complete(settled)
                                        case hls: Handler.HandleLoopState[
                                                [X] =>> Any,
                                                [X] =>> Any,
                                                Nothing,
                                                Any,
                                                Any,
                                                Any,
                                                Any
                                            ] @unchecked =>
                                            cur = hls.complete(st, settled)
                                case d: Defer[?, ?, ?] =>
                                    cur = d
                                case a =>
                                    val s = a.asInstanceOf[Arrow[Any, Any, Any]].step
                                    cur = s.head(settled.asInstanceOf[Any < Any], s.tail.chain(dump()))
                            end match
        finally stack.truncate(base)
        end try
        cur.asInstanceOf[A]
    end apply
end Eval
