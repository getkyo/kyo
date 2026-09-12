package kyo.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.Bracket
import kyo.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ListBuffer

class DebuggerTest extends AnyFreeSpec:

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answered[A](v: A < Ask): A < Any =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)

    class Recording extends Debugger:
        val events = ListBuffer.empty[String]
        private def record(kind: String): Unit =
            this.synchronized(discard(events += kind))
        override def onLoop(value: Any, contA: Any, contB: Any): Unit       = record("loop")
        override def onHandle(suspend: Any, handler: Any, state: Any): Unit = record("handle")
        override def onRegionEnter(handler: Any, state: Any): Unit          = record("regionEnter")
        override def onRegionExit(handler: Any, result: Any): Unit          = record("regionExit")
        override def onResult(value: Any): Unit                             = record("result")
        override def onRelease(handler: Any, ex: Any): Unit                 = record("release")
    end Recording

    // Cancelled rather than passed vacuously when the hooks are erased: installing a debugger into such a build
    // is refused, and a test that silently records nothing would look like coverage it is not.
    def session[A](d: Debugger)(body: => A): A =
        if !Debugger.enabled then cancel("requires -Dkyo.kernel.internal.Debugger.enabled=true")
        Debugger.install(d)
        try body
        finally Debugger.uninstall()
    end session

    "the hooks are no-ops and the gate is open by default" in {
        val d = new Debugger {}
        assert(d.enter())
        d.onLoop(1, 2, 3)
        d.onHandle(1, 2, 3)
        d.onRegionEnter(1, 2)
        d.onRegionExit(1, 2)
        d.onResult(1)
        d.onRelease(1, 2)
        d.onRecover(1, 2)
        d.onForeign(1, 2)
        d.onContext(1, 2)
        d.onAlloc(1)
        d.onUnfused(1)
        succeed
    }

    "the noop leaves results untouched" in {
        assert((1: Int < Any).map(_ + 1).map(_ * 2).eval == 4)
    }

    "install makes the session current and uninstall restores the noop" in {
        val d = new Recording
        session(d)(assert(Debugger.get eq d))
        assert(Debugger.get eq Debugger.Noop)
    }

    "a session observes an eval exactly when the hooks are compiled in" in {
        val d = new Recording
        session(d)(assert(answered(ask.map(_ + 1)).eval == 42))
        if Debugger.enabled then
            assert(d.events.contains("loop"))
            assert(d.events.contains("handle"))
            assert(d.events.contains("regionEnter"))
            assert(d.events.contains("regionExit"))
        else assert(d.events.isEmpty)
        end if
    }

    "uninstall stops the stream" in {
        val d = new Recording
        session(d)(discard((1: Int < Any).map(_ + 1).eval))
        val n = d.events.size
        assert((1: Int < Any).map(_ + 1).eval == 2)
        assert(d.events.size == n)
    }

    "a session leaves a park and resume's result untouched" in {
        val d = new Recording
        session(d) {
            val v: Int < Any =
                Effect.defer {
                    requestStop()
                    1
                }.map(_ + 41)
            val p = Eval.partial(v)
            assert(p.isInstanceOf[Pending[?, ?]])
            assert(p.eval == 42)
        }
    }

    "a bracket under a session still releases exactly once" in {
        var released = 0
        val d        = new Recording
        session(d) {
            val v = Bracket(Effect.defer(1))(r => (r + 1: Int < Any).map(_ * 2))((_, _) => released += 1)
            assert(v.eval == 4)
        }
        assert(released == 1)
    }

end DebuggerTest
