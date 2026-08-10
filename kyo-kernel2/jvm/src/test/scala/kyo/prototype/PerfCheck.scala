package kyo.prototype

import kyo.Frame
import kyo.Tag

object PerfCheck:

    given Frame = Frame.internal

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
        val runs = new Array[Long](5)
        var r    = 0
        while r < 5 do
            val start = java.lang.System.nanoTime()
            var j     = 0
            while j < iterations do
                val _ = body
                j += 1
            runs(r) = (java.lang.System.nanoTime() - start) / iterations
            r += 1
        end while
        java.util.Arrays.sort(runs)
        println(s"$name: ${runs(2)} ns/op (min ${runs(0)}, max ${runs(4)})")
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
        val depth = 10000
        println(
            s"depth = $depth, results: proto deepBind=${deepBindProto(depth)} narrow=${narrowBindMapProto(1000)} ops=${effectOpsProto(depth)} old deepBind=${deepBindOld(depth)}"
        )
        measure("proto deepBind      ", 300)(deepBindProto(depth))
        measure("old   deepBind      ", 300)(deepBindOld(depth))
        measure("proto narrowBindMap ", 300)(narrowBindMapProto(1000))
        measure("proto effectOps     ", 300)(effectOpsProto(depth))
    end main
end PerfCheck
