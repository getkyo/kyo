package kyo.prototype

import kyo.Frame
import kyo.Tag

object PerfCheck:

    given Frame = Frame.internal

    val threadMx = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

    inline def measure(name: String, iterations: Int)(inline body: => Any): Unit =
        var i = 0
        while i < 3 do
            var j = 0
            while j < iterations do
                val _ = body
                j += 1
            i += 1
        end while
        java.lang.System.gc()
        val runs   = new Array[Long](5)
        val allocs = new Array[Long](5)
        var r      = 0
        while r < 5 do
            val allocStart = threadMx.getCurrentThreadAllocatedBytes
            val start      = java.lang.System.nanoTime()
            var j          = 0
            while j < iterations do
                val _ = body
                j += 1
            runs(r) = (java.lang.System.nanoTime() - start) / iterations
            allocs(r) = (threadMx.getCurrentThreadAllocatedBytes - allocStart) / iterations
            r += 1
        end while
        java.util.Arrays.sort(runs)
        java.util.Arrays.sort(allocs)
        println(s"$name: ${runs(2)} ns/op (min ${runs(0)}, max ${runs(4)}), ${allocs(2)} bytes/op")
    end measure

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]
    val askTag: Tag[Ask] = Tag[Ask]
    def ask: Int < Ask   = ArrowEffect.suspend[[B] =>> Unit, [B] =>> Int, Ask, Any](askTag, ())

    def deepBindProto(depth: Int): Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > depth then 0 else loop(i + 1)
            }
        loop(0).eval
    end deepBindProto

    def narrowBindMapProto(depth: Int): Int =
        def loop(i: Int): Int < Any =
            if i > depth then i
            else
                ((i + 11): Int < Any)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(loop)
        loop(0).eval
    end narrowBindMapProto

    def effectOpsProto(depth: Int): Int =
        def loop(i: Int): Int < Ask =
            if i > depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handle(askTag, loop(0))([X] => (_, cont) => cont(1)).eval
    end effectOpsProto

    object OldKernel:
        import kyo.kernel.*
        given kyo.Frame = kyo.Frame.internal

        sealed trait AskOld extends ArrowEffect[[B] =>> Unit, [B] =>> Int]
        val askOldTag: Tag[AskOld] = Tag[AskOld]

        def effectOps(depth: Int): Int =
            def loop(i: Int): Int < AskOld =
                if i > depth then i
                else ArrowEffect.suspend[Any](askOldTag, ()).map(a => loop(i + a))
            ArrowEffect.handle(askOldTag, loop(0))([C] => (_, cont) => cont(1)).eval
        end effectOps

        def narrowBindMap(depth: Int): Int =
            def loop(i: Int): Int < Any =
                if i > depth then i
                else
                    ((i + 11): Int < Any)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(loop)
            loop(0).eval
        end narrowBindMap
    end OldKernel

    def deepBindOld(depth: Int): Int =
        import kyo.kernel.<
        import kyo.kernel.Effect
        def loop(i: Int): Int < Any =
            Effect.defer {
                if i > depth then 0 else loop(i + 1)
            }
        loop(0).eval
    end deepBindOld

    def main(args: Array[String]): Unit =
        if args.nonEmpty && args(0) == "ops" then
            var i = 0
            while i < 100000 do
                val _ = effectOpsProto(10000)
                i += 1
            return
        end if
        val depth   = 10000
        val suite   = if args.isEmpty then "all" else args(0)
        val proto   = suite == "all" || suite == "proto"
        val kernel2 = suite == "all" || suite == "kernel2"
        if proto then
            println(
                s"depth = $depth, results: proto deepBind=${deepBindProto(depth)} narrow=${narrowBindMapProto(1000)} ops=${effectOpsProto(depth)}"
            )
            measure("proto   deepBind      ", 300)(deepBindProto(depth))
            measure("proto   narrowBindMap ", 300)(narrowBindMapProto(1000))
            measure("proto   effectOps     ", 300)(effectOpsProto(depth))
        end if
        if kernel2 then
            println(
                s"depth = $depth, results: kernel2 deepBind=${deepBindOld(depth)} narrow=${OldKernel.narrowBindMap(1000)} ops=${OldKernel.effectOps(depth)}"
            )
            measure("kernel2 deepBind      ", 300)(deepBindOld(depth))
            measure("kernel2 narrowBindMap ", 300)(OldKernel.narrowBindMap(1000))
            measure("kernel2 effectOps     ", 300)(OldKernel.effectOps(depth))
        end if
    end main
end PerfCheck
