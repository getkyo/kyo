package kyo.kernel2.bench

import java.util.concurrent.TimeUnit
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.*
import language.implicitConversions
import org.openjdk.jmh.annotations.*

sealed trait BenchEcho    extends ControlEffect[Const[Int], Const[Int]]
sealed trait BenchCounter extends ControlEffect[Const[Maybe[Int]], Const[Int]]

/** Per-operation benchmarks for the kernel2 substrate and handler system.
  *
  * Each benchmark is one workload unit, so `-prof gc` reports the allocation per operation directly comparable to the design doc's
  * baseline table. Run manually, for example:
  *
  * {{{
  * sbt 'kyo-kernel2JVM/Jmh/run -prof gc .*KernelBench.*'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx3G", "-Xss10M", "-XX:+UseCompactObjectHeaders"))
class KernelBench:

    val echoTag    = Tag[BenchEcho]
    val counterTag = Tag[BenchCounter]

    def echo(v: Int): Int < BenchEcho =
        ControlEffect.suspend[Any](echoTag, v)

    def counterOp(in: Maybe[Int]): Int < BenchCounter =
        ControlEffect.suspend[Any](counterTag, in)

    def runEcho(v: => Int < BenchEcho): Int =
        ControlEffect.handle(echoTag, v)(
            [C] => (in, cont) => cont(in)
        ).eval

    def runEchoStep(v: => Int < BenchEcho): Int =
        ControlEffect.handle(echoTag, v)(
            [C] =>
                (in, cont) =>
                    cont.step match
                        case Maybe.Present(s) => s.head.run(in, s.next).asInstanceOf[Int < BenchEcho]
                        case Maybe.Absent     => in
        ).eval

    def runCounter(v: => Int < BenchCounter, n0: Int): Int =
        var state = n0
        ControlEffect.handle(counterTag, v)(
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

    var fusedCont: Arrow[Int, Int, BenchEcho] = null

    @Setup(Level.Trial)
    def setup(): Unit =
        var k: Int < BenchEcho = echo(0)
        var i                  = 0
        while i < 10 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val _ = ControlEffect.handlePartial(echoTag, k)(
            [C] =>
                (input, cont) =>
                    parked = cont
                    Maybe.Absent
        )
        fusedCont = parked.asInstanceOf[Arrow[Int, Int, BenchEcho]]
    end setup

    @Benchmark
    def eagerMap5: Int =
        ((1: Int < Any).map(_ + 1).map(_ * 2).map(_ - 3).map(_ + 5).map(_ - 4)).eval

    @Benchmark
    def deepBind10k: Unit =
        def loop(i: Int): Unit < Any =
            ((): Unit < Any).map(_ => if i > 10000 then () else loop(i + 1))
        loop(0).eval
    end deepBind10k

    @Benchmark
    def suspension: Int =
        runEcho(
            echo(0)
                .map(_ => echo(1)).map(_ => 2).map(_ => echo(3)).map(_ => 4)
                .map(_ => echo(5)).map(_ => 6).map(_ => echo(7)).map(_ => 8)
                .map(_ => echo(9)).map(_ => 10)
        )

    @Benchmark
    def suspensionStep: Int =
        runEchoStep(
            echo(0)
                .map(_ => echo(1)).map(_ => 2).map(_ => echo(3)).map(_ => 4)
                .map(_ => echo(5)).map(_ => 6).map(_ => echo(7)).map(_ => 8)
                .map(_ => echo(9)).map(_ => 10)
        )

    @Benchmark
    def state10: Int =
        def program: Int < BenchCounter =
            counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program))
        runCounter(program, 10)
    end state10

    @Benchmark
    def stateMap10k: Int =
        def program: Int < BenchCounter =
            counterOp(Maybe.Absent).map(n => if n <= 0 then n else counterOp(Maybe(n - 1)).map(_ => program).map(_ + 1))
        runCounter(program, 10000)
    end stateMap10k

    @Benchmark
    def narrowIter: Int =
        def loop(i: Int): Int < BenchEcho =
            if i < 10 then
                echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
            else echo(i)
        runEcho(echo(0).map(loop))
    end narrowIter

    @Benchmark
    def resumeFused: Int =
        fusedCont(7).asInstanceOf[Int < Any].eval

end KernelBench
