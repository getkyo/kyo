package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized
import scala.util.Random

class ForeachSpecializedBench extends BaseBench:
    @Param(Array("1024", "65536"))
    var size: Int = uninitialized

    var intChunk: Chunk[Int] = uninitialized
    var intVec: Vector[Int]  = uninitialized
    var intList: List[Int]   = uninitialized
    var intSeq: Seq[Int]     = uninitialized

    def intFillFn: Int = Random.nextInt()

    def f(i: Int): Int < Any = i + 1

    def g(i: Int): Iterable[Int] = Iterable(i * 5, i * 5 + 1, i * 5 + 2, i * 5 + 3, i * 5 + 4)

    @Setup(Level.Trial)
    def setup(): Unit =
        intChunk = Chunk.fill(size)(intFillFn)
        intVec = intChunk.toVector
        intList = intChunk.toList
        intSeq = intChunk.toSeq
    end setup

    @Benchmark def kyoForeachChunk(bh: Blackhole) =
        val prg = Kyo.foreach(intChunk)(f).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachVec(bh: Blackhole) =
        val prg = Kyo.foreach(intVec)(f).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachList(bh: Blackhole) =
        val prg = Kyo.foreach(intList)(f).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachGeneric(bh: Blackhole) =
        val prg = Kyo.foreach(intSeq)(f).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachConcatChunk(bh: Blackhole) =
        val prg = Kyo.foreachConcat(intChunk)(g).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachConcatVec(bh: Blackhole) =
        val prg = Kyo.foreachConcat(intVec)(g).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachConcatList(bh: Blackhole) =
        val prg = Kyo.foreachConcat(intList)(g).map(_.size)
        bh.consume(prg.eval)

    @Benchmark def kyoForeachConcatGeneric(bh: Blackhole) =
        val prg = Kyo.foreachConcat(intSeq)(g).map(_.size)
        bh.consume(prg.eval)

end ForeachSpecializedBench
