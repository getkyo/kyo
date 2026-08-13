package kyo

import kyo.internal.DomBackend
import org.scalajs.dom
import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js as scalajs

/** Tests for the SPA event-delegation forwarding gate and its mount-scope lifecycle.
  *
  * These run against a real jsdom document (see [[DomTestEnv]]), so the traversal is exercised through the actual DOM
  * API: `createElement`, attribute reads, `parentNode` links, and the `instanceof Element` check in the walk.
  */
class DomBackendDelegationTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    private def el(parent: dom.Node, ev: String = null): dom.Element =
        val e = dom.document.createElement("div")
        if ev != null then e.setAttribute("data-kyo-ev", ev)
        if parent != null then discard(parent.appendChild(e))
        e
    end el

    final private case class ListenerCall(eventType: String, listener: scalajs.Any, options: scalajs.Any)

    final private class ListenerTracker(failOnAdd: String = null, chronology: ArrayBuffer[String] = ArrayBuffer.empty):
        val added    = ArrayBuffer.empty[ListenerCall]
        val removed  = ArrayBuffer.empty[ListenerCall]
        val attempts = ArrayBuffer.empty[String]

        private val body           = dom.document.body.asInstanceOf[scalajs.Dynamic]
        private val originalAdd    = body.addEventListener
        private val originalRemove = body.removeEventListener

        def install(): ListenerTracker =
            body.updateDynamic("addEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                attempts += eventType
                if eventType == failOnAdd then throw new scalajs.JavaScriptException(s"failed add: $eventType")
                else
                    added += ListenerCall(eventType, listener, options)
                    discard(originalAdd.call(body, eventType, listener, options))
                end if
            )
            body.updateDynamic("removeEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                removed += ListenerCall(eventType, listener, options)
                chronology += s"remove:$eventType"
                discard(originalRemove.call(body, eventType, listener, options))
            )
            this
        end install

        def restore(): Unit =
            body.updateDynamic("addEventListener")(originalAdd)
            body.updateDynamic("removeEventListener")(originalRemove)
    end ListenerTracker

    private def sameCall(left: ListenerCall, right: ListenerCall): Boolean =
        left.eventType == right.eventType &&
            scalajs.special.strictEquals(left.listener, right.listener) &&
            scalajs.special.strictEquals(left.options, right.options)

    private def captureTrue(call: ListenerCall): Boolean =
        scalajs.typeOf(call.options) == "boolean" && call.options.asInstanceOf[Boolean]

    private def wheelOptions(call: ListenerCall): Boolean =
        scalajs.typeOf(call.options) == "object" &&
            call.options.asInstanceOf[scalajs.Dynamic].capture.asInstanceOf[Boolean] &&
            !call.options.asInstanceOf[scalajs.Dynamic].passive.asInstanceOf[Boolean]

    final private class LifecycleChronology(events: ArrayBuffer[String]) extends DomBackend.MountDiagnostics:
        def channelClosing(): Unit    = events += "channel-close"
        def drainInterrupting(): Unit = events += "drain-interrupt"
        def drainJoined(): Unit       = events += "drain-joined"
    end LifecycleChronology

    private def mountAndStop(tracker: ListenerTracker, expectedAdded: Int = 13)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < Async =
        for
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("mounted"))))
            _     <- assertEventually(Sync.defer(tracker.added.size == expectedAdded))
            _     <- fiber.interrupt
            _     <- fiber.getResult
            _     <- assertEventually(Sync.defer(tracker.removed.size == tracker.added.size))
        yield ()
    end mountAndStop

    "forwards when the target itself declares the type" in {
        val target = el(dom.document.body, ev = "keydown")
        assert(DomBackend.declaredInChain(target, "keydown"))
    }

    "forwards when only an ancestor declares the type" in {
        // The regression this PR fixes: a keydown declared on an ancestor panel must be forwarded even
        // though the event target carries no data-kyo-ev of its own.
        val ancestor = el(dom.document.body, ev = "keydown")
        val mid      = el(ancestor)
        val target   = el(mid)
        assert(DomBackend.declaredInChain(target, "keydown"))
    }

    "does not forward when no element in the chain declares the type" in {
        val ancestor = el(dom.document.body, ev = "click")
        val target   = el(ancestor)
        assert(!DomBackend.declaredInChain(target, "keydown"))
    }

    "matches individual entries of a comma-separated declaration" in {
        val target = el(dom.document.body, ev = "click,keydown")
        assert(DomBackend.declaredInChain(target, "keydown"))
        assert(DomBackend.declaredInChain(target, "click"))
        assert(!DomBackend.declaredInChain(target, "key"))
    }

    "does not consult a declaration on document.body itself" in {
        dom.document.body.setAttribute("data-kyo-ev", "click")
        val target = el(dom.document.body)
        val result = DomBackend.declaredInChain(target, "click")
        dom.document.body.removeAttribute("data-kyo-ev")
        assert(!result)
    }

    "stops at a non-element parent without crashing" in {
        // documentElement's parent is the document node, which is not an Element, so the walk must end there.
        assert(!DomBackend.declaredInChain(dom.document.documentElement, "click"))
    }

    "returns false for a detached element" in {
        val target = el(null, ev = null)
        assert(!DomBackend.declaredInChain(target, "click"))
    }

    "removes every delegated body listener when the mount scope closes" in {
        Scope.acquireRelease(Sync.defer(new ListenerTracker().install()))(tracker => Sync.defer(tracker.restore())).map { tracker =>
            mountAndStop(tracker).map { _ =>
                val expected = Seq(
                    "click",
                    "input",
                    "change",
                    "submit",
                    "keydown",
                    "keyup",
                    "focus",
                    "blur",
                    "mouseover",
                    "mouseout",
                    "wheel",
                    "beforeinput",
                    "compositionend"
                )
                assert(tracker.added.map(_.eventType).sorted == expected.sorted)
                val nonWheel = tracker.added.filterNot(_.eventType == "wheel")
                assert(nonWheel.size == 12)
                assert(nonWheel.forall(captureTrue))
                val wheel = tracker.added.filter(_.eventType == "wheel")
                assert(wheel.size == 1)
                assert(wheel.forall(wheelOptions))
                assert(tracker.added.filter(c => c.eventType == "beforeinput" || c.eventType == "compositionend").forall(captureTrue))
                assert(tracker.removed.size == tracker.added.size)
                assert(tracker.removed.filterNot(_.eventType == "wheel").forall(captureTrue))
                assert(tracker.removed.filter(_.eventType == "wheel").forall(wheelOptions))
                assert(tracker.removed.zip(tracker.added.reverse).forall((removal, addition) => sameCall(removal, addition)))
                tracker.added.foreach { addition =>
                    assert(tracker.removed.count(removal => sameCall(addition, removal)) == 1)
                }
            }
        }
    }

    "cleans up a partially installed listener set when a later add fails" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(new ListenerTracker("submit", chronology).install()))(tracker =>
            Sync.defer(tracker.restore())
        ).map {
            tracker =>
                for
                    result <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("mounted"), new LifecycleChronology(chronology))))
                        .map(_.getResult)
                    _ <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
                yield
                    assert(result.isPanic)
                    assert(tracker.attempts == Seq("click", "input", "change", "submit"))
                    assert(tracker.added.map(_.eventType) == Seq("click", "input", "change"))
                    assert(tracker.removed.map(_.eventType) == Seq("change", "input", "click"))
                    assert(tracker.removed.size == tracker.added.size)
                    assert(chronology == Seq(
                        "remove:change",
                        "remove:input",
                        "remove:click",
                        "channel-close",
                        "drain-interrupt",
                        "drain-joined"
                    ))
        }
    }

    "tears down listeners before the event channel and drain" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(new ListenerTracker(chronology = chronology).install()))(tracker =>
            Sync.defer(tracker.restore())
        ).map {
            tracker =>
                for
                    fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("mounted"), new LifecycleChronology(chronology))))
                    _     <- assertEventually(Sync.defer(tracker.added.size == 13))
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    _     <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
                yield
                    val channelClose = chronology.indexOf("channel-close")
                    val interrupt    = chronology.indexOf("drain-interrupt")
                    val joined       = chronology.indexOf("drain-joined")
                    assert(chronology.take(channelClose).size == 13)
                    assert(chronology.take(channelClose).forall(_.startsWith("remove:")))
                    assert(channelClose < interrupt)
                    assert(interrupt < joined)
        }
    }

    "returns delegated body listeners to baseline after every mount cycle" in {
        Scope.acquireRelease(Sync.defer(new ListenerTracker().install()))(tracker => Sync.defer(tracker.restore())).map { tracker =>
            Kyo.foreachDiscard(1 to 3) { cycle =>
                mountAndStop(tracker, cycle * 13).map { _ =>
                    assert(tracker.added.size == cycle * 13)
                    assert(tracker.removed.size == cycle * 13)
                    val offset       = (cycle - 1) * 13
                    val cycleAdded   = tracker.added.slice(offset, cycle * 13)
                    val cycleRemoved = tracker.removed.slice(offset, cycle * 13)
                    assert(cycleRemoved.zip(cycleAdded.reverse).forall((removal, addition) => sameCall(removal, addition)))
                    cycleAdded.foreach { addition =>
                        assert(cycleRemoved.count(removal => sameCall(addition, removal)) == 1)
                    }
                }
            }
        }
    }

end DomBackendDelegationTest
