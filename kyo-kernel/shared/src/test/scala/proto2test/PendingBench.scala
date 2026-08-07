package proto2test

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto2.*
import language.implicitConversions

sealed trait BenchEcho    extends Effect[Const[Int], Const[Int]]
sealed trait BenchCounter extends Effect[Const[Maybe[Int]], Const[Int]]

object PendingBench:

    val echoTag    = Tag[BenchEcho]
    val counterTag = Tag[BenchCounter]

    def echo(v: Int): Int < BenchEcho =
        val s = new Kyo.Suspend[Const[Int], Const[Int], BenchEcho, Any]:
            def input = v
            def tag   = echoTag
            def frame = Frame.derive
        s.map(Arrow[Int])
    end echo

    def counterOp(in: Maybe[Int]): Int < BenchCounter =
        val s = new Kyo.Suspend[Const[Maybe[Int]], Const[Int], BenchCounter, Any]:
            def input = in
            def tag   = counterTag
            def frame = Frame.derive
        s.map(Arrow[Int])
    end counterOp

    def runEcho(v: => Int < BenchEcho): Int =
        Kyo.unwrap(`<`.eval(echoTag, v)(
            [X] => (in: Int, cont: Arrow[Int, Int, BenchEcho]) => Maybe(cont(in))
        )).asInstanceOf[Int]

    def runCounter(v: => Int < BenchCounter, n0: Int): Int =
        var state = n0
        Kyo.unwrap(`<`.eval(counterTag, v)(
            [X] =>
                (in: Maybe[Int], cont: Arrow[Int, Int, BenchCounter]) =>
                    in match
                        case Maybe.Present(x) =>
                            state = x
                            Maybe(cont(x))
                        case _ =>
                            Maybe(cont(state))
        )).asInstanceOf[Int]
    end runCounter

    def time(name: String, reps: Int)(body: => Any): String =
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
        val warm = Frame.derive
        Predef.locally(warm)
        inline def N = 1000000
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
        val _ = time("stateMap10k", 5):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, 10000)
        val _ = time("stateMap100k", 2):
            def program: Int < BenchCounter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, 100000)
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
    end main
end PendingBench
