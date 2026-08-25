package pendingtest

import kyo.Frame

object PendingBench:

    inline def N = 1000000

    def time(name: String)(body: => Any): String =
        var best      = Long.MaxValue
        var last: Any = null
        var i         = 0
        while i < 7 do
            val t0 = System.nanoTime
            last =
                try body
                catch case t: Throwable => t.getClass.getSimpleName
            val d = (System.nanoTime - t0) / 1000000
            if i >= 2 && d < best then best = d
            i += 1
        end while
        val line = s"$name: ${best}ms (result=$last)"
        println(line)
        line
    end time

    def main(args: Array[String]): Unit =
        val warm = Frame.derive
        Predef.locally(warm)
        val results = args(0) match
            case "proto"  => ProtoScenarios.all()
            case "kernel" => KernelScenarios.all()
        results.foreach(println)
    end main
end PendingBench

object ProtoScenarios:
    import PendingBench.*
    import kyo.Const
    import kyo.Maybe
    import kyo.Tag
    import kyo.proto.*
    import language.implicitConversions

    sealed trait Echo extends Effect[Const[Int], Const[Int]]

    val counterTag = Tag[Counter]
    val echoTag    = Tag[Echo]

    def counterOp(in: Maybe[Int]): Int < Counter =
        val s = new Kyo.Suspend[Const[Maybe[Int]], Const[Int], Counter, Any]:
            def input = in
            def tag   = counterTag
            def frame = Frame.derive
        s.map(Arrow[Int])
    end counterOp

    def echo(v: Int): Int < Echo =
        val s = new Kyo.Suspend[Const[Int], Const[Int], Echo, Any]:
            def input = v
            def tag   = echoTag
            def frame = Frame.derive
        s.map(Arrow[Int])
    end echo

    def runCounter(v: => Int < Counter, n0: Int): Int =
        var state = n0
        `<`.eval(counterTag, v, () => false, 512)(
            [X] =>
                (in: Maybe[Int], cont: Arrow[Int, Int, Counter]) =>
                    in match
                        case Maybe.Present(x) =>
                            state = x
                            Maybe(cont(x))
                        case _ =>
                            Maybe(cont(state))
        ).unsafeGet
    end runCounter

    def runEcho(v: => Int < Echo): Int =
        `<`.eval(echoTag, v, () => false, 512)(
            [X] => (in: Int, cont: Arrow[Int, Int, Echo]) => Maybe(cont(in))
        ).unsafeGet

    def all(): List[String] =
        val deepBind = time("deepBind"):
            def loop(i: Int): Unit < Any =
                ((): Unit < Any).map(_ => if i > N then () else loop(i + 1))
            loop(0).eval

        val narrowBindMap = time("narrowBindMap"):
            def loop(i: Int): Int < Echo =
                if i < N then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEcho(echo(0).map(loop))

        val state = time("state"):
            def program: Int < Counter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program))
            runCounter(program, N)

        def stateMapAt(name: String, size: Int) = time(name):
            def program: Int < Counter =
                counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
            runCounter(program, size)
        val stateMap10k  = stateMapAt("stateMap10k", 10000)
        val stateMap100k = stateMapAt("stateMap100k", 100000)
        val stateMap     = stateMapAt("stateMap1M", 1000000)

        val suspension = time("suspension"):
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

        List(deepBind, narrowBindMap, state, stateMap10k, stateMap100k, stateMap, suspension)
    end all
end ProtoScenarios

object KernelScenarios:
    import PendingBench.*
    import kyo.*
    import kyo.kernel.ArrowEffect

    sealed trait KEcho extends ArrowEffect[Const[Int], Const[Int]]

    def echo(v: Int): Int < KEcho =
        ArrowEffect.suspend[Any](Tag[KEcho], v)

    def runEcho(v: => Int < KEcho): Int =
        ArrowEffect.handle(Tag[KEcho], v)(
            [C] => (in, cont) => cont(in)
        ).eval

    def all(): List[String] =
        val deepBind = time("deepBind"):
            def loop(i: Int): Unit < Any =
                Kyo.unit.flatMap(_ => if i > N then () else loop(i + 1))
            loop(0).eval

        val narrowBindMap = time("narrowBindMap"):
            def loop(i: Int): Int < KEcho =
                if i < N then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEcho(echo(0).map(loop))

        // kyo.Var rides the new kernel mid-migration, so its rows cannot type against this module's
        // Safepoint until the migration lands; the cross-kernel board is kyo-kernel-bench
        val state        = "state: skipped (kyo.Var rides the new kernel mid-migration)"
        val stateMap10k  = "stateMap10k: skipped (kyo.Var rides the new kernel mid-migration)"
        val stateMap100k = "stateMap100k: skipped (quadratic, ~2min per rep)"
        val stateMap     = "stateMap1M: skipped (quadratic, unbounded runtime)"

        val suspension = time("suspension"):
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

        List(deepBind, narrowBindMap, state, stateMap10k, stateMap100k, stateMap, suspension)
    end all
end KernelScenarios
