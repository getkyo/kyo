package pendingtest

import kyo.proto.*
import language.implicitConversions

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
        import ProtoScenarios.*
        measure("narrowIter", 200000):
            def loop(i: Int): Int < Echo =
                if i < 10 then
                    echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else echo(i)
            runEcho(echo(0).map(loop))
    end main
end PendingAllocBench
