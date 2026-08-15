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
            while i > base && stack(i - 1).isInstanceOf[Transform[?, ?, ?]] do i -= 1
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
                        if s.cont ne Identity then stack.push(s.cont)
                        val i = stack.find(s.tag.erased, base)
                        if i < 0 then bug(s"unhandled suspension: $s")
                        stack(i).asInstanceOf[Handle[Nothing, Any, Any, Any, Any]].handler match
                            case hc: Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                var k: Arrow[Any, Any, Any] = Arrow[Any]
                                var j                       = i + 1
                                while j < stack.size do
                                    k = stack(j).asInstanceOf[Arrow[Any, Any, Any]].chain(k)
                                    j += 1
                                stack.truncate(i + 1)
                                val cont = k
                                cur = hc.run(s.input, o => Bind(o, cont))
                            case hl: Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                hl.run(s.input) match
                                    case out: Arrow[?, ?, ?] => ???
                                    case c: Loop.Continue[?] => cur = c._1
                                    case done =>
                                        stack.truncate(i)
                                        cur = done
                            case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                                hls.run(stack.state(i), s.input) match
                                    case out: Arrow[?, ?, ?] => ???
                                    case c: Loop.Continue2[?, ?] =>
                                        stack.setState(i, c._1.asInstanceOf[AnyRef])
                                        cur = c._2
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
