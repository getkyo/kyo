package kyo.kernel

import java.lang.management.ManagementFactory
import kyo.Chunk
import kyo.Frame
import kyo.Tag
import scala.annotation.tailrec

// executable spec for evaluating Kyo.Handled regions as merged layers: one
// flat loop carries the value with its (handler, exit) layers, entry appends
// a layer, a settled value pops the innermost exit, done feeds its own
// layer's exit, and pending clause computations chain with the crossed
// layers rebuilt around the resumption. No Halt, no recursion, no pair
// returns. Scenarios cover the current EvalTest semantics plus the five
// defects the region Eval reproduces red.
object EvalProbe:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask  extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say  extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def ask: Int < Ask                   = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say       = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)
    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Const[Int => Int], Const[Int], VarE, Any](Tag[VarE], f)

    type ESuspend   = Kyo.Suspend[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type EHandled   = Kyo.Handled[Const[Any], Const[Any], Nothing, Any, Any, Any]
    type ELoop      = Handler.Loop[Const[Any], Const[Any], Nothing, Any, Any]
    type ELoopState = Handler.LoopState[Const[Any], Const[Any], Nothing, Any, Any]

    def walk(cont: Arrow[Any, Any, Any], v: Any < Any): Any < Any =
        val step = cont.step
        step.head(v, step.tail)

    // a one-step arrow over erased currency; wrap-style: a pending result
    // absorbs the remainder via map instead of appending
    def transform(f: Any < Any => Any < Any): Arrow.Transform[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = summon[Frame]
            def apply[C, S2](v: Any < S2, next2: Arrow[Any, C, S2]) =
                (f(v.asInstanceOf[Any < Any]): Any) match
                    case kyo: Kyo[?, ?] =>
                        kyo.asInstanceOf[Kyo[Any, Any]].map(next2.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[C < S2]
                    case out =>
                        val step = next2.step
                        step.head(out.asInstanceOf[Any < S2], step.tail)

    // erased region constructor; the casts keep the arguments out of the
    // implicit lift, which would nest computations as data
    def region(value: Any < Any, h: Handler[?, ?, ?], exit: Arrow[Any, Any, Any]): EHandled =
        new Kyo.Handled[Const[Any], Const[Any], Nothing, Any, Any, Any](
            value.asInstanceOf[Any < Nothing],
            h.asInstanceOf[Handler[Const[Any], Const[Any], Nothing]],
            exit
        )

    // one flat loop over the value and its region layers: entering a region
    // appends its (handler, exit) layer, a settled value pops the innermost
    // exit, done feeds its own layer's exit and discards the layers it climbs
    // past, and a pending clause computation is chained with the crossed
    // layers rebuilt around the resumption. Nothing recurses and answering
    // allocates nothing
    type Exits = Chunk[Arrow[Any, Any, Any]]

    def rebuildFrom(from: Int, value: Any < Any, hs: Handlers, exits: Exits): Any < Any =
        @tailrec def wrap(i: Int, acc: Any < Any): Any < Any =
            if i < from then acc
            else wrap(i - 1, region(acc, hs(i), exits(i)))
        wrap(hs.size - 1, value)
    end rebuildFrom

    def run(v0: Any < Any): Any =
        val slot = Safepoint.get()
        @tailrec def loop(value: Any < Any, hs: Handlers, exits: Exits): Any =
            (value: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] =>
                    val k = kyo.asInstanceOf[EHandled]
                    loop(k.value.asInstanceOf[Any < Any], hs.add(k.handler), exits.append(k.cont.asInstanceOf[Arrow[Any, Any, Any]]))
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    val k   = s.asInstanceOf[ESuspend]
                    val idx = hs.indexOf(k.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $k")
                    else
                        val kCont = k.cont
                        hs(idx) match
                            case hl: Handler.Loop[?, ?, ?, ?, ?] =>
                                (hl.asInstanceOf[ELoop][Any](k.input): Any) match
                                    case pend: Kyo[?, ?] =>
                                        val hsAll = hs
                                        val exAll = exits
                                        val chained = pend.asInstanceOf[Kyo[Any, Any]].map(transform { out =>
                                            Nested.unnest[Any](out) match
                                                case c: Loop.Continue[?] =>
                                                    rebuildFrom(idx, walk(kCont, c._1.asInstanceOf[Any < Any]), hsAll, exAll)
                                                case done =>
                                                    walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained, hs.take(idx), exits.take(idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs
                                                        val exAll = exits
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, a), hsAll, exAll)
                                                        })
                                                        loop(chained, hs.take(idx + 1), exits.take(idx + 1))
                                                    case a =>
                                                        loop(walk(kCont, c._1.asInstanceOf[Any < Any]), hs, exits)
                                            case done =>
                                                loop(walk(exits(idx), Nested.lift(done)), hs.take(idx), exits.take(idx))
                            case hst: Handler.LoopState[?, ?, ?, ?, ?] =>
                                (hst.asInstanceOf[ELoopState][Any](k.input): Any) match
                                    case pend: Kyo[?, ?] =>
                                        val hsOld = hs
                                        val exAll = exits
                                        val chained = pend.asInstanceOf[Kyo[Any, Any]].map(transform { out =>
                                            Nested.unnest[Any](out) match
                                                case c: Loop.Continue2[?, ?] =>
                                                    val hsAll = hsOld.updated(idx, c._1.asInstanceOf[Handler[?, ?, ?]])
                                                    rebuildFrom(idx, walk(kCont, c._2.asInstanceOf[Any < Any]), hsAll, exAll)
                                                case done =>
                                                    walk(exAll(idx), Nested.lift(done))
                                        })
                                        loop(chained, hs.take(idx), exits.take(idx))
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Loop.Continue2[?, ?] =>
                                                val next = c._1.asInstanceOf[Handler[?, ?, ?]]
                                                val hs2  = if next eq hst then hs else hs.updated(idx, next)
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val hsAll = hs2
                                                        val exAll = exits
                                                        val chained = p.asInstanceOf[Kyo[Any, Any]].map(transform { a =>
                                                            rebuildFrom(idx + 1, walk(kCont, a), hsAll, exAll)
                                                        })
                                                        loop(chained, hs2.take(idx + 1), exits.take(idx + 1))
                                                    case a =>
                                                        loop(walk(kCont, c._2.asInstanceOf[Any < Any]), hs2, exits)
                                                end match
                                            case done =>
                                                loop(walk(exits(idx), Nested.lift(done)), hs.take(idx), exits.take(idx))
                            case other =>
                                throw new IllegalStateException(s"cannot handle: $other")
                        end match
                    end if
                case d: Kyo.Defer[?, ?, ?] =>
                    val defer = d.asInstanceOf[Kyo.Defer[Any, Any, Any]]
                    Safepoint.restore(slot, 0L)
                    loop(walk(defer.cont, defer.value), hs, exits)
                case v =>
                    if hs.size == 0 then v
                    else
                        val n = hs.size - 1
                        loop(walk(exits(n), v), hs.take(n), exits.take(n))
        loop(v0, Handlers.empty, Chunk.empty)
    end run

    def eval[A](v: A < Any): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val out   = run(v.asInstanceOf[Any < Any])
        Safepoint.restore(slot, saved)
        Nested.unnest[A](out)
    end eval

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
            def apply[X](input: Unit) = Loop.continue(value)

    def loopSay(
        name: String,
        log: scala.collection.mutable.ListBuffer[String]
    ): Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any](Tag[Say]):
            def apply[X](input: String) =
                log += name
                Loop.continue(())

    def failSay(result: Int): Handler.Loop[Const[String], Const[Unit], Say, Int, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
            def apply[X](input: String) = Loop.done(result)

    final class VarHandler(value: Int) extends Handler.LoopState[Const[Int => Int], Const[Int], VarE, Nothing, Any](Tag[VarE]):
        def apply[X](f: Int => Int) =
            val v2 = f(value)
            Loop.continue(if v2 == value then this else new VarHandler(v2), v2)
    end VarHandler

    var failures = 0

    def check[A](name: String)(actual: => A, expected: A)(using CanEqual[A, A]): Unit =
        val a =
            try Right(actual)
            catch case e: Throwable => Left(e)
        a match
            case Right(v) if v == expected => println(s"PASS $name = $v")
            case Right(v) =>
                failures += 1
                println(s"FAIL $name = $v expected $expected")
            case Left(e) =>
                failures += 1
                println(s"FAIL $name threw ${e.getClass.getSimpleName}: ${e.getMessage}")
        end match
    end check

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

        check("S1 scope answers")(
            eval(new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any),
            42
        )

        locally {
            val log   = scala.collection.mutable.ListBuffer[String]()
            val inner = (new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any).map(_ * 10)
            val outer = (new Kyo.Handled(inner, loopSay("s", log), Arrow[Int]): Int < Any).map(_ + 1000)
            check("S2 exits innermost first")(eval(outer), 1420)
        }

        locally {
            val inner = new Kyo.Handled(ask.map(_ + 1), loopAsk(1), Arrow[Int])
            val outer = new Kyo.Handled(inner, loopAsk(41), Arrow[Int])
            check("S3 innermost handler wins")(eval(outer: Int < Any), 2)
        }

        locally {
            val log = scala.collection.mutable.ListBuffer[String]()
            val askClauseSays =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                    def apply[X](input: Unit) = Loop.continue(say("c").map(_ => 41))
            val askScope = new Kyo.Handled(ask.map(_ + 1), askClauseSays, Arrow[Int])
            val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
            check("S4 effectful answer via outer scope")(eval(sayScope: Int < Any), 42)
            check("S4 log")(log.toList, List("s"))
        }

        locally {
            val log = scala.collection.mutable.ListBuffer[String]()
            val askClauseSays =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                    def apply[X](input: Unit) = Loop.continue(say("c").map(_ => 41))
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner =
                new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, loopSay("inner", log), Arrow[Int])
            val askScope = new Kyo.Handled(sayInner, askClauseSays, Arrow[Int])
            val sayOuter = new Kyo.Handled(askScope, loopSay("outer", log), Arrow[Int])
            check("S5 clause runs outside its scope")(eval(sayOuter: Int < Any), 42)
            check("S5 log")(log.toList, List("inner", "outer"))
        }

        locally {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r = new Kyo.Handled(loop(100000), loopAsk(1), Arrow[Int])
            check("S6 deep in-scope recursion")(eval(r: Int < Any), 0)
        }

        locally {
            var reached = false
            val failAsk =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                    def apply[X](input: Unit) = Loop.done(-1)
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val r = new Kyo.Handled(program, failAsk, Arrow[Int])
            check("S7 done ends the scope")(eval(r: Int < Any), -1)
            check("S7 remainder skipped")(reached, false)
        }

        locally {
            val log       = scala.collection.mutable.ListBuffer[String]()
            var innerExit = false
            val failAsk =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                    def apply[X](input: Unit) = Loop.done(-1)
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner =
                new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, loopSay("s", log), Arrow[Int])
            val mapped = (sayInner: Int < Ask).map { v =>
                innerExit = true
                v
            }
            val r = new Kyo.Handled(mapped, failAsk, Arrow[Int])
            check("S8 done climbs past inner scope")(eval(r: Int < Any), -1)
            check("S8 inner exit skipped")(innerExit, false)
            check("S8 log")(log.toList, List("s"))
        }

        locally {
            final class TwoPhase(phase: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit) =
                    if phase == 0 then Loop.continue(new TwoPhase(1), ask.map(_ + 100))
                    else Loop.done(-2)
            end TwoPhase
            val r = new Kyo.Handled(ask.map(_ + 1), new TwoPhase(0), Arrow[Int])
            check("S9 successor answers a re-raise and dones")(eval(r: Int < Any), -2)
        }

        locally {
            val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
            val r       = new Kyo.Handled(program, new VarHandler(0), Arrow[Int])
            check("S10 state threads")(eval(r: Int < Any), 30)
        }

        locally {
            val log                       = scala.collection.mutable.ListBuffer[String]()
            val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
            val innerScope =
                new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, VarE](inner, loopSay("s", log), Arrow[Int])
            val program = (innerScope: Int < VarE).map(_ => varOp(identity))
            val r       = new Kyo.Handled(program, new VarHandler(0), Arrow[Int])
            check("S11 state survives inner exit")(eval(r: Int < Any), 7)
        }

        locally {
            final class Budget(remaining: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit) =
                    if remaining > 0 then Loop.continue(new Budget(remaining - 1), 1)
                    else Loop.done(-1)
            end Budget
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r = new Kyo.Handled(go(5), new Budget(3), Arrow[Int])
            check("S12 state and done compose")(eval(r: Int < Any), -1)
        }

        locally {
            val program: Int < (Ask & Say) = say("x").map(_ => ask)
            val r =
                new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Say](program, loopAsk(41), Arrow[Int])
            val outcome =
                try
                    discardInt(eval((r: Int < Say).asInstanceOf[Int < Any]))
                    "no throw"
                catch case _: IllegalStateException => "throws"
            check("S13 unhandled suspension rejected")(outcome, "throws")
        }

        locally {
            val log = scala.collection.mutable.ListBuffer[String]()
            val askClause =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                    def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(41))
            val askScope = new Kyo.Handled(ask.map(_ + 1), askClause, Arrow[Int])
            val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
            check("S14 pending outcome continues")(eval(sayScope: Int < Any), 42)
            check("S14 log")(log.toList, List("s"))
        }

        locally {
            var reached = false
            val log     = scala.collection.mutable.ListBuffer[String]()
            val askClause =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Say](Tag[Ask]):
                    def apply[X](input: Unit) = say("pre").map(_ => Loop.done(-1))
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope = new Kyo.Handled(program, askClause, Arrow[Int])
            val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
            check("S15 pending outcome dones")(eval(sayScope: Int < Any), -1)
            check("S15 remainder skipped")(reached, false)
        }

        locally {
            var reached = false
            val askClause =
                new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                    def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(41))
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope = new Kyo.Handled(program, askClause, Arrow[Int])
            val r        = new Kyo.Handled(askScope, failSay(-9), Arrow[Int])
            check("S16 done during outcome settle climbs to its scope")(eval(r: Int < Any), -9)
            check("S16 remainder skipped")(reached, false)
        }

        locally {
            var reached = false
            final class Pre(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(new Pre(n + 1), n))
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope = new Kyo.Handled(program, new Pre(0), Arrow[Int])
            val r        = new Kyo.Handled(askScope, failSay(-9), Arrow[Int])
            check("S17 stateful variant climbs to its scope")(eval(r: Int < Any), -9)
            check("S17 remainder skipped")(reached, false)
        }

        locally {
            val depth = 1000000
            val nested = (1 to depth).foldLeft(0: Int < Any) { (acc, _) =>
                new Kyo.Handled(acc, loopAsk(1), Arrow[Int])
            }
            check("S18 static 1M nesting")(eval(nested), 0)
        }

        locally {
            val depth = 1000000
            def go(n: Int): Int < Any =
                if n == 0 then 0
                else new Kyo.Handled(ask.map(_ => go(n - 1)), loopAsk(1), Arrow[Int])
            check("S19 dynamic scope per step 1M")(eval(go(depth)), 0)
        }

        locally {
            val depth = 1000000
            final class Chain(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
                def apply[X](input: Unit) =
                    if n == 0 then Loop.continue(this, 0)
                    else Loop.continue(new Chain(n - 1), ask.map(_ + 1))
            end Chain
            val r = new Kyo.Handled(ask, new Chain(depth), Arrow[Int])
            check("S20 chained re-raises 1M")(eval(r: Int < Any), depth)
        }

        println(if failures == 0 then "ALL PASS" else s"$failures FAILURES")

        def onceRewrite(): Int =
            eval(new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any)
        def onceEval(): Int =
            (new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any).eval

        def deepProgram(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => deepProgram(n - 1))
        def deepRewrite(): Int =
            eval(new Kyo.Handled(deepProgram(10000), loopAsk(1), Arrow[Int]): Int < Any)
        def deepEval(): Int =
            (new Kyo.Handled(deepProgram(10000), loopAsk(1), Arrow[Int]): Int < Any).eval

        val log = scala.collection.mutable.ListBuffer[String]()
        def crossProgram(n: Int): Int < (Ask & Say) =
            if n == 0 then 0 else say("x").map(_ => crossProgram(n - 1))
        def crossNode: Int < Any =
            val inner = new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Say](
                crossProgram(10000).asInstanceOf[Int < (Ask & Say)].map(identity).asInstanceOf[Int < (Ask & Say)],
                loopAsk(1),
                Arrow[Int]
            )
            new Kyo.Handled(inner, loopSay("b", log), Arrow[Int])
        end crossNode
        def crossRewrite(): Int =
            log.clear()
            eval(crossNode)
        def crossEval(): Int =
            log.clear()
            crossNode.eval

        var w = 0
        while w < 30000 do
            discardInt(onceRewrite()); discardInt(onceEval())
            w += 1
        end while
        var w2 = 0
        while w2 < 300 do
            discardInt(deepRewrite()); discardInt(deepEval())
            discardInt(crossRewrite()); discardInt(crossEval())
            w2 += 1
        end while

        val it1        = 1000000
        val (tr, ar)   = measure(it1)(() => onceRewrite())
        val (te, ae)   = measure(it1)(() => onceEval())
        val it2        = 1000
        val (tdr, adr) = measure(it2)(() => deepRewrite())
        val (tde, ade) = measure(it2)(() => deepEval())
        val (tcr, acr) = measure(it2)(() => crossRewrite())
        val (tce, ace) = measure(it2)(() => crossEval())
        println(f"P1 one-op cycle:      rewrite ${tr}%8.1f ns ${ar}%7.0f B   eval ${te}%8.1f ns ${ae}%7.0f B")
        println(
            f"P2 10k answered ops:  rewrite ${tdr / 10000}%8.1f ns ${adr / 10000}%7.1f B   eval ${tde / 10000}%8.1f ns ${ade / 10000}%7.1f B per op"
        )
        println(
            f"P3 10k crossing ops:  rewrite ${tcr / 10000}%8.1f ns ${acr / 10000}%7.1f B   eval ${tce / 10000}%8.1f ns ${ace / 10000}%7.1f B per op"
        )
    end main

end EvalProbe
