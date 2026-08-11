package kyo.kernel

import java.lang.management.ManagementFactory
import kyo.Frame
import kyo.Tag
import scala.annotation.tailrec

// executable spec for the Cont mechanism Eval does not handle yet: parks
// climbing by returns, each crossed scope wrapping itself, multi-shot
// capture. Loop and LoopState semantics live in EvalTest against the real
// Eval; this probe answers Loop only as far as its programs need.
object HandlersProbe:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    type ESuspend = Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type EHandled = Kyo.Handled[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type ELoop    = Handler.Loop[Const[Any], Const[Any], Nothing, Any, Any]
    type ECont    = Handler.Cont[Const[Any], Const[Any], Nothing, Any, Any]

    // a park climbs by ordinary returns, addressed by handler identity
    final class PPark(val owner: Handler[?, ?, ?], val input: Any, val resume: Any => Any)

    def walkSusp(k: ESuspend, o: Any < Any): Any < Any =
        val step = k.cont.step
        step.head(o, step.tail)

    def walkExit(cont: Arrow[Any, Any, Any], v: Any < Any): Any < Any =
        val step = cont.step
        step.head(v, step.tail)

    def resumeThen(park: PPark, andThen: Any => Any): Any => Any =
        o =>
            park.resume(o) match
                case e: PPark => new PPark(e.owner, e.input, resumeThen(e, andThen))
                case settled  => andThen(settled)

    // settled value or PPark
    def run(v0: Any < Any, handlers: Handlers): Any =
        val slot = Safepoint.get()
        @tailrec def loop(v: Any < Any): Any =
            (v: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] =>
                    val k = kyo.asInstanceOf[EHandled]
                    // the cast keeps the field read out of the implicit lift:
                    // EHandled pins E to Nothing, so value's `Any < Nothing`
                    // does not conform to `Any < Any` and the conversion would
                    // nest the region node as data
                    run(k.value.asInstanceOf[Any < Any], handlers.add(k.handler)) match
                        case park: PPark =>
                            new PPark(
                                park.owner,
                                park.input,
                                resumeThen(
                                    park,
                                    // settled results are already `<`-currency; lifting again double-nests
                                    s => run(walkExit(k.cont.asInstanceOf[Arrow[Any, Any, Any]], s.asInstanceOf[Any < Any]), handlers)
                                )
                            )
                        case settled =>
                            loop(walkExit(k.cont.asInstanceOf[Arrow[Any, Any, Any]], settled.asInstanceOf[Any < Any]))
                    end match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    val k   = kyo.asInstanceOf[ESuspend]
                    val idx = handlers.indexOf(k.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    else
                        handlers(idx) match
                            case h: Handler.Loop[?, ?, ?, ?, ?] =>
                                (h.asInstanceOf[ELoop][Any](k.input): Any) match
                                    case c: Handler.Loop.Continue[?] =>
                                        (c._1: Any) match
                                            case pending: Kyo[?, ?] =>
                                                run(pending.asInstanceOf[Any < Any], handlers.take(idx + 1)) match
                                                    case e: PPark => new PPark(
                                                            e.owner,
                                                            e.input,
                                                            resumeThen(e, s => run(walkSusp(k, s.asInstanceOf[Any < Any]), handlers))
                                                        )
                                                    case settled => loop(walkSusp(k, settled.asInstanceOf[Any < Any]))
                                            case answer =>
                                                // the continue payload is `<`-currency as constructed by the clause
                                                loop(walkSusp(k, answer.asInstanceOf[Any < Any]))
                                    case done =>
                                        throw new IllegalStateException("done not modeled by the probe")
                            case h: Handler.Cont[?, ?, ?, ?, ?] =>
                                new PPark(h, k.input, o => run(walkSusp(k, `<`.lift[Any, Any](o)), handlers))
                            case other =>
                                throw new IllegalStateException(s"not modeled by the probe: $other")
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, Any, Any]]
                    Safepoint.restore(slot, 0L)
                    val step = defer.cont.step
                    loop(step.head(defer.value, step.tail))
                case v =>
                    v
            end match
        end loop
        loop(v0)
    end run

    // the park addressed to a Cont handler is answered where its scope was
    // entered: the clause receives the wrapped continuation, replays re-enter
    // every crossed scope
    def answer(park: PPark, handlers: Handlers): Any =
        park.owner match
            case hc: Handler.Cont[?, ?, ?, ?, ?] =>
                // resume yields settled `<`-currency (or a further park); both
                // pass through as-is, lifting would double-nest currency
                val cont: Any => Any < Any = o => park.resume(o).asInstanceOf[Any < Any]
                // the cast keeps the clause result out of the implicit lift:
                // ECont pins E to Nothing, so its `Any < Nothing` result does
                // not conform to `Any < Any` and the conversion would nest it
                run(hc.asInstanceOf[ECont][Any](park.input, cont).asInstanceOf[Any < Any], handlers.add(park.owner))
            case other =>
                throw new IllegalStateException(s"park addressed to $other")

    def eval[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val out =
            try
                run(v.asInstanceOf[Any < Any], Handlers.empty) match
                    case park: PPark => answer(park, Handlers.empty)
                    case settled     => settled
            finally Safepoint.restore(slot, saved)
        out match
            case e: PPark => throw new IllegalStateException(s"unowned park: $e")
            case v =>
                Kyo.unnest(v.asInstanceOf[A < Any])
        end match
    end eval

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
            def apply[X](input: Unit) = Handler.Loop.continue(value)

    def loopSayRecord(
        name: String,
        log: scala.collection.mutable.ListBuffer[String]
    ): Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any](Tag[Say]):
            def apply[X](input: String) =
                log += name
                Handler.Loop.continue(())

    val threadMx = ManagementFactory.getThreadMXBean().asInstanceOf[com.sun.management.ThreadMXBean]

    def measure(iters: Int)(run: () => Int): (Double, Double) =
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

    def discardInt(i: Int): Unit = if i == Int.MinValue then println("impossible")

    def main(args: Array[String]): Unit =
        // a clause parking to an outer capture scope; the site remainder survives
        val contSay =
            new Handler.Cont[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
                def apply[X](input: String, cont: Unit => Int < (Say & Any)): Int < (Say & Any) = cont(())
        val askClauseSays =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = Handler.Loop.continue(say("c").map(_ => 41))
        val p1inner =
            new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Say](ask.map(_ + 1), askClauseSays, Arrow[Int])
        val p1 = eval(new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Any](p1inner, contSay, Arrow[Int]): Int < Any)
        println(s"P1 clause-parks = $p1 (expect 42)")

        // capture is multi-shot and re-enters crossed scopes per replay
        var crossedExits = 0
        val contAsk =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit, cont: Int => Int < (Ask & Any)): Int < (Ask & Any) =
                    cont(10).map(a => cont(20).map(b => a + b))
        val log2                         = scala.collection.mutable.ListBuffer[String]()
        val p2program: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
        val p2sayNode =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](p2program, loopSayRecord("s", log2), Arrow[Int])
        val p2say = (p2sayNode: Int < Ask).map { v =>
            crossedExits += 1
            v
        }
        val p2 = eval(new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Any](p2say, contAsk, Arrow[Int]): Int < Any)
        println(s"P2 multi-shot = $p2 crossedExits=$crossedExits says=${log2.size} (expect 32 2 1)")

        // scope cycle cost: real eval on a loop scope vs the trampoline
        def onceEval(): Int =
            (new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any).eval
        def onceTrampoline(): Int =
            ArrowEffect.resume(Tag[Ask], ask.map(_ + 1))([X] => _ => 41).eval

        var w = 0
        while w < 50000 do
            discardInt(onceEval())
            discardInt(onceTrampoline())
            w += 1
        end while
        val iters    = 1000000
        val (te, ae) = measure(iters)(() => onceEval())
        val (tt, at) = measure(iters)(() => onceTrampoline())
        println(f"P3 scope cycle: eval ${te}%6.1f ns ${ae}%5.0f B  trampoline ${tt}%6.1f ns ${at}%5.0f B per handler call")
    end main

end HandlersProbe
