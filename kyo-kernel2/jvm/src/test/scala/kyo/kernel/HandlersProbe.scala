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

// v3, compositional: regions evaluate by recursion, scope is an argument,
// stop and capture climb as return values, each crossed region wraps itself.
object HandlersProbe2:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    type ESuspend = Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type EResume  = Handler.Resume[Const[Any], Const[Any], Nothing, Any]
    type EStop    = Handler.Stop[Const[Any], Const[Any], Nothing, Any, Any]
    type ECont    = Handler.Cont[Const[Any], Const[Any], Nothing, Any, Any]
    type ELoop    = Handler.Loop[Const[Any], Const[Any], Nothing, Any, Any, Any]

    // stand-in for the future Kyo.Handled
    final class PRegion(val handler: Handler[?, ?, ?], val exit: Any => Any, val state0: Any)

    // escapes climb by ordinary returns, addressed by handler identity
    final class PHalt(val owner: Handler[?, ?, ?], val input: Any)
    final class PPark(val owner: Handler[?, ?, ?], val input: Any, val resume: Any => Any)

    def walkCont(k: ESuspend, o: Any < Any): Any < Any =
        val step = k.cont.step
        step.head(o, step.tail)

    def matches(k: ESuspend, h: Handler[?, ?, ?]): Boolean =
        k.tag.erased <:< h.tag.asInstanceOf[Tag[Nothing]].erased

    def indexOf(handlers: kyo.Chunk[Handler[?, ?, ?]], k: ESuspend): Int =
        @tailrec def scan(i: Int): Int =
            if i < 0 then -1
            else if matches(k, handlers(i)) then i
            else scan(i - 1)
        scan(handlers.size - 1)
    end indexOf

    // run a computation under the passed handlers; returns settled, PHalt, or PPark
    def run(v0: Any < Any, handlers: kyo.Chunk[Handler[?, ?, ?]]): Any =
        val slot = Safepoint.get()
        @tailrec def loop(v: Any < Any): Any =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    val k   = kyo.asInstanceOf[ESuspend]
                    val idx = indexOf(handlers, k)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    handlers(idx) match
                        case h: Handler.Resume[?, ?, ?, ?] =>
                            h.asInstanceOf[EResume][Any](k.input) match
                                case pending: Kyo[?, ?] =>
                                    // the clause runs outside its own region: recursion under the prefix
                                    run(pending.asInstanceOf[Any < Any], handlers.take(idx + 1)) match
                                        case e: PHalt => e
                                        case e: PPark =>
                                            // the clause parked: save the site remainder around its resume
                                            new PPark(
                                                e.owner,
                                                e.input,
                                                o => resumeThen(e, o, s => run(walkCont(k, `<`.lift[Any, Any](s)), handlers))
                                            )
                                        case settled =>
                                            loop(walkCont(k, `<`.lift[Any, Any](settled)))
                                case settled =>
                                    loop(walkCont(k, settled.asInstanceOf[Any < Any]))
                        case h: Handler.Stop[?, ?, ?, ?, ?] =>
                            new PHalt(h, k.input)
                        case h =>
                            // Handle and Loop park: the continuation is the site remainder under
                            // the site handlers; crossed regions wrap as the park climbs
                            new PPark(h, k.input, o => run(walkCont(k, `<`.lift[Any, Any](o)), handlers))
                    end match
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, Any, Any]]
                    Safepoint.restore(slot, 0L)
                    val step = defer.cont.step
                    loop(step.head(defer.value, step.tail))
                case v =>
                    Kyo.unnest[Any, Any](v.asInstanceOf[Any < Any])
        loop(v0)
    end run

    def resumeThen(park: PPark, o: Any, andThen: Any => Any): Any =
        park.resume(o) match
            case e: PHalt => e
            case e: PPark => new PPark(e.owner, e.input, o2 => resumeThen(e, o2, andThen))
            case settled  => andThen(settled)

    // one region: drive under handlers plus self, process escapes addressed to self,
    // wrap self around escapes that climb past
    def region(r: PRegion, handlers: kyo.Chunk[Handler[?, ?, ?]], compute: kyo.Chunk[Handler[?, ?, ?]] => Any): Any =
        process(r, r.state0, handlers, compute(handlers.append(r.handler)))

    def process(r: PRegion, state0: Any, handlers: kyo.Chunk[Handler[?, ?, ?]], result0: Any): Any =
        var state  = state0
        var result = result0
        while true do
            result match
                case halt: PHalt if halt.owner eq r.handler =>
                    result = run(halt.owner.asInstanceOf[EStop][Any](halt.input), handlers.append(r.handler))
                case park: PPark if park.owner eq r.handler =>
                    r.handler match
                        case hh: Handler.Cont[?, ?, ?, ?, ?] =>
                            val cont: Any => Any < Any = o => `<`.lift[Any, Any](park.resume(o))
                            result = run(hh.asInstanceOf[ECont][Any](park.input, cont), handlers.append(r.handler))
                        case hl: Handler.Loop[?, ?, ?, ?, ?, ?] =>
                            val cont: Any => Any < Any = o => `<`.lift[Any, Any](park.resume(o))
                            val (st2, v2)              = hl.asInstanceOf[ELoop][Any](park.input, state, cont)
                            state = st2
                            result = run(v2, handlers.append(r.handler))
                        case other =>
                            throw new IllegalStateException(s"park addressed to $other")
                case e: PHalt =>
                    return e
                case park: PPark =>
                    val snapshot = state
                    val prior    = park.resume
                    return new PPark(park.owner, park.input, o => process(r, snapshot, handlers, prior(o)))
                case settled =>
                    r.handler match
                        case _: Handler.Loop[?, ?, ?, ?, ?, ?] => return r.exit((state, settled))
                        case _                                 => return r.exit(settled)
        end while
        throw new IllegalStateException("unreachable")
    end process

    def driveAll(regions: Array[PRegion], program: Any < Any): Any =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        def go(i: Int, handlers: kyo.Chunk[Handler[?, ?, ?]]): Any =
            if i == regions.length then run(program, handlers)
            else region(regions(i), handlers, hs => go(i + 1, hs))
        val out =
            try go(0, kyo.Chunk.empty)
            finally Safepoint.restore(slot, saved)
        out match
            case e: PHalt => throw new IllegalStateException(s"unowned halt: $e")
            case e: PPark => throw new IllegalStateException(s"unowned park: $e")
            case v        => v
        end match
    end driveAll

    def resumeAsk(value: Int): Handler[?, ?, ?] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = value

    def resumeSayRecord(name: String, log: scala.collection.mutable.ListBuffer[String]): Handler[?, ?, ?] =
        new Handler.Resume[Const[String], Const[Unit], Say, Any](Tag[Say]):
            def apply[X](input: String): Unit < Any =
                log += name
                ()

    def reg(h: Handler[?, ?, ?], exit: Any => Any = identity, state0: Any = null): PRegion =
        new PRegion(h, exit, state0)

    def main(args: Array[String]): Unit =
        // P2: nested entry and exit ordering, inner exit first
        val log2 = scala.collection.mutable.ListBuffer[String]()
        val p2 = driveAll(
            Array(
                reg(resumeSayRecord("say", log2), v => v.asInstanceOf[Int] + 1000),
                reg(resumeAsk(41), v => v.asInstanceOf[Int] * 10)
            ),
            ask.map(_ + 1).asInstanceOf[Any < Any]
        )
        println(s"P2 nested-exits = $p2 (expect 1420)")

        // P2b: innermost handler of a tag wins
        val p2b = driveAll(
            Array(reg(resumeAsk(41)), reg(resumeAsk(1))),
            ask.map(_ + 1).asInstanceOf[Any < Any]
        )
        println(s"P2b innermost-wins = $p2b (expect 2)")

        // P3: a pending resume answer resolves OUTSIDE its own region; main ops resolve innermost
        val log3 = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays: Handler[?, ?, ?] =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
                def apply[X](input: Unit): Int < Any = say("c").map(_ => 41).asInstanceOf[Int < Any]
        val p3 = driveAll(
            Array(
                reg(resumeSayRecord("outer", log3)),
                reg(askClauseSays),
                reg(resumeSayRecord("inner", log3))
            ),
            say("m").map(_ => ask).map(_ + 1).asInstanceOf[Any < Any]
        )
        println(s"P3 clause-scope = $p3 log=${log3.toList} (expect 42 List(inner, outer))")

        // P3b: a clause parks to an outer capture handler; the site remainder survives
        val handleSay: Handler[?, ?, ?] =
            new Handler.Cont[Const[String], Const[Unit], Say, Any, Any](Tag[Say]):
                def apply[X](input: String, cont: Unit => Any < (Say & Any)): Any < (Say & Any) = cont(())
        val p3b = driveAll(
            Array(reg(handleSay), reg(askClauseSays)),
            ask.map(_ + 1).asInstanceOf[Any < Any]
        )
        println(s"P3b clause-parks = $p3b (expect 42)")

        // P4: handle capture, multi-shot, crossed region re-entered per replay
        var crossedExits = 0
        val handleAsk: Handler[?, ?, ?] =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Any, Any](Tag[Ask]):
                def apply[X](input: Unit, cont: Int => Any < (Ask & Any)): Any < (Ask & Any) =
                    cont(10).map(a => cont(20).map(b => a.asInstanceOf[Int] + b.asInstanceOf[Int]))
        val log4 = scala.collection.mutable.ListBuffer[String]()
        val p4 = driveAll(
            Array(
                reg(handleAsk),
                reg(
                    resumeSayRecord("s", log4),
                    v =>
                        crossedExits += 1; v
                )
            ),
            say("x").map(_ => ask).map(_ + 1).asInstanceOf[Any < Any]
        )
        println(s"P4 multi-shot = $p4 crossedExits=$crossedExits says=${log4.size} (expect 32 2 1)")

        // P4b: a crossed loop region forks its state per replay
        val loopSay: Handler[?, ?, ?] =
            new Handler.Loop[Const[String], Const[Unit], Say, Any, Any, Any](Tag[Say]):
                def apply[X](input: String, state: Any, cont: Unit => Any < (Say & Any)): (Any, Any < (Say & Any)) =
                    (state.asInstanceOf[Int] + 1, cont(()))
        val pairAsk: Handler[?, ?, ?] =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Any, Any](Tag[Ask]):
                def apply[X](input: Unit, cont: Int => Any < (Ask & Any)): Any < (Ask & Any) =
                    cont(10).map(a => cont(20).map(b => (a, b)))
        val p4b = driveAll(
            Array(reg(pairAsk), reg(loopSay, identity, 0)),
            say("a").map(_ => ask).map(x => say("b").map(_ => x)).asInstanceOf[Any < Any]
        )
        println(s"P4b state-forks = $p4b (expect ((2,10),(2,20)))")

        // P5: loop state threads across answers, final state at exit
        val loopAsk: Handler[?, ?, ?] =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Any, Any, Any](Tag[Ask]):
                def apply[X](input: Unit, state: Any, cont: Int => Any < (Ask & Any)): (Any, Any < (Ask & Any)) =
                    (state.asInstanceOf[Int] + 1, cont(state.asInstanceOf[Int]))
        val p5 = driveAll(
            Array(reg(loopAsk, identity, 0)),
            ask.map(a => ask.map(b => a + b)).asInstanceOf[Any < Any]
        )
        println(s"P5 loop-state = $p5 (expect (2,1))")

        // P6 perf: one region cycle per iteration, drive vs trampoline
        def onceDrive(): Int =
            driveAll(Array(reg(resumeAsk(41))), ask.map(_ + 1).asInstanceOf[Any < Any]).asInstanceOf[Int]
        def onceTrampoline(): Int =
            ArrowEffect.resume(Tag[Ask], ask.map(_ + 1))([X] => _ => 41).eval

        var w = 0
        while w < 50000 do
            HandlersProbe.discardInt(onceDrive())
            HandlersProbe.discardInt(onceTrampoline())
            w += 1
        end while
        val iters    = 1000000
        val (td, ad) = HandlersProbe.measure("drive-cycle", iters)(() => onceDrive())
        val (tt, at) = HandlersProbe.measure("trampoline-cycle", iters)(() => onceTrampoline())
        println(f"P6 region cycle: drive ${td}%6.1f ns ${ad}%5.0f B  trampoline ${tt}%6.1f ns ${at}%5.0f B per handler call")
    end main

end HandlersProbe2
