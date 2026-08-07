package pendingtest

object PendingAllocBench:

    private val tmx =
        java.lang.management.ManagementFactory.getThreadMXBean
            .asInstanceOf[com.sun.management.ThreadMXBean]

    private def allocated(): Long =
        tmx.getThreadAllocatedBytes(Thread.currentThread.threadId)

    private var sink: Any = null

    def measure(name: String, iters: Int)(body: => Any): Unit =
        var warm = 0
        while warm < iters * 2 do
            sink = body
            warm += 1
        var best  = Double.MaxValue
        var batch = 0
        while batch < 5 do
            val a0 = allocated()
            var i  = 0
            while i < iters do
                sink = body
                i += 1
            val perOp = (allocated() - a0).toDouble / iters
            if perOp < best then best = perOp
            batch += 1
        end while
        println(f"$name: $best%.1f B/op")
    end measure

    def main(args: Array[String]): Unit =
        val mode = if args.isEmpty then "proto" else args(0)
        mode match
            case "proto"  => protoRows()
            case "kernel" => KernelAllocRows.run()
    end main

    private def protoRows(): Unit =
        import kyo.proto.*
        import language.implicitConversions
        import ProtoScenarios.*
        measure("narrowIter", 200000):
            def loop(i: Int): Int < Echo =
                if i < 10 then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEcho(echo(0).map(loop))
    end protoRows

    // kernel rows mirror proto3test.PendingAllocBench row for row so the
    // B/op columns compare like for like under identical harness and flags
    private object KernelAllocRows:
        import KernelScenarios.KEcho
        import KernelScenarios.echo
        import KernelScenarios.runEcho
        import kyo.*
        import kyo.kernel.ArrowEffect
        import language.implicitConversions

        sealed trait KCounter extends ArrowEffect[Const[Maybe[Int]], Const[Int]]

        def counterOp(in: Maybe[Int]): Int < KCounter =
            ArrowEffect.suspend[Any](Tag[KCounter], in)

        def runCounter(v: => Int < KCounter, n0: Int): Int =
            var state = n0
            ArrowEffect.handle(Tag[KCounter], v)(
                [C] =>
                    (in, cont) =>
                        in match
                            case Maybe.Present(x) =>
                                state = x
                                cont(x)
                            case _ =>
                                cont(state)
            ).eval
        end runCounter

        def run(): Unit =
            measure("eager5", 1000000):
                ((1: Int < Any).map(_ + 1).map(_ * 2).map(_ - 3).map(_ + 5).map(_ - 4)).eval

            measure("eager10", 500000):
                ((1: Int < Any).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
                    .map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)).eval

            measure("suspension", 500000):
                runEcho(echo(1).map(_ + 1).map(_ + 1).map(_ + 1))

            measure("stateCont10", 200000):
                def program: Int < KCounter =
                    counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program))
                runCounter(program, 10)

            measure("narrowIter", 200000):
                def loop(i: Int): Int < KEcho =
                    if i < 10 then
                        echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                            .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                    else echo(i)
                runEcho(echo(0).map(loop))

            locally {
                var k: Int < KEcho = echo(0)
                var i              = 0
                while i < 10 do
                    k = k.map(_ + 1)
                    i += 1
                var parked: Any = null
                val _ = ArrowEffect.handleFirst(Tag[KEcho], k)(
                    handle = [C] =>
                        (in, cont) =>
                            parked = cont
                            (0: Int < Any)
                    ,
                    done = v => v
                ).eval
                val cont = parked.asInstanceOf[Int => Int < Any]
                measure("resumeFused10", 1000000):
                    cont(7).eval
            }
        end run
    end KernelAllocRows
end PendingAllocBench
