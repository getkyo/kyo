package kyo.kernel

import java.lang.management.ManagementFactory
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import scala.annotation.tailrec

object HandlersProbe:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    def resumeAsk(value: Int): Handler[?, ?, ?] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = value

    def resumeAskWith(answer: => Int < Any): Handler[?, ?, ?] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = answer

    def resumeSay: Handler[?, ?, ?] =
        new Handler.Resume[Const[String], Const[Unit], Say, Any](Tag[Say]):
            def apply[X](input: String): Unit < Any = ()

    def stopAsk(value: Int): Handler[?, ?, ?] =
        new Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < (Ask & Any) = value

    // The drive: eval holding all handlers, answering suspensions by lookup.
    def drive[A](v: A < Any, handlers: Handlers): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        @tailrec def loop(v: Any < Any): Any =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    handlers.find(kyo.tag) match
                        case Maybe.Present(h: Handler.Resume[?, ?, ?, ?]) =>
                            val r      = h.asInstanceOf[Handler.Resume[Const[Any], Const[Any], Nothing, Any]]
                            val k      = kyo.asInstanceOf[Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]]
                            val answer = r[Any](k.input)
                            val step   = k.cont.step
                            loop(step.head(answer, step.tail))
                        case Maybe.Present(h: Handler.Stop[?, ?, ?, ?, ?]) =>
                            val s = h.asInstanceOf[Handler.Stop[Const[Any], Const[Any], Nothing, Any, Any]]
                            val k = kyo.asInstanceOf[Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]]
                            loop(s[Any](k.input))
                        case _ =>
                            throw new IllegalStateException(s"unhandled suspension: $kyo")
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, Any, Any]]
                    Safepoint.restore(slot, 0L)
                    val step = defer.cont.step
                    loop(step.head(defer.value, step.tail))
                case v =>
                    v
        val res =
            try loop(v.asInstanceOf[Any < Any])
            finally Safepoint.restore(slot, saved)
        Kyo.unnest(res.asInstanceOf[A < Any])
    end drive

    inline def Depth = 10000

    def towerProgram: Int < Ask =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        loop(0)
    end towerProgram

    def ceilingTower(answer: () => Int): Int =
        @tailrec def loop(i: Int): Int =
            if i > Depth then i
            else loop(i + answer())
        loop(0)
    end ceilingTower

    val threadMx = ManagementFactory.getThreadMXBean().asInstanceOf[com.sun.management.ThreadMXBean]

    def measure(name: String, iters: Int)(run: () => Int): (Double, Double) =
        val tid   = Thread.currentThread().threadId()
        var best  = Double.MaxValue
        var alloc = 0.0
        var round = 0
        while round < 5 do
            val a0  = threadMx.getThreadAllocatedBytes(tid)
            val t0  = java.lang.System.nanoTime()
            var i   = 0
            var acc = 0
            while i < iters do
                acc += run()
                i += 1
            val t1 = java.lang.System.nanoTime()
            val a1 = threadMx.getThreadAllocatedBytes(tid)
            if acc == Int.MinValue then println("impossible")
            val perOp = (t1 - t0).toDouble / iters
            if perOp < best then
                best = perOp
                alloc = (a1 - a0).toDouble / iters
            round += 1
        end while
        (best, alloc)
    end measure

    def main(args: Array[String]): Unit =
        val askHandler = resumeAsk(1)
        val one        = Handlers.empty.add(askHandler)
        val four       = Handlers.empty.add(resumeSay).add(stopAsk(-1)).add(resumeSay).add(askHandler)

        val crossed: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
        val crossedResult              = drive(crossed.asInstanceOf[Int < Any], Handlers.empty.add(resumeSay).add(resumeAsk(41)))
        println(s"SEMANTICS crossed-trailing-transform = $crossedResult (expect 42)")

        val effectfulAnswer = Handlers.empty.add(resumeSay).add(resumeAskWith(say("k").map(_ => 41).asInstanceOf[Int < Any]))
        val completeness    = drive(ask.map(_ + 1).asInstanceOf[Int < Any], effectfulAnswer)
        println(s"SEMANTICS clause-raises-outer-handled = $completeness (expect 42)")

        var reached = false
        val stopProg: Int < Ask = ask.map { a =>
            reached = true; a + 1
        }
        val stopResult = drive(stopProg.asInstanceOf[Int < Any], Handlers.empty.add(stopAsk(-1)))
        println(s"SEMANTICS stop-discards = $stopResult reached=$reached (expect -1 false)")

        val deep = drive(towerProgram.asInstanceOf[Int < Any], one)
        println(s"SEMANTICS deep-tower = $deep (expect ${Depth + 1})")

        var w = 0
        while w < 30 do
            discardInt(ArrowEffect.resume(Tag[Ask], towerProgram)([X] => _ => 1).eval)
            discardInt(drive(towerProgram.asInstanceOf[Int < Any], one))
            discardInt(drive(towerProgram.asInstanceOf[Int < Any], four))
            discardInt(ceilingTower(() => 1))
            w += 1
        end while

        val iters    = 200
        val (t1, a1) = measure("trampoline", iters)(() => ArrowEffect.resume(Tag[Ask], towerProgram)([X] => _ => 1).eval)
        val (t2, a2) = measure("drive-lookup-depth1", iters)(() => drive(towerProgram.asInstanceOf[Int < Any], one))
        val (t3, a3) = measure("drive-lookup-depth4", iters)(() => drive(towerProgram.asInstanceOf[Int < Any], four))
        val (t4, a4) = measure("ceiling-direct-call", iters)(() => ceilingTower(() => 1))

        def row(name: String, t: Double, a: Double): Unit =
            println(f"$name%-22s ${t / 1000}%10.1f us/op ${a}%12.0f B/op ${t / Depth}%8.2f ns/susp ${a / Depth}%8.2f B/susp")

        println(s"PERF (Depth=$Depth suspensions per op, best of 5 rounds of $iters)")
        row("trampoline", t1, a1)
        row("drive-lookup-depth1", t2, a2)
        row("drive-lookup-depth4", t3, a3)
        row("ceiling-direct-call", t4, a4)
    end main

    def discardInt(i: Int): Unit = if i == Int.MinValue then println("impossible")

end HandlersProbe

// v4: executable spec for the escape mechanisms Eval does not drive yet
// (stop, capture, loop state), over real Handled nodes and the real
// Handlers collection. Resume-region semantics live in EvalTest against
// the real Eval; this probe only models what lands in the next steps.
object HandlersProbe2:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    type ESuspend = Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type EHandled = Kyo.Handled[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type EResume  = Handler.Resume[Const[Any], Const[Any], Nothing, Any]
    type EStop    = Handler.Stop[Const[Any], Const[Any], Nothing, Any, Any]
    type ECont    = Handler.Cont[Const[Any], Const[Any], Nothing, Any, Any]
    type ELoop    = Handler.Loop[Const[Any], Const[Any], Nothing, Any, Any, Any]

    // where a loop region's initial state comes from is an open kernel
    // decision; the probe carries it on the handler instance
    trait Seeded:
        def state0: Any

    // escapes climb by ordinary returns, addressed by handler identity
    final class PHalt(val owner: Handler[?, ?, ?], val input: Any)
    final class PPark(val owner: Handler[?, ?, ?], val input: Any, val resume: Any => Any)

    def walkSusp(k: ESuspend, o: Any < Any): Any < Any =
        val step = k.cont.step
        step.head(o, step.tail)

    def walkExit(cont: Arrow[Any, Any, Any], v: Any < Any): Any < Any =
        val step = cont.step
        step.head(v, step.tail)

    def seed(h: Handler[?, ?, ?]): Any =
        h match
            case s: Seeded => s.state0
            case _         => null

    def resumeThen(park: PPark, andThen: Any => Any): Any => Any =
        o =>
            park.resume(o) match
                case e: PHalt => e
                case e: PPark => new PPark(e.owner, e.input, resumeThen(e, andThen))
                case settled  => andThen(settled)

    // settled value, PHalt, or PPark
    def run(v0: Any < Any, handlers: Handlers): Any =
        val slot = Safepoint.get()
        @tailrec def loop(v: Any < Any): Any =
            (v: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] =>
                    val k = kyo.asInstanceOf[EHandled]
                    reenter(k.handler, k.cont, handlers, seed(k.handler))(run(k.value, handlers.add(k.handler)))
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    val k   = kyo.asInstanceOf[ESuspend]
                    val idx = handlers.indexOf(k.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    else
                        handlers(idx) match
                            case h: Handler.Resume[?, ?, ?, ?] =>
                                val answer = h.asInstanceOf[EResume][Any](k.input)
                                (answer: Any) match
                                    case pending: Kyo[?, ?] =>
                                        run(answer, handlers.take(idx + 1)) match
                                            case e: PHalt => e
                                            case e: PPark => new PPark(
                                                    e.owner,
                                                    e.input,
                                                    resumeThen(e, s => run(walkSusp(k, `<`.lift[Any, Any](s)), handlers))
                                                )
                                            case settled => loop(walkSusp(k, `<`.lift[Any, Any](settled)))
                                    case _ =>
                                        loop(walkSusp(k, answer))
                                end match
                            case h: Handler.Stop[?, ?, ?, ?, ?] =>
                                new PHalt(h, k.input)
                            case h =>
                                new PPark(h, k.input, o => run(walkSusp(k, `<`.lift[Any, Any](o)), handlers))
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, Any, Any]]
                    Safepoint.restore(slot, 0L)
                    val step = defer.cont.step
                    loop(step.head(defer.value, step.tail))
                case v =>
                    v
        loop(v0)
    end run

    // process a region's result: answer escapes addressed to its handler,
    // wrap itself around escapes climbing past, walk the exit on settle
    def reenter(handler: Handler[?, ?, ?], cont: Arrow[?, ?, ?], outer: Handlers, state0: Any)(result0: Any): Any =
        var state  = state0
        var result = result0
        while true do
            result match
                case halt: PHalt if halt.owner eq handler =>
                    result = run(halt.owner.asInstanceOf[EStop][Any](halt.input), outer.add(handler))
                case park: PPark if park.owner eq handler =>
                    handler match
                        case hc: Handler.Cont[?, ?, ?, ?, ?] =>
                            val k: Any => Any < Any = o => `<`.lift[Any, Any](park.resume(o))
                            result = run(hc.asInstanceOf[ECont][Any](park.input, k), outer.add(handler))
                        case hl: Handler.Loop[?, ?, ?, ?, ?, ?] =>
                            val k: Any => Any < Any = o => `<`.lift[Any, Any](park.resume(o))
                            val (st2, v2)           = hl.asInstanceOf[ELoop][Any](park.input, state, k)
                            state = st2
                            result = run(v2, outer.add(handler))
                        case other =>
                            throw new IllegalStateException(s"park addressed to $other")
                case e: PHalt =>
                    return e
                case park: PPark =>
                    val snapshot = state
                    val prior    = park.resume
                    return new PPark(park.owner, park.input, o => reenter(handler, cont, outer, snapshot)(prior(o)))
                case settled =>
                    val out =
                        handler match
                            case _: Handler.Loop[?, ?, ?, ?, ?, ?] => (state, settled)
                            case _                                 => settled
                    return run(walkExit(cont.asInstanceOf[Arrow[Any, Any, Any]], `<`.lift[Any, Any](out)), outer)
        end while
        throw new IllegalStateException("unreachable")
    end reenter

    def drive[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val out =
            try run(v.asInstanceOf[Any < Any], Handlers.empty)
            finally Safepoint.restore(slot, saved)
        out match
            case e: PHalt => throw new IllegalStateException(s"unowned halt: $e")
            case e: PPark => throw new IllegalStateException(s"unowned park: $e")
            case v =>
                Kyo.unnest(v.asInstanceOf[A < Any])
        end match
    end drive

    def resumeAsk(value: Int): Handler.Resume[Const[Unit], Const[Int], Ask, Any] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = value

    def resumeSayRecord(
        name: String,
        log: scala.collection.mutable.ListBuffer[String]
    ): Handler.Resume[Const[String], Const[Unit], Say, Any] =
        new Handler.Resume[Const[String], Const[Unit], Say, Any](Tag[Say]):
            def apply[X](input: String): Unit < Any =
                log += name
                ()

    def main(args: Array[String]): Unit =
        // a resume clause parks to an outer capture region; the site remainder survives
        val contSay =
            new Handler.Cont[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
                def apply[X](input: String, cont: Unit => Int < (Say & Any)): Int < (Say & Any) = cont(())
        val askClauseSays =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Say](Tag[Ask]):
                def apply[X](input: Unit): Int < Say = say("c").map(_ => 41)
        val p2inner = new Kyo.Handled(ask.map(_ + 1), askClauseSays, Arrow[Int])
        val p2      = drive(new Kyo.Handled(p2inner, contSay, Arrow[Int]): Int < Any)
        println(s"P2 clause-parks = $p2 (expect 42)")

        // capture is multi-shot and re-enters crossed regions per replay
        var crossedExits = 0
        val contAsk =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit, cont: Int => Int < (Ask & Any)): Int < (Ask & Any) =
                    cont(10).map(a => cont(20).map(b => a + b))
        val log3                         = scala.collection.mutable.ListBuffer[String]()
        val p3program: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
        val p3sayNode =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](p3program, resumeSayRecord("s", log3), Arrow[Int])
        val p3say = (p3sayNode: Int < Ask).map { v =>
            crossedExits += 1; v
        }
        val p3 = drive(new Kyo.Handled(p3say, contAsk, Arrow[Int]): Int < Any)
        println(s"P3 multi-shot = $p3 crossedExits=$crossedExits says=${log3.size} (expect 32 2 1)")

        // a crossed loop region forks its state per replay
        val loopSay =
            new Handler.Loop[Const[String], Const[Unit], Say, Int, Any, Int](Tag[Say]) with Seeded:
                def state0 = 0
                def apply[X](input: String, state: Int, cont: Unit => Int < (Say & Any)): (Int, Int < (Say & Any)) =
                    (state + 1, cont(()))
        val pairAsk =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Any, Any](Tag[Ask]):
                def apply[X](input: Unit, cont: Int => Any < (Ask & Any)): Any < (Ask & Any) =
                    cont(10).map(a => cont(20).map(b => (a, b)))
        val p4program: Int < (Ask & Say) = say("a").map(_ => ask).map(x => say("b").map(_ => x)).asInstanceOf[Int < (Ask & Say)]
        val p4say =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](p4program, loopSay, Arrow[Int])
        val p4 = drive(new Kyo.Handled(p4say, pairAsk, Arrow[Any]): Any < Any)
        println(s"P4 state-forks = $p4 (expect ((2,10),(2,20)))")

        // loop threads state across answers and pairs it at exit
        val loopAsk =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any, Int](Tag[Ask]) with Seeded:
                def state0 = 0
                def apply[X](input: Unit, state: Int, cont: Int => Int < (Ask & Any)): (Int, Int < (Ask & Any)) =
                    (state + 1, cont(state))
        val p5program: Int < Ask = ask.map(a => ask.map(b => a + b))
        val p5                   = drive(new Kyo.Handled(p5program, loopAsk, Arrow[Int]): Any < Any)
        println(s"P5 loop-state = $p5 (expect (2,1))")

        // region cycle cost: real eval on a resume region vs the trampoline
        def onceEval(): Int =
            (new Kyo.Handled(ask.map(_ + 1), resumeAsk(41), Arrow[Int]): Int < Any).eval
        def onceTrampoline(): Int =
            ArrowEffect.resume(Tag[Ask], ask.map(_ + 1))([X] => _ => 41).eval

        var w = 0
        while w < 50000 do
            HandlersProbe.discardInt(onceEval())
            HandlersProbe.discardInt(onceTrampoline())
            w += 1
        end while
        val iters    = 1000000
        val (te, ae) = HandlersProbe.measure("eval-region-cycle", iters)(() => onceEval())
        val (tt, at) = HandlersProbe.measure("trampoline-cycle", iters)(() => onceTrampoline())
        println(f"P6 region cycle: eval ${te}%6.1f ns ${ae}%5.0f B  trampoline ${tt}%6.1f ns ${at}%5.0f B per handler call")
    end main

end HandlersProbe2
