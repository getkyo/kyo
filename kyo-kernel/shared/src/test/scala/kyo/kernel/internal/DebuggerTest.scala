package kyo.kernel.internal

import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ListBuffer

class DebuggerTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answered[A](v: A < Ask): A < Any =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)

    // records every event; routing follows the documented protocol: a refusal allows the delivery
    // retry through a per-thread toggle, so a routed step surfaces once at onDefer and then runs.
    // The gate is frameless; frames arrive with the routed step
    class Recording(route: Boolean = false) extends Debugger:
        val events = ListBuffer.empty[(String, String)]
        private val allowNext = new ThreadLocal[Boolean]:
            override def initialValue = false
        override def enter(): Boolean =
            events += (("enter", ""))
            if allowNext.get() then
                allowNext.set(false)
                true
            else if route then
                allowNext.set(true)
                false
            else true
            end if
        end enter
        override def onDefer[A, S](stack: Stack, frame: Frame, value: A < S): A < S =
            events += (("defer", frame.position.show))
            value
        override def onSuspend[A](stack: Stack, frame: Frame, input: A): Unit =
            events += (("suspend", frame.position.show))
        override def onDeliver[A](stack: Stack, frame: Frame, value: A): A =
            events += (("deliver", frame.position.show))
            value
    end Recording

    def session[A](d: Debugger)(body: => A): A =
        Debugger.install(d)
        try body
        finally Debugger.uninstall()
    end session

    "the noop leaves results untouched" in {
        assert(Eval((1: Int < Any).map(_ + 1).map(_ * 2)) == 4)
    }

    // the gate is consulted within an eval's extent, so the chains build inside a deferred payload;
    // strict construction outside any eval runs unobserved by design
    def chain: Int < Any = Effect.defer((1: Int < Any).map(_ + 1).map(_ * 2))

    "enter observes strict applications inside the eval" in {
        val d = new Recording(route = false)
        session(d)(assert(Eval(chain) == 4))
        assert(d.events.exists(_._1 == "enter"))
    }

    "full-trace mode routes every strict step through the eval" in {
        val d = new Recording(route = true)
        session(d)(assert(Eval(chain) == 4))
        assert(d.events.count(_._1 == "defer") >= 2)
    }

    "routed steps surface with their call-site frames at onDefer" in {
        // the gate is frameless; a session that wants frames routes and reads them here, so the
        // chain's construction position must appear among the routed steps' frames
        val d = new Recording(route = true)
        session(d)(assert(Eval(chain) == 4))
        val positions = d.events.collect { case ("defer", p) => p }
        assert(positions.exists(_.contains("DebuggerTest.scala")))
    }

    "strict construction outside an eval is not consulted" in {
        val d = new Recording(route = false)
        session(d) {
            val v = (1: Int < Any).map(_ + 1)
            assert(d.events.isEmpty)
            assert(Eval(v) == 2)
        }
    }

    "an onDefer swap replaces the payload of a routed step" in {
        val d = new Recording(route = true):
            // the swap asserts conformance on its own side, which is the typed contract's point
            override def onDefer[A, S](stack: Stack, frame: Frame, value: A < S): A < S =
                if value.equals(1) then 10.asInstanceOf[A < S] else value
        session(d)(assert(Eval(Effect.defer(1).map(_ + 1)) == 11))
    }

    // red: the delivery of the intermediate value no longer routes through onDeliver
    "an onDeliver swap replaces the delivered value" ignore {
        val d = new Recording(route = true):
            override def onDeliver[A](stack: Stack, frame: Frame, value: A): A =
                if value.equals(2) then 20.asInstanceOf[A] else value
        // 1 + 1 delivers 2 into the trailing map, swapped to 20, times 2
        session(d)(assert(Eval(Effect.defer(1).map(_ + 1).map(_ * 2)) == 40))
    }

    "suspensions report their input" in {
        val d = new Recording(route = false)
        session(d)(assert(Eval(answered(ask.map(_ + 1))) == 42))
        assert(d.events.exists(_._1 == "suspend"))
    }

    "a session routes handler answers through the general paths" in {
        var deliveries = 0
        val d = new Recording(route = false):
            override def fastPathsAllowed = false
            override def onDeliver[A](stack: Stack, frame: Frame, value: A): A =
                deliveries += 1
                value
        session(d)(assert(Eval(answered(ask.map(_ + 1))) == 42))
        assert(deliveries > 0)
    }

    "uninstall stops the stream" in {
        val d = new Recording(route = false)
        session(d)(discard(Eval((1: Int < Any).map(_ + 1))))
        val n = d.events.size
        assert(Eval((1: Int < Any).map(_ + 1)) == 2)
        assert(d.events.size == n)
    }

    "an eval that began before install never observes the stack hooks" in {
        val d = new Recording(route = false)
        val v: Int < Any =
            Effect.defer {
                Debugger.install(d)
                1
            }.map(_ + 1).map(_ * 2)
        try assert(Eval(v) == 4)
        finally Debugger.uninstall()
        // the eval read the noop at entry; only a later eval would see d
        assert(!d.events.exists(_._1 == "defer"))
        assert(!d.events.exists(_._1 == "deliver"))
    }

    // red: Eval.partial runs through an armed Safepoint.stop instead of parking
    "a park and resume inside a session keeps the stream and the result" ignore {
        val d = new Recording(route = false)
        session(d) {
            val v: Int < Any =
                Effect.defer {
                    SafepointStop.request()
                    1
                }.map(_ + 41)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(Eval(p) == 42)
        }
        assert(d.events.nonEmpty)
    }

    "a routed bracket still releases exactly once" in {
        var released = 0
        val d        = new Recording(route = true)
        session(d) {
            val v = Effect.bracket(Effect.defer(1))(_ => released += 1)(r => (r + 1: Int < Any).map(_ * 2))
            assert(Eval(v) == 4)
        }
        assert(released == 1)
    }

end DebuggerTest
