package pendingtest

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto.*
import language.implicitConversions

sealed trait Ask     extends Effect[Const[Unit], Const[Int]]
sealed trait Counter extends Effect[Const[Maybe[Int]], Const[Int]]

object PendingFusionProbe:

    def ask: Int < Ask =
        val suspend = new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any]:
            def frame = Frame.derive
            def input = ()
            def tag   = Tag[Ask]
        suspend.map(Arrow[Int])
    end ask

    def cont(k: Any): Arrow[Int, Int, Any] =
        k.asInstanceOf[Kyo.Continue[Const[Unit], Const[Int], Ask, Any, Int, Any]].cont

    def eval(v: Int < Any): Int = v.eval

    def chainA: Int < Ask = ask.map(_ + 1).map(_ * 3).map(_ - 2).map(_ + 4).map(_ * 2).map(_ - 5).map(_ + 7).map(_ * 3).map(_ - 1)
    def chainB: Int < Ask = ask.map(_ - 7).map(_ * 5).map(_ + 11).map(_ - 3).map(_ * 2).map(_ + 6).map(_ - 9).map(_ * 4).map(_ + 2)
    def chainC: Int < Ask = ask.map(_ ^ 3).map(_ + 9).map(_ * 2).map(_ ^ 5).map(_ - 4).map(_ * 3).map(_ + 8).map(_ ^ 2).map(_ - 6)

    def deep(n: Int): Int =
        var k: Int < Ask = ask
        var i            = 0
        while i < n do
            k = k.map(_ + 1)
            i += 1
        eval(cont(k)(0))
    end deep

    def main(args: Array[String]): Unit =
        args(0) match
            case "alloc" =>
                val mx = java.lang.management.ManagementFactory.getThreadMXBean
                    .asInstanceOf[com.sun.management.ThreadMXBean]
                val ca  = cont(chainA)
                var sum = 0L
                var i   = 0
                while i < 2000000 do
                    sum += eval(ca(i))
                    i += 1
                val tid    = Thread.currentThread.threadId
                val before = mx.getThreadAllocatedBytes(tid)
                var j      = 0
                while j < 1000000 do
                    sum += eval(ca(j))
                    j += 1
                val after = mx.getThreadAllocatedBytes(tid)
                println(
                    s"allocated ${after - before} bytes / 1000000 resumes = ${(after - before) / 1000000.0} bytes per resume (sum=$sum)"
                )
            case "trace" =>
                val program: Int < Ask = ask.map { _ =>
                    (1: Int < Any).map(v => (throw new RuntimeException("boom")): Int)
                }
                try
                    val _ = `<`.eval(Tag[Ask], program, () => false, 512)(
                        [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe(cont(1))
                    )
                catch
                    case e: RuntimeException =>
                        println("=== enriched trace (thrown under eval):")
                        e.getStackTrace.take(8).foreach(el => println("  at " + el))
                        e.getSuppressed.foreach(s => println("  Suppressed: " + s))
                end try
                println()
                try
                    val _ = (1: Int < Any).map(_ => (2: Int < Any).map(v => (throw new RuntimeException("eager")): Int))
                catch
                    case e: RuntimeException =>
                        println("=== eager throw (no eval boundary), suppressed only:")
                        e.getStackTrace.take(4).foreach(el => println("  at " + el))
                        e.getSuppressed.foreach(s => println("  Suppressed: " + s))
                end try
            case "countdown" =>
                val counterTag = Tag[Counter]
                def op(in: Maybe[Int]): Int < Counter =
                    val s = new Kyo.Suspend[Const[Maybe[Int]], Const[Int], Counter, Any]:
                        def frame = Frame.derive
                        def input = in
                        def tag   = counterTag
                    s.map(Arrow[Int])
                end op
                def get: Int < Counter         = op(Maybe.Absent)
                def set(v: Int): Int < Counter = op(Maybe(v))

                def naive: Int < Counter =
                    get.map(n => if n <= 0 then n else set(n - 1).map(_ => naive))
                def trailing: Int < Counter =
                    get.map(n => if n <= 0 then n else set(n - 1).map(_ => trailing).map(x => x))

                def run(v: => Int < Counter, n0: Int): (Int, Long) =
                    var state = n0
                    val t0    = System.nanoTime
                    val r = `<`.eval(counterTag, v, () => false, 512)(
                        [X] =>
                            (in: Maybe[Int], cont: Arrow[Int, Int, Counter]) =>
                                in match
                                    case Maybe.Present(v2) =>
                                        state = v2
                                        Maybe(cont(v2))
                                    case _ =>
                                        Maybe(cont(state))
                    )
                    (r.unsafeGet, (System.nanoTime - t0) / 1000000)
                end run

                val _          = run(naive, 100000)
                val _          = run(trailing, 100000)
                val (rn1, tn1) = run(naive, 100000)
                val (rt1, tt1) = run(trailing, 100000)
                val (rn2, tn2) = run(naive, 1000000)
                val (rt2, tt2) = run(trailing, 1000000)
                val correct    = rn1 == 0 && rt1 == 0 && rn2 == 0 && rt2 == 0
                println(s"naive:    100k=${tn1}ms 1M=${tn2}ms")
                println(s"trailing: 100k=${tt1}ms 1M=${tt2}ms")
                val linear = tt2 < math.max(1, tt1) * 30
                val close  = tt2 < math.max(1, tn2) * 5
                println(
                    s"correct=$correct linearScaling=$linear trailingClose=$close ${if correct && linear && close then "OK" else "WRONG"}"
                )
            case "brackets" =>
                var log = List.empty[String]
                val b1 = new Kyo.Bracket[Int, Int, Any]:
                    def frame = Frame.derive
                    def acquire =
                        log :+= "acq"; 42
                    def release(r: Int) =
                        log :+= "rel"; ()
                    def cont = new Arrow.Transform[Int, Int, Any]:
                        def frame                                                    = Frame.derive
                        def run[C, S2](v: Int, c: Arrow[Int, C, S2]): C < (Any & S2) = c(v + 1)
                val r1  = b1.map(Arrow[Int]).eval
                val ok1 = r1 == 43 && log == List("acq", "rel")

                log = Nil
                val b2 = new Kyo.Bracket[Int, Int, Ask]:
                    def frame = Frame.derive
                    def acquire =
                        log :+= "acq"; 42
                    def release(r: Int) =
                        log :+= "rel"; ()
                    def cont = new Arrow.Transform[Int, Int, Ask]:
                        def frame                                                    = Frame.derive
                        def run[C, S2](v: Int, c: Arrow[Int, C, S2]): C < (Ask & S2) = c(ask.map(a => a + v))
                var parked: Any = null
                val rem = `<`.eval(Tag[Ask], b2.map(Arrow[Int]), () => false, 512)(
                    [X] =>
                        (u: Unit, c: Arrow[Int, Int, Ask]) =>
                            parked = c
                            Maybe.Absent
                )
                val afterPark = log == List("acq")
                val r2        = parked.asInstanceOf[Arrow[Int, Int, Ask]](100).asInstanceOf[Int < Any].eval
                val ok2       = afterPark && r2 == 142 && log == List("acq", "rel")

                log = Nil
                val b3 = new Kyo.Bracket[Int, Int, Any]:
                    def frame = Frame.derive
                    def acquire =
                        log :+= "acq"; 42
                    def release(r: Int) =
                        log :+= "rel"; ()
                    def cont = new Arrow.Transform[Int, Int, Any]:
                        def frame = Frame.derive
                        def run[C, S2](v: Int, c: Arrow[Int, C, S2]): C < (Any & S2) =
                            throw new RuntimeException("boom")
                val ok3 =
                    try
                        val _ = b3.map(Arrow[Int]).eval
                        false
                    catch case e: RuntimeException => e.getMessage == "boom" && log == List("acq", "rel")

                println(s"total=$ok1 parked=$ok2 exception=$ok3 ${if ok1 && ok2 && ok3 then "OK" else "WRONG"}")
            case "effects" =>
                val n            = 1000
                var k: Int < Ask = ask
                var i            = 0
                while i < n do
                    k = k.map(_ + 1)
                    i += 1
                val done = `<`.eval(Tag[Ask], k, () => false, 512)(
                    [X] => (input: Unit, c: Arrow[Int, Int, Ask]) => Maybe(c(5))
                )
                var parked: Any = null
                val r = `<`.eval(Tag[Ask], k, () => false, 512)(
                    [X] =>
                        (input: Unit, c: Arrow[Int, Int, Ask]) =>
                            parked = c
                            Maybe.Absent
                )
                val resumed = parked.asInstanceOf[Arrow[Int, Int, Ask]](7)
                val done2 = `<`.eval(Tag[Ask], resumed, () => false, 512)(
                    [X] => (input: Unit, c: Arrow[Int, Int, Ask]) => Maybe(c(0))
                )
                val ok = done.unsafeGet == n + 5 && done2.unsafeGet == n + 7
                println(s"sync-handled=${done.unsafeGet} parked-resumed=${done2.unsafeGet} ${if ok then "OK" else "WRONG"}")
            case "sync" =>
                val n = 1000000
                def loop(i: Int): Int < Any =
                    if i == 0 then 0
                    else (i: Int < Any).map(_ => loop(i - 1))
                val r = loop(n).eval
                println(s"sync deep recursion: $r ${if r == 0 then "OK" else "WRONG"}")
            case "preempt" =>
                val n            = 10000
                var k: Int < Ask = ask
                var i            = 0
                while i < n do
                    k = k.map(_ + 1)
                    i += 1
                var polls = 0
                val suspended = cont(k)(0).eval(() =>
                    polls += 1; polls == 2
                )
                val done = suspended.eval
                println(s"polls=$polls done=$done ${if polls == 2 && done == n then "OK" else "WRONG"}")
                var p512 = 0
                val r512 = cont(k)(0).eval(
                    () =>
                        p512 += 1;
                        false
                    ,
                    512
                ).eval
                var p4096 = 0
                val r4096 = cont(k)(0).eval(
                    () =>
                        p4096 += 1;
                        false
                    ,
                    4096
                ).eval
                println(s"p512=$p512 p4096=$p4096 ${if r512 == n && r4096 == n && p512 > p4096 * 4 then "OK" else "WRONG"}")
            case "check" =>
                val n = 100000
                val r = deep(n)
                println(s"deep($n) = $r, expected $n, ${if r == n then "OK" else "WRONG"}")
            case mode =>
                val n   = mode.toInt
                val ca  = cont(chainA)
                val cb  = cont(chainB)
                val cc  = cont(chainC)
                var sum = 0L
                var i   = 0
                while i < 3000000 do
                    sum += eval(ca(i))
                    if n >= 2 then sum += eval(cb(i))
                    if n >= 3 then sum += eval(cc(i))
                    i += 1
                end while
                println(sum)
    end main
end PendingFusionProbe
