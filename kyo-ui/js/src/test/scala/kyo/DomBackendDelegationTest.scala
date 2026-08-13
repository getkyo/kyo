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

    final private class ListenerTracker:
        val added   = ArrayBuffer.empty[ListenerCall]
        val removed = ArrayBuffer.empty[ListenerCall]

        private val body           = dom.document.body.asInstanceOf[scalajs.Dynamic]
        private val originalAdd    = body.addEventListener
        private val originalRemove = body.removeEventListener

        def install(): ListenerTracker =
            body.updateDynamic("addEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                added += ListenerCall(eventType, listener, options)
                discard(originalAdd.call(body, eventType, listener, options))
            )
            body.updateDynamic("removeEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                removed += ListenerCall(eventType, listener, options)
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
                assert(tracker.removed.size == tracker.added.size)
                assert(tracker.removed.zip(tracker.added.reverse).forall((removal, addition) => sameCall(removal, addition)))
                tracker.added.foreach { addition =>
                    assert(tracker.removed.count(removal => sameCall(addition, removal)) == 1)
                }
            }
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
