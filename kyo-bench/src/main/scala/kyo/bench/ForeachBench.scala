package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized
import scala.util.Random

class ForeachBench extends BaseBench:
    @Param(Array("1024", "65536", "262144", "1048576"))
    var size: Int = uninitialized

    var intChunk: Chunk[Int] = uninitialized
    var intVec: Vector[Int]  = uninitialized

    def intFillFn: Int = Random.nextInt()

    def f(i: Int): Int < Any = i + 1

    @Setup(Level.Trial)
    def setup(): Unit =
        intChunk = Chunk.fill(size)(intFillFn)
        intVec = intChunk.toVector
    end setup

    @Benchmark def kyoForeach(bh: Blackhole) =
        val prg = Kyo.foreach(intChunk)(f).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def deferChunk(bh: Blackhole) =
        val asVal = intChunk
        val prg   = defer(asVal.map(i => f(i).now).size)
        bh.consume(prg.eval)
    end deferChunk

    @Benchmark def deferSeq(bh: Blackhole) =
        val asVal: Seq[Int] = intChunk
        val prg             = defer(asVal.map(i => f(i).now).size)
        bh.consume(prg.eval)
    end deferSeq

    @Benchmark def deferVector(bh: Blackhole) =
        val asVal = intVec
        val prg   = defer(asVal.map(i => f(i).now).size)
        bh.consume(prg.eval)
    end deferVector

    @Benchmark def deferVectorAsSeq(bh: Blackhole) =
        val asVal: Seq[Int] = intVec
        val prg             = defer(asVal.map(i => f(i).now).size)
        bh.consume(prg.eval)
    end deferVectorAsSeq

    @Benchmark def deferIterable(bh: Blackhole) =
        val asVal: Iterable[Int] = intChunk
        val prg                  = defer(asVal.map(i => f(i).now).size)
        bh.consume(prg.eval)
    end deferIterable

end ForeachBench
