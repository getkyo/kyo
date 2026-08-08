package kyo.kernel2

// TEMPORARY experiment E1 for the rotation/handlers design: measures the in-place
// Resume ceiling (threaded evidence lookup + typed clause call) against the real
// handleResume park-and-dispatch path, on identical kernel2 chain workloads.
// Deleted or folded into the design once measured.

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions

object EvidenceProbeMain:

    given Frame = Frame.internal

    sealed trait Echo extends ArrowEffect[Const[Int], Const[Int]]
    val echoTag = Tag[Echo]

    def echo(i: Int): Int < Echo = ArrowEffect.suspend[Any](echoTag, i)

    /** The experimental evidence environment: tag-keyed, clauses stored and invoked at their public types. The single cast is the
      * tag-keyed recovery, the same boundary Context.get carries.
      */
    final class Evidence private (map: Map[Tag[Any], AnyRef]):
        def set[I[_], O[_], E <: ArrowEffect[I, O]](tag: Tag[E], clause: [C] => I[C] => O[C]): Evidence =
            new Evidence(map.updated(tag.erased, clause.asInstanceOf[AnyRef]))
        def resolve[I[_], O[_], E <: ArrowEffect[I, O]](tag: Tag[E]): Maybe[[C] => I[C] => O[C]] =
            map.get(tag.erased) match
                // the tag-keyed recovery: the entry was stored under this tag at the clause's public type
                case Some(clause) => Maybe(clause.asInstanceOf[[C] => I[C] => O[C]])
                case None         => Maybe.Absent
    end Evidence
    object Evidence:
        val empty = new Evidence(Map.empty)

    // B: the operation answers in place through the evidence, no park, no dispatch
    def opB(ev: Evidence, i: Int): Int =
        ev.resolve(echoTag) match
            case Maybe.Present(clause) => clause[Any](i)
            case Maybe.Absent          => throw new IllegalStateException("unhandled")

    // identical chain shapes: 10 operations interleaved with plain maps
    def programA: Int < Echo =
        echo(0)
            .map(_ => echo(1)).map(_ => 2).map(_ => echo(3)).map(_ => 4)
            .map(_ => echo(5)).map(_ => 6).map(_ => echo(7)).map(_ => 8)
            .map(_ => echo(9)).map(_ => 10)

    def runA(): Int =
        ArrowEffect.handleResume(echoTag, programA)([C] => i => i).eval

    def programB(ev: Evidence): Int < Any =
        (0: Int < Any)
            .map(_ => opB(ev, 1)).map(_ => 2).map(_ => opB(ev, 3)).map(_ => 4)
            .map(_ => opB(ev, 5)).map(_ => 6).map(_ => opB(ev, 7)).map(_ => 8)
            .map(_ => opB(ev, 9)).map(_ => 10)

    def runB(ev: Evidence): Int =
        programB(ev).eval

    // baseline: the same chain with no operations at all, isolating the op cost
    def programZ: Int < Any =
        (0: Int < Any)
            .map(_ => 1).map(_ => 2).map(_ => 3).map(_ => 4)
            .map(_ => 5).map(_ => 6).map(_ => 7).map(_ => 8)
            .map(_ => 9).map(_ => 10)

    def runZ(): Int =
        programZ.eval

    def measure(name: String, iterations: Int)(body: => Int): Unit =
        val mx = java.lang.management.ManagementFactory.getThreadMXBean
            .asInstanceOf[com.sun.management.ThreadMXBean]
        val tid  = Thread.currentThread().getId(): @annotation.nowarn("cat=deprecation")
        var sink = 0
        var w    = 0
        while w < iterations do // warmup round
            sink += body
            w += 1
        val b0 = mx.getThreadAllocatedBytes(tid)
        val t0 = java.lang.System.nanoTime()
        var i  = 0
        while i < iterations do
            sink += body
            i += 1
        val t1 = java.lang.System.nanoTime()
        val b1 = mx.getThreadAllocatedBytes(tid)
        println(f"$name%-10s ${(t1 - t0).toDouble / iterations}%9.1f ns/op  ${(b1 - b0).toDouble / iterations}%9.1f B/op  (sink=$sink)")
    end measure

    // E2: environment shape sensitivity: lookup at depth 1 vs 4, and the miss path
    sealed trait Pad1 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Pad2 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Pad3 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Miss extends ArrowEffect[Const[Int], Const[Int]]

    def main(args: Array[String]): Unit =
        val ev = Evidence.empty.set(echoTag, [C] => (i: Int) => i)
        val ev4 = Evidence.empty
            .set(Tag[Pad1], [C] => (i: Int) => i)
            .set(Tag[Pad2], [C] => (i: Int) => i)
            .set(Tag[Pad3], [C] => (i: Int) => i)
            .set(echoTag, [C] => (i: Int) => i)
        val missTag = Tag[Miss]
        val n       = 300000
        var round   = 0
        while round < 3 do // interleaved rounds so JIT drift hits both sides
            measure("baseline", n)(runZ())
            measure("resumeA", n)(runA())
            measure("evidenceB", n)(runB(ev))
            measure("depth4", n)(runB(ev4))
            measure("missPath", n) {
                var i = 0
                var s = 0
                while i < 10 do
                    s += (ev4.resolve(missTag) match
                        case Maybe.Present(_) => 1
                        case Maybe.Absent     => 0)
                    i += 1
                end while
                s
            }
            round += 1
        end while
    end main
end EvidenceProbeMain
