package kyo.kernel.bench


import java.util.concurrent.TimeUnit
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.internal.Context
import kyo.kernel.internal.Handlers
import language.implicitConversions
import org.openjdk.jmh.annotations.*

sealed trait BenchEcho    extends ArrowEffect[Const[Int], Const[Int]]
sealed trait BenchCounter extends ArrowEffect[Const[Maybe[Int]], Const[Int]]
sealed trait BenchEnv     extends ContextEffect[Int]
sealed trait BenchStop    extends ArrowEffect[Const[Int], Const[Int]]
sealed trait BenchFail    extends ArrowEffect[Const[Int], Const[Nothing]]

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
        ArrowEffect.suspend[Any](echoTag, v)

    def counterOp(in: Maybe[Int]): Int < BenchCounter =
        ArrowEffect.suspend[Any](counterTag, in)

    def runEcho(v: => Int < BenchEcho): Int =
        ArrowEffect.handle(echoTag, v)(
            [C] => (in, cont) => cont(in)
        ).eval

    def runEchoStep(v: => Int < BenchEcho): Int =
        ArrowEffect.handlePartial(echoTag, v, Context.empty)(
            [C] =>
                (in, cont) =>
                    cont.step match
                        case Maybe.Present(s) => Maybe(s.head.run(in, Context.empty, Handlers.empty, s.next).asInstanceOf[Int < BenchEcho])
                        case Maybe.Absent     => Maybe(in: Int < BenchEcho)
        ).asInstanceOf[Int < Any].eval

    def runCounter(v: => Int < BenchCounter, n0: Int): Int =
        var state = n0
        ArrowEffect.handle(counterTag, v)(
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
        val _ = ArrowEffect.handlePartial(echoTag, k, Context.empty)(
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

    val envTag = Tag[BenchEnv]

    @Benchmark
    def contextRead100: Int =
        def loop(i: Int, acc: Int): Int < BenchEnv =
            if i == 100 then acc
            else ContextEffect.suspend(envTag).map(v => loop(i + 1, acc + v))
        ContextEffect.handle(envTag, 1)(loop(0, 0)).eval
    end contextRead100

    @Benchmark
    def loopPure10k: Int =
        Loop(0)(i => if i < 10000 then Loop.continue(i + 1) else Loop.done(i)).eval

    @Benchmark
    def loopSuspend1k: Int =
        val v = Loop(0) { i =>
            if i < 1000 then echo(i).map(_ => Loop.continue(i + 1))
            else Loop.done(i)
        }
        runEcho(v)
    end loopSuspend1k

    val stopTag = Tag[BenchStop]
    val failTag = Tag[BenchFail]

    def stopOp(v: Int): Int < BenchStop =
        ArrowEffect.suspend[Any](stopTag, v)

    def failOp(v: Int): Nothing < BenchFail =
        ArrowEffect.suspend[Any](using summon[kyo.Frame])[Const[Int], Const[Nothing], BenchFail](failTag, v)

    @Benchmark
    def deepStop1k: Int =
        // a foreign resumption, one thousand frames, then the stop operation: the skip's headline row
        def chain(v: Int): Int < (BenchStop & BenchEcho) =
            var acc: Int < (BenchStop & BenchEcho) = v
            var i                                  = 0
            while i < 1000 do
                acc = acc.map(_ + 1)
                i += 1
            acc.map(x => stopOp(x))
        val program = echo(0).map(z => chain(z))
        val stopped = ArrowEffect.handleStop(stopTag, program)([C] => in => in)
        runEcho(stopped.asInstanceOf[Int < BenchEcho])
    end deepStop1k

    @Benchmark
    def stopConstructed1k: Int =
        // the same chain built before the handle call: construction that predates the handler is out of reach, the honesty row
        var acc: Int < BenchStop = 0
        var i                    = 0
        while i < 1000 do
            acc = acc.map(_ + 1)
            i += 1
        val program = acc.map(x => stopOp(x))
        ArrowEffect.handleStop(stopTag, program)([C] => in => in).eval
    end stopConstructed1k

    @Benchmark
    def neverResumes1k: Int =
        // a never-resuming operation followed by a thousand construction maps: the static skip makes them free
        var acc: Int < BenchFail = failOp(42).map[Int, Any]((n: Nothing) => n)
        var i                    = 0
        while i < 1000 do
            acc = acc.map(_ + 1)
            i += 1
        ArrowEffect.handle(failTag, acc)([C] => (in, cont) => in).eval
    end neverResumes1k

    @Benchmark
    def foreignBubbleUnderStop: Int =
        // a foreign operation surfacing mid chain under a registered stop region: the resolve-miss regression vector
        def chain(v: Int): Int < (BenchStop & BenchEcho) =
            var acc: Int < (BenchStop & BenchEcho) = echo(v).map(x => x)
            var i                                  = 0
            while i < 100 do
                acc = acc.map(_ + 1)
                i += 1
            acc
        val program = echo(0).map(z => chain(z))
        val stopped = ArrowEffect.handleStop(stopTag, program)([C] => in => in)
        runEcho(stopped.asInstanceOf[Int < BenchEcho])
    end foreignBubbleUnderStop

end KernelBench
