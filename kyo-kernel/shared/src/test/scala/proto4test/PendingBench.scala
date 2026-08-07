package proto4test

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto4.*
import language.implicitConversions

sealed trait BenchEcho    extends ArrowEffect[Const[Int], Const[Int]]
sealed trait BenchCounter extends ArrowEffect[Const[Maybe[Int]], Const[Int]]

object PendingBench:

    val echoTag    = Tag[BenchEcho]
    val counterTag = Tag[BenchCounter]

    def echo(v: Int): Int < BenchEcho =
        ArrowEffect.suspend[Any](echoTag, v)

    def counterOp(in: Maybe[Int]): Int < BenchCounter =
        ArrowEffect.suspend[Any](counterTag, in)

    def runEcho(v: => Int < BenchEcho): Int =
        `<`.eval(echoTag, v)(
            [X] => (in: Int, cont: Arrow[Int, Int, BenchEcho]) => cont(in)
        )

    def runCounter(v: => Int < BenchCounter, n0: Int): Int =
        var state = n0
        `<`.eval(counterTag, v)(
            [X] =>
                (in: Maybe[Int], cont: Arrow[Int, Int, BenchCounter]) =>
                    in match
                        case Maybe.Present(x) =>
                            state = x
                            cont(x)
                        case _ =>
                            cont(state)
        )
    end runCounter

    // one resume handler per workload: the s.head.run site profiles receivers
    // per handler, so sharing one handler across workloads pools its profile
    def runEchoStepNarrow(v: => Int < BenchEcho): Int =
        `<`.eval(echoTag, v)(
            [X] =>
                (in: Int, cont: Arrow[Int, Int, BenchEcho]) =>
                    cont.step match
                        case Maybe.Present(s) => s.head.run(in, s.next)
                        case Maybe.Absent     => in
        )

    def runEchoStepSuspension(v: => Int < BenchEcho): Int =
        `<`.eval(echoTag, v)(
            [X] =>
                (in: Int, cont: Arrow[Int, Int, BenchEcho]) =>
                    cont.step match
                        case Maybe.Present(s) => s.head.run(in, s.next)
                        case Maybe.Absent     => in
        )

    def runCounterStep(v: => Int < BenchCounter, n0: Int): Int =
        var state = n0
        `<`.eval(counterTag, v)(
            [X] =>
                (in: Maybe[Int], cont: Arrow[Int, Int, BenchCounter]) =>
                    val x =
                        in match
                            case Maybe.Present(x) =>
                                state = x
                                x
                            case _ =>
                                state
                    cont.step match
                        case Maybe.Present(s) => s.head.run(x, s.next)
                        case Maybe.Absent     => x
        )
    end runCounterStep

    private var rowFilter: String = null

    def time(name: String, reps: Int)(body: => Any): String =
        if rowFilter != null && name != rowFilter then ""
        else
            var best      = Long.MaxValue
            var last: Any = null
            var i         = 0
            while i < reps do
                val t0 = java.lang.System.nanoTime
                last =
                    try body
                    catch case t: Throwable => t.getClass.getSimpleName
                val d = (java.lang.System.nanoTime - t0) / 1000000
                if d < best then best = d
                i += 1
            end while
            val line = s"$name: ${best}ms (result=$last)"
            println(line)
            line
    end time

    def main(args: Array[String]): Unit =
        rowFilter = if args.isEmpty then null else args(0)
        val warm = Frame.derive
        Predef.locally(warm)
        inline def N = 1000000
        val _ = time("eager", 5):
            var i   = 0
            var acc = 0
            while i < 1000000 do
                acc += ((i: Int < Any).map(_ + 1).map(_ * 2).map(_ - 3).map(_ + 5).map(_ - 4)).eval
                i += 1
            end while
            acc
        val _ = time("deepBind", 3):
            def loop(i: Int): Unit < Any =
                ((): Unit < Any).map(_ => if i > N then () else loop(i + 1))
            loop(0).eval
        val _ = time("narrowBindMap", 5):
            def loop(i: Int): Int < BenchEcho =
                if i < N then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEcho(echo(0).map(loop))
        val _ = time("state", 5):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program))
            runCounter(program, N)
        val _ = time("stateStep", 5):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program))
            runCounterStep(program, N)
        val _ = time("stateMap10k", 5):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, 10000)
        val _ = time("stateMap100k", 2):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, 100000)
        val _ = time("stateMap1M", 2):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, 1000000)
        val _ = time("suspension", 5):
            var i   = 0
            var acc = 0
            while i < 100000 do
                acc += runEcho(
                    echo(0)
                        .map(_ => echo(1)).map(_ => 2).map(_ => echo(3)).map(_ => 4)
                        .map(_ => echo(5)).map(_ => 6).map(_ => echo(7)).map(_ => 8)
                        .map(_ => echo(9)).map(_ => 10)
                )
                i += 1
            end while
            acc
        val _ = time("suspensionStep", 5):
            var i   = 0
            var acc = 0
            while i < 100000 do
                acc += runEchoStepSuspension(
                    echo(0)
                        .map(_ => echo(1)).map(_ => 2).map(_ => echo(3)).map(_ => 4)
                        .map(_ => echo(5)).map(_ => 6).map(_ => echo(7)).map(_ => 8)
                        .map(_ => echo(9)).map(_ => 10)
                )
                i += 1
            end while
            acc
        val _ = time("narrowBindMapStep", 5):
            def loop(i: Int): Int < BenchEcho =
                if i < N then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEchoStepNarrow(echo(0).map(loop))
    end main
end PendingBench
