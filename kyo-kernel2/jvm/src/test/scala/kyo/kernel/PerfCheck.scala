package kyo.kernel

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

    def deepBind(depth: Int): Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > depth then 0 else loop(i + 1)
            }
        loop(0).eval
    end deepBind

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

    def effectOps(depth: Int): Int =
        def loop(i: Int): Int < Ask =
            if i > depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handle(askTag, loop(0))([X] => (_, cont) => cont(1)).eval
    end effectOps

    def main(args: Array[String]): Unit =
        if args.nonEmpty && args(0) == "ops" then
            var i = 0
            while i < 100000 do
                val _ = effectOps(10000)
                i += 1
            return
        end if
        val depth = 10000
        println(s"depth = $depth, results: deepBind=${deepBind(depth)} narrow=${narrowBindMap(1000)} ops=${effectOps(depth)}")
        measure("deepBind      ", 300)(deepBind(depth))
        measure("narrowBindMap ", 300)(narrowBindMap(1000))
        measure("effectOps     ", 300)(effectOps(depth))
    end main
end PerfCheck
