package kyo.bench

import kyo.Maybe
import kyo.Span
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized

class SpanBench extends BaseBench:
    @Param(Array("16", "1024"))
    var size: Int = uninitialized

    var ints: Span[Int]        = uninitialized
    var strs: Span[String]     = uninitialized
    var intSeq: Vector[Int]    = uninitialized
    var strSeq: Vector[String] = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        intSeq = Vector.tabulate(size)(identity)
        strSeq = intSeq.map(_.toString)
        ints = Span.from(intSeq)
        strs = Span.from(strSeq)
    end setup

    @Benchmark def emptyInt(bh: Blackhole)     = bh.consume(Span.empty[Int])
    @Benchmark def emptyStr(bh: Blackhole)     = bh.consume(Span.empty[String])
    @Benchmark def emptyGeneric(bh: Blackhole) = bh.consume(Span.empty[Maybe[Int]])

    @Benchmark def applyInt(bh: Blackhole) = bh.consume(Span(1, 2, 3))
    @Benchmark def applyStr(bh: Blackhole) = bh.consume(Span("a", "b", "c"))

    @Benchmark def fromSeqInt(bh: Blackhole) = bh.consume(Span.from(intSeq))
    @Benchmark def fromSeqStr(bh: Blackhole) = bh.consume(Span.from(strSeq))

    @Benchmark def tabulateInt(bh: Blackhole) = bh.consume(Span.tabulate(size)(identity))

    @Benchmark def mapInt(bh: Blackhole) = bh.consume(ints.map(_ + 1))
    @Benchmark def mapStr(bh: Blackhole) = bh.consume(strs.map(_ + "a"))

    @Benchmark def filterInt(bh: Blackhole)     = bh.consume(ints.filter(_ % 2 == 0))
    @Benchmark def filterStr(bh: Blackhole)     = bh.consume(strs.filter(_.length % 2 == 0))
    @Benchmark def filterNoneInt(bh: Blackhole) = bh.consume(ints.filter(_ < 0))
    @Benchmark def filterNoneStr(bh: Blackhole) = bh.consume(strs.filter(_.isEmpty))

    @Benchmark def sliceInt(bh: Blackhole)      = bh.consume(ints.slice(1, size - 1))
    @Benchmark def sliceStr(bh: Blackhole)      = bh.consume(strs.slice(1, size - 1))
    @Benchmark def sliceEmptyStr(bh: Blackhole) = bh.consume(strs.slice(1, 1))
    @Benchmark def dropAllStr(bh: Blackhole)    = bh.consume(strs.drop(size))

    @Benchmark def takeWhileStr(bh: Blackhole) = bh.consume(strs.takeWhile(_.length < 3))
    @Benchmark def reverseInt(bh: Blackhole)   = bh.consume(ints.reverse)
    @Benchmark def reverseStr(bh: Blackhole)   = bh.consume(strs.reverse)

    @Benchmark def appendInt(bh: Blackhole) = bh.consume(ints.append(0))
    @Benchmark def appendStr(bh: Blackhole) = bh.consume(strs.append("x"))
    @Benchmark def concatInt(bh: Blackhole) = bh.consume(ints.concat(ints))
    @Benchmark def concatStr(bh: Blackhole) = bh.consume(strs.concat(strs))

    @Benchmark def flatMapInt(bh: Blackhole)  = bh.consume(ints.flatMap(i => Span(i, i)))
    @Benchmark def distinctStr(bh: Blackhole) = bh.consume(strs.distinct)
end SpanBench
