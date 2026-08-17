package kyo.kernel.internal

import kyo.Arrow
import kyo.Arrow.*
import kyo.bug
import kyo.kernel.<
import kyo.kernel.Loop
import kyo.kernel.Nested

// TODO Eval should be private[kernel]. The external apis are <.eval/evalNow and ArrowEffect.*
object Eval:

    private val noEntries = kyo.Span.empty[Arrow[?, ?, ?]]
    private val noRefs    = kyo.Span.empty[AnyRef]

    def apply[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try Nested.unnest[A](loop(v, armed = false, neverStop))
        finally Safepoint.restore(slot, saved)
    end apply

    private[kyo] def partial[A, S](v: A < S): A < S =
        partial(v, neverStop)

    private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            Safepoint.arm(slot)
            try loop(v.asInstanceOf[A < Any], armed = true, () => Safepoint.consumeStopped(slot) || stop()).asInstanceOf[A < S]
            finally Safepoint.restore(slot, saved)
        end if
    end partial

    private val neverStop: () => Boolean = () => false

    private def loop[A](v: A < Any, armed: Boolean, stop: () => Boolean): Any =
        val stack = Stack.current()
        val base  = stack.size

        def dump(): Arrow[Any, Any, Any] =
            val top = stack.size
            var i   = top
            while i > base && !stack.marked(i - 1) do i -= 1
            if i == top then Arrow[Any]
            else
                var acc = stack(i)
                var j   = i + 1
                while j < top do
                    acc = stack(j).chain(acc)
                    j += 1
                stack.truncate(i)
                acc
            end if
        end dump

        def outcome(
            resume: Arrow[Any, Any, Any],
            h: Handle[Nothing, Any, Any, Any, Any],
            i: Int
        ): Arrow[Any, Any, Any] =
            val top = stack.size
            var j   = i + 1
            while j < top && !stack.marked(j) do j += 1
            val marked  = j < top
            val entries = if marked then stack.copyEntries(i + 1) else noEntries
            val tags    = if marked then stack.copyTags(i + 1) else noRefs
            val states  = if marked then stack.copyStates(i + 1) else noRefs
            val body =
                if marked then
                    stack.truncate(i)
                    resume
                else
                    var k: Arrow[Any, Any, Any] = Arrow[Any]
                    var m                       = i + 1
                    while m < top do
                        k = stack(m).chain(k)
                        m += 1
                    stack.truncate(i)
                    resume.chain(k)
            def region(regionHandler: Handler[Nothing, Any, Any, Any], payload: Any): Arrow[Any, Any, Any] =
                new Handle[Nothing, Any, Any, Any, Any]:
                    def v       = Arrow.Eval(entries, tags, states, Identity(payload.asInstanceOf[Any < Any], body))
                    def handler = regionHandler
                    def cont    = Arrow[Any]
            new Transform[Any, Any, Any]:
                def frame = kyo.Frame.internal
                def apply[C2, S2](v: Any < S2, next: Arrow[Any, C2, S2]): C2 < S2 =
                    v match
                        case p: Arrow[Any, Any, S2] @unchecked =>
                            Chain(p, this.chain(next))
                        case c: Loop.Continue[?] =>
                            Identity(region(h.handler, c._1).asInstanceOf[Any < S2], next)
                        case c: Loop.Continue2[?, ?] =>
                            h.handler match
                                case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                                    val st = c._1
                                    Identity(
                                        region(
                                            new Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]:
                                                def tag                         = hls.tag
                                                def initialState                = st
                                                def run[C](s2: Any, input: Any) = hls.run[C](s2, input)
                                                def complete(s2: Any, a: Any)   = hls.complete(s2, a)
                                            ,
                                            c._2
                                        ).asInstanceOf[Any < S2],
                                        next
                                    )
                                case other =>
                                    bug(s"stateful answer for a stateless region: $other")
                        case done =>
                            Identity(done.asInstanceOf[Any < S2], next)
            end new
        end outcome

        def reify(v: Any): Any =
            if stack.size == base then v
            else
                val entries = stack.copyEntries(base)
                val tags    = stack.copyTags(base)
                val states  = stack.copyStates(base)
                stack.truncate(base)
                Arrow.Eval(entries, tags, states, v.asInstanceOf[Any < Any])
        end reify

        var cur: Any = v
        var running  = true

        inline def dispatchInline(s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any], whole: Arrow[Any, Any, Any]): Any =
            try
                // TODO let's encapsulate this access in Stack. It should provide more high level apis
                val i = stack.find(s.tag.erased, base)
                if i < 0 then bug(s"unhandled suspension: $s")
                val h = stack(i).asInstanceOf[Handle[Nothing, Any, Any, Any, Any]]
                h.handler match
                    case hc: Handler.HandleCont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        val top = stack.size
                        var j   = i + 1
                        while j < top && !stack.marked(j) do j += 1
                        if j == top then
                            if i + 1 == top then hc.run(s.input, whole)
                            else
                                // TODO this also looks like a stack operation
                                var k: Arrow[Any, Any, Any] = Arrow[Any]
                                var m                       = i + 1
                                while m < top do
                                    k = stack(m).chain(k)
                                    m += 1
                                stack.truncate(i + 1)
                                hc.run(s.input, whole.chain(k))
                        else
                            val entries = stack.copyEntries(i + 1)
                            val tags    = stack.copyTags(i + 1)
                            val states  = stack.copyStates(i + 1)
                            stack.truncate(i + 1)
                            hc.run(s.input, o => Arrow.Eval(entries, tags, states, whole(o)))
                        end if
                    case hl: Handler.HandleLoop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                        hl.run(s.input) match
                            case out: Arrow[Any, Any, Any] @unchecked =>
                                Chain(out, outcome(whole, h, i))
                            case c: Loop.Continue[?] =>
                                c._1 match
                                    case p: Arrow[Any, Any, Any] @unchecked => Chain(p, whole)
                                    case o                                  => whole(Nested.unnest[Any](o))
                            case done =>
                                stack.truncate(i)
                                done
                    case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                        hls.run(stack.state(i), s.input) match
                            case out: Arrow[Any, Any, Any] @unchecked =>
                                Chain(out, outcome(whole, h, i))
                            case c: Loop.Continue2[?, ?] =>
                                stack.setState(i, c._1)
                                c._2 match
                                    case p: Arrow[Any, Any, Any] @unchecked => Chain(p, whole)
                                    case o                                  => whole(Nested.unnest[Any](o))
                            case done =>
                                stack.truncate(i)
                                done
                end match
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, s, stack, base)
                    throw ex
            end try
        end dispatchInline

        def dispatch(s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any], whole: Arrow[Any, Any, Any]): Any =
            dispatchInline(s, whole)

        try
            // TODO rewrite while loops to @tailrec def loop. Avoid vars if possible
            while running do
                if armed && stop() then
                    cur = reify(cur)
                    running = false
                else
                    cur match
                        case c: Chain[?, ?, ?, ?] =>
                            stack.push(c.b)
                            cur = c.a
                        case b: Bind[?, ?, ?] =>
                            stack.push(b.cont)
                            cur = b.value
                        case s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                            cur = dispatchInline(s, s.asInstanceOf[Arrow[Any, Any, Any]])
                        case m: SuspendWith[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any] @unchecked =>
                            cur = dispatch(m.susp, m.asInstanceOf[Arrow[Any, Any, Any]])
                        case h: Handle[Nothing, Any, Any, Any, Any] @unchecked =>
                            stack.push(h.cont)
                            h.handler match
                                case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                                    stack.push(h, h.handler.tag.erased, hls.initialState)
                                case _ =>
                                    stack.push(h, h.handler.tag.erased)
                            end match
                            cur = h.v
                        case e: Arrow.Eval[?, ?, ?] =>
                            stack.pushAll(e.entries, e.tags, e.states)
                            cur = e.value
                        case a: Arrow[Any, Any, Any] @unchecked =>
                            val s0   = a.step
                            val next = s0.tail.chain(dump())
                            try cur = s0.head((), next)
                            catch
                                case ex: Throwable =>
                                    EffectTrace.attach(ex, a, next, stack, base)
                                    throw ex
                            end try
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
                                        try
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
                                        catch
                                            case ex: Throwable =>
                                                EffectTrace.attach(ex, h, stack, base)
                                                throw ex
                                        end try
                                    case s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                                        try cur = s(Nested.unnest[Any](settled))
                                        catch
                                            case ex: Throwable =>
                                                EffectTrace.attach(ex, s, stack, base)
                                                throw ex
                                        end try
                                    case m: SuspendWith[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any] @unchecked =>
                                        try cur = m(Nested.unnest[Any](settled))
                                        catch
                                            case ex: Throwable =>
                                                EffectTrace.attach(ex, m, stack, base)
                                                throw ex
                                        end try
                                    case d: Defer[?, ?, ?] =>
                                        cur = d
                                    case a =>
                                        val s    = a.step
                                        val next = s.tail.chain(dump())
                                        try cur = s.head(settled.asInstanceOf[Any < Any], next)
                                        catch
                                            case ex: Throwable =>
                                                EffectTrace.attach(ex, a, next, stack, base)
                                                throw ex
                                        end try
                                end match
        catch
            case ex: Throwable =>
                EffectTrace.splice(ex)
                throw ex
        finally stack.truncate(base)
        end try
        cur
    end loop
end Eval
