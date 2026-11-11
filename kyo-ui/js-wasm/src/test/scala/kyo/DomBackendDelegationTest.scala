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

    final private class ListenerTracker(
        failOnAdd: String = null,
        failOnDocumentAdd: String = null,
        chronology: ArrayBuffer[String] = ArrayBuffer.empty
    ):
        val added           = ArrayBuffer.empty[ListenerCall]
        val removed         = ArrayBuffer.empty[ListenerCall]
        val documentAdded   = ArrayBuffer.empty[ListenerCall]
        val documentRemoved = ArrayBuffer.empty[ListenerCall]
        val attempts        = ArrayBuffer.empty[String]
        var activeTimers    = 0

        private val body                   = dom.document.body.asInstanceOf[scalajs.Dynamic]
        private val document               = dom.document.asInstanceOf[scalajs.Dynamic]
        private val window                 = dom.window.asInstanceOf[scalajs.Dynamic]
        private val originalAdd            = body.addEventListener
        private val originalRemove         = body.removeEventListener
        private val originalDocumentAdd    = document.addEventListener
        private val originalDocumentRemove = document.removeEventListener
        private val originalSetInterval    = window.setInterval
        private val originalClearInterval  = window.clearInterval

        def install(): ListenerTracker =
            body.updateDynamic("addEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                attempts += eventType
                if eventType == failOnAdd then throw new scalajs.JavaScriptException(s"failed add: $eventType")
                else
                    discard(originalAdd.call(body, eventType, listener, options))
                    added += ListenerCall(eventType, listener, options)
                end if
            )
            body.updateDynamic("removeEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                discard(originalRemove.call(body, eventType, listener, options))
                removed += ListenerCall(eventType, listener, options)
                chronology += s"remove:$eventType"
            )
            document.updateDynamic("addEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                if eventType == failOnDocumentAdd then throw new scalajs.JavaScriptException(s"failed document add: $eventType")
                else
                    discard(originalDocumentAdd.call(document, eventType, listener, options))
                    documentAdded += ListenerCall(eventType, listener, options)
            )
            document.updateDynamic("removeEventListener")((eventType: String, listener: scalajs.Any, options: scalajs.Any) =>
                discard(originalDocumentRemove.call(document, eventType, listener, options))
                documentRemoved += ListenerCall(eventType, listener, options)
                chronology += s"remove-document:$eventType"
            )
            window.updateDynamic("setInterval")((callback: scalajs.Any, millis: scalajs.Any) =>
                activeTimers += 1
                originalSetInterval.call(window, callback, millis)
            )
            window.updateDynamic("clearInterval")((id: scalajs.Any) =>
                discard(originalClearInterval.call(window, id))
                activeTimers -= 1
                chronology += "clear-interval"
            )
            this
        end install

        def restore(): Unit =
            body.updateDynamic("addEventListener")(originalAdd)
            body.updateDynamic("removeEventListener")(originalRemove)
            document.updateDynamic("addEventListener")(originalDocumentAdd)
            document.updateDynamic("removeEventListener")(originalDocumentRemove)
            window.updateDynamic("setInterval")(originalSetInterval)
            window.updateDynamic("clearInterval")(originalClearInterval)
        end restore
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
        def channelClosed(): Unit     = events += "channel-close"
        def drainInterrupting(): Unit = events += "drain-interrupt"
        def drainJoined(): Unit       = events += "drain-joined"
    end LifecycleChronology

    private def mountAndStop(tracker: ListenerTracker, expectedAdded: Int = 28)(using
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
                    "dragstart",
                    "dragenter",
                    "dragover",
                    "dragleave",
                    "drop",
                    "dragend",
                    "pointerdown",
                    "pointermove",
                    "pointerup",
                    "pointercancel",
                    "touchstart",
                    "touchmove",
                    "touchend",
                    "touchcancel",
                    "keydown",
                    "wheel",
                    "beforeinput",
                    "compositionend"
                )
                assert(tracker.added.map(_.eventType).sorted == expected.sorted)
                val nonWheel = tracker.added.filterNot(_.eventType == "wheel")
                assert(nonWheel.size == 27)
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
                assert(tracker.documentAdded.map(_.eventType) == Seq("kyo:resolve-drag"))
                assert(tracker.documentRemoved.size == 1)
                assert(sameCall(tracker.documentRemoved.head, tracker.documentAdded.head))
                assert(tracker.activeTimers == 0)
            }
        }
    }

    "cleans up a partially installed listener set when a later add fails" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(new ListenerTracker(failOnAdd = "submit", chronology = chronology).install()))(tracker =>
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
                    _     <- assertEventually(Sync.defer(tracker.added.size == 28))
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    _     <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
                yield
                    val channelClose = chronology.indexOf("channel-close")
                    val interrupt    = chronology.indexOf("drain-interrupt")
                    val joined       = chronology.indexOf("drain-joined")
                    assert(chronology.take(channelClose).size == 30)
                    assert(chronology.take(channelClose).head == "clear-interval")
                    assert(chronology.take(channelClose)(1) == "remove-document:kyo:resolve-drag")
                    assert(chronology.take(channelClose).drop(2).forall(_.startsWith("remove:")))
                    assert(channelClose < interrupt)
                    assert(interrupt < joined)
        }
    }

    "interrupts and joins an active delegated event handler during teardown" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(new ListenerTracker(chronology = chronology).install()))(tracker =>
            Sync.defer(tracker.restore())
        ).map { tracker =>
            for
                entered   <- Latch.init(1)
                blocker   <- Latch.init(1)
                finalized <- Latch.init(1)
                handler = Sync.ensure(finalized.release)(entered.release.andThen(blocker.await))
                fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.button("active").id("active-handler").onClick(handler),
                    new LifecycleChronology(chronology)
                )))
                _ <- assertEventually(Sync.defer(tracker.added.size == 28))
                _ <- Sync.defer {
                    val button = dom.document.getElementById("active-handler")
                    val event  = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].MouseEvent)(
                        "click",
                        scalajs.Dynamic.literal(bubbles = true)
                    )
                    discard(button.asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                }
                _ <- entered.await
                _ <- fiber.interrupt
                _ <- finalized.await
                _ <- fiber.getResult
                // The fiber's result settles once its abandonment has released what it held; the mount scope's own
                // finalizers, the join among them, run detached from it, so the chronology is complete only once the
                // join has been recorded.
                _ <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
            yield
                val channelClose = chronology.indexOf("channel-close")
                val interrupt    = chronology.indexOf("drain-interrupt")
                val joined       = chronology.indexOf("drain-joined")
                assert(tracker.removed.size == 28)
                assert(chronology.take(channelClose).size == 30)
                assert(chronology.take(channelClose).head == "clear-interval")
                assert(chronology.take(channelClose)(1) == "remove-document:kyo:resolve-drag")
                assert(chronology.take(channelClose).drop(2).forall(_.startsWith("remove:")))
                assert(channelClose < interrupt)
                assert(interrupt < joined)
        }
    }

    "returns delegated body listeners to baseline after every mount cycle" in {
        Scope.acquireRelease(Sync.defer(new ListenerTracker().install()))(tracker => Sync.defer(tracker.restore())).map { tracker =>
            Kyo.foreachDiscard(1 to 3) { cycle =>
                mountAndStop(tracker, cycle * 28).map { _ =>
                    assert(tracker.added.size == cycle * 28)
                    assert(tracker.removed.size == cycle * 28)
                    val offset       = (cycle - 1) * 28
                    val cycleAdded   = tracker.added.slice(offset, cycle * 28)
                    val cycleRemoved = tracker.removed.slice(offset, cycle * 28)
                    assert(cycleRemoved.zip(cycleAdded.reverse).forall((removal, addition) => sameCall(removal, addition)))
                    cycleAdded.foreach { addition =>
                        assert(cycleRemoved.count(removal => sameCall(addition, removal)) == 1)
                    }
                }
            }
        }
    }

    "rolls back existing and drag listeners when a late drag listener add fails" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(new ListenerTracker(failOnAdd = "drop", chronology = chronology).install()))(tracker =>
            Sync.defer(tracker.restore())
        ).map { tracker =>
            for
                result <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("mounted"), new LifecycleChronology(chronology))))
                    .map(_.getResult)
                _ <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
            yield
                assert(result.isPanic)
                assert(tracker.attempts.takeRight(5) == Seq("dragstart", "dragenter", "dragover", "dragleave", "drop"))
                assert(tracker.added.size == 17)
                assert(tracker.removed.size == tracker.added.size)
                assert(tracker.removed.zip(tracker.added.reverse).forall((removal, addition) => sameCall(removal, addition)))
                assert(tracker.documentAdded.isEmpty)
                assert(tracker.activeTimers == 0)
                val channelClose = chronology.indexOf("channel-close")
                assert(chronology.take(channelClose).forall(_.startsWith("remove:")))
        }
    }

    "rolls back all body listeners when the resolution listener add fails" in {
        val chronology = ArrayBuffer.empty[String]
        Scope.acquireRelease(Sync.defer(
            new ListenerTracker(failOnDocumentAdd = "kyo:resolve-drag", chronology = chronology).install()
        ))(tracker => Sync.defer(tracker.restore())).map { tracker =>
            for
                result <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("mounted"), new LifecycleChronology(chronology))))
                    .map(_.getResult)
                _ <- assertEventually(Sync.defer(chronology.contains("drain-joined")))
            yield
                assert(result.isPanic)
                assert(tracker.added.size == 28)
                assert(tracker.removed.size == tracker.added.size)
                assert(tracker.removed.zip(tracker.added.reverse).forall((removal, addition) => sameCall(removal, addition)))
                assert(tracker.documentAdded.isEmpty)
                assert(tracker.documentRemoved.isEmpty)
                assert(tracker.activeTimers == 0)
                val channelClose = chronology.indexOf("channel-close")
                assert(chronology.take(channelClose).size == 28)
                assert(chronology.take(channelClose).forall(_.startsWith("remove:")))
        }
    }

    "an anchor with a handler keeps the browser's default on a modified click" in {
        for
            ready   <- Sync.defer(new DomTestEnv.MountReady)
            clicked <- Latch.init(2)
            fiber   <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                UI.div(UI.a.href(UI.Href.Path("/target")).id("modified-click").onClick(clicked.release)("Link")),
                ready
            )))
            _         <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("modified-click") != null))
            prevented <- Sync.defer {
                val target                        = dom.document.getElementById("modified-click")
                def click(ctrl: Boolean): Boolean =
                    val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].MouseEvent)(
                        "click",
                        scalajs.Dynamic.literal(bubbles = true, cancelable = true, ctrlKey = ctrl)
                    )
                    discard(target.asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                    event.defaultPrevented.asInstanceOf[Boolean]
                end click
                (click(false), click(true))
            }
            // Both clicks reached the handler; only the plain one had its default taken.
            _ <- clicked.await
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield
            assert(prevented._1)
            assert(!prevented._2)
        end for
    }

    "suppresses a scroll key on a target whose isContentEditable the DOM does not define" in {
        // `scrollKeyPrevented` consults `isContentEditable`, which is an HTMLElement member: an SVG target does not
        // carry it, and jsdom implements contentEditable on no element at all, so it reads as undefined. Reading it
        // as a plain Boolean threw a ClassCastException out of the keydown listener, which lost both the
        // preventDefault below and every other event the listener would have delegated.
        for
            ready <- Sync.defer(new DomTestEnv.MountReady)
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                UI.div(UI.button("nav").id("scroll-key-target").tabIndex(0).preventScrollKeys),
                ready
            )))
            _         <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("scroll-key-target") != null))
            prevented <- Sync.defer {
                val target = dom.document.getElementById("scroll-key-target")
                assert(scalajs.isUndefined(target.asInstanceOf[scalajs.Dynamic].isContentEditable))
                val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                    "keydown",
                    scalajs.Dynamic.literal(key = "ArrowDown", bubbles = true, cancelable = true)
                )
                discard(target.asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                event.defaultPrevented.asInstanceOf[Boolean]
            }
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield assert(prevented)
        end for
    }

    "selector mounts dispatch clicks only to their own tree" in {
        Scope.run {
            for
                leftCount    <- AtomicInt.init(0)
                rightCount   <- AtomicInt.init(0)
                leftBarrier  <- Latch.init(1)
                rightBarrier <- Latch.init(1)
                leftReady    <- Sync.defer(new DomTestEnv.MountReady)
                rightReady   <- Sync.defer(new DomTestEnv.MountReady)
                _            <- Sync.defer {
                    dom.document.body.innerHTML = "<div id='left-mount'></div><div id='right-mount'></div>"
                }
                left <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.button("left").id("left-click").onClick(leftCount.incrementAndGet.unit))
                        .onKeyUp(_ => leftBarrier.release),
                    "#left-mount",
                    leftReady
                )))
                _     <- Scope.ensure(left.interrupt.andThen(left.getResult).unit)
                right <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.button("right").id("right-click").onClick(rightCount.incrementAndGet.unit))
                        .onKeyUp(_ => rightBarrier.release),
                    "#right-mount",
                    rightReady
                )))
                _ <- Scope.ensure(right.interrupt.andThen(right.getResult).unit)
                _ <- assertEventually(Sync.defer(leftReady.installed && rightReady.installed))
                _ <- Sync.defer {
                    dom.document.getElementById("left-click").asInstanceOf[dom.html.Button].click()
                    Seq("left-click", "right-click").foreach { id =>
                        val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                            "keyup",
                            scalajs.Dynamic.literal(key = "x", bubbles = true)
                        )
                        discard(dom.document.getElementById(id).asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                    }
                }
                _          <- leftBarrier.await
                _          <- rightBarrier.await
                leftValue  <- leftCount.get
                rightValue <- rightCount.get
            yield assert((leftValue, rightValue) == (1, 0), s"Observed counts: $leftValue, $rightValue")
        }
    }

    "nested selector mounts do not dispatch into the enclosing tree" in {
        Scope.run {
            for
                outerCount   <- AtomicInt.init(0)
                innerCount   <- AtomicInt.init(0)
                outerBarrier <- Latch.init(1)
                innerBarrier <- Latch.init(1)
                outerReady   <- Sync.defer(new DomTestEnv.MountReady)
                innerReady   <- Sync.defer(new DomTestEnv.MountReady)
                _            <- Sync.defer {
                    dom.document.body.innerHTML = "<div id='outer-mount'></div>"
                }
                outer <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.div("placeholder").id("inner-mount"), UI.button("marker").id("outer-marker"))
                        .onClick(outerCount.incrementAndGet.unit).onKeyUp(_ => outerBarrier.release),
                    "#outer-mount",
                    outerReady
                )))
                _     <- Scope.ensure(outer.interrupt.andThen(outer.getResult).unit)
                _     <- assertEventually(Sync.defer(outerReady.installed))
                inner <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.button("inner").id("inner-click").onClick(innerCount.incrementAndGet.unit))
                        .onKeyUp(_ => innerBarrier.release),
                    "#inner-mount",
                    innerReady
                )))
                _ <- Scope.ensure(inner.interrupt.andThen(inner.getResult).unit)
                _ <- assertEventually(Sync.defer(innerReady.installed))
                _ <- Sync.defer {
                    dom.document.getElementById("inner-click").asInstanceOf[dom.html.Button].click()
                    Seq("outer-marker", "inner-click").foreach { id =>
                        val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                            "keyup",
                            scalajs.Dynamic.literal(key = "x", bubbles = true)
                        )
                        discard(dom.document.getElementById(id).asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                    }
                }
                _          <- outerBarrier.await
                _          <- innerBarrier.await
                outerValue <- outerCount.get
                innerValue <- innerCount.get
            yield assert((outerValue, innerValue) == (0, 1), s"Observed counts: $outerValue, $innerValue")
        }
    }

    "selector mounts leave outside input masking and scroll keys alone" in {
        Scope.run {
            for
                ready <- Sync.defer(new DomTestEnv.MountReady)
                _     <- Sync.defer {
                    dom.document.body.innerHTML =
                        "<div id='owned-mount'></div><div data-kyo-scroll-keys><input id='outside-input' data-kyo-mask='999'></div>"
                }
                fiber  <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("owned"), "#owned-mount", ready)))
                _      <- Scope.ensure(fiber.interrupt.andThen(fiber.getResult).unit)
                _      <- assertEventually(Sync.defer(ready.installed))
                result <- Sync.defer {
                    val target = dom.document.getElementById("outside-input").asInstanceOf[scalajs.Dynamic]
                    val input  = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].InputEvent)(
                        "beforeinput",
                        scalajs.Dynamic.literal(inputType = "insertText", data = "1", bubbles = true, cancelable = true)
                    )
                    val key = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                        "keydown",
                        scalajs.Dynamic.literal(key = "ArrowDown", bubbles = true, cancelable = true)
                    )
                    discard(target.dispatchEvent(input))
                    discard(target.dispatchEvent(key))
                    (
                        input.defaultPrevented.asInstanceOf[Boolean],
                        key.defaultPrevented.asInstanceOf[Boolean],
                        target.value.asInstanceOf[String]
                    )
                }
            yield assert(result == (false, false, ""), s"Observed input: $result")
        }
    }

    "selector mounts do not inherit event declarations from outside ancestors" in {
        Scope.run {
            for
                ready <- Sync.defer(new DomTestEnv.MountReady)
                _     <- Sync.defer {
                    dom.document.body.innerHTML =
                        "<div data-kyo-ev='click' data-kyo-scroll-keys><div id='bounded-mount'></div></div>"
                }
                fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.a("link").href(UI.Href.Path("#destination")).id("owned-link"), UI.button("key").id("owned-key")),
                    "#bounded-mount",
                    ready
                )))
                _         <- Scope.ensure(fiber.interrupt.andThen(fiber.getResult).unit)
                _         <- assertEventually(Sync.defer(ready.installed))
                prevented <- Sync.defer {
                    val click = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].MouseEvent)(
                        "click",
                        scalajs.Dynamic.literal(bubbles = true, cancelable = true)
                    )
                    val key = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                        "keydown",
                        scalajs.Dynamic.literal(key = "ArrowDown", bubbles = true, cancelable = true)
                    )
                    discard(dom.document.getElementById("owned-link").asInstanceOf[scalajs.Dynamic].dispatchEvent(click))
                    discard(dom.document.getElementById("owned-key").asInstanceOf[scalajs.Dynamic].dispatchEvent(key))
                    (click.defaultPrevented.asInstanceOf[Boolean], key.defaultPrevented.asInstanceOf[Boolean])
                }
            yield assert(prevented == (false, false), s"Observed prevention: $prevented")
        }
    }

    "closing a replaced mount preserves the replacement's events" in {
        Scope.run {
            for
                count       <- AtomicInt.init(0)
                barrier     <- Latch.init(1)
                oldReady    <- Sync.defer(new DomTestEnv.MountReady)
                newReady    <- Sync.defer(new DomTestEnv.MountReady)
                _           <- Sync.defer { dom.document.body.innerHTML = "<div id='replacement-mount'></div>" }
                old         <- Fiber.initUnscoped(Scope.run(DomBackend.mount(UI.div("old"), "#replacement-mount", oldReady)))
                _           <- Scope.ensure(old.interrupt.andThen(old.getResult).unit)
                _           <- assertEventually(Sync.defer(oldReady.installed))
                replacement <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.button("new").id("replacement-click").onClick(count.incrementAndGet.unit))
                        .onKeyUp(_ => barrier.release),
                    "#replacement-mount",
                    newReady
                )))
                _ <- Scope.ensure(replacement.interrupt.andThen(replacement.getResult).unit)
                _ <- assertEventually(Sync.defer(newReady.installed))
                _ <- old.interrupt
                _ <- old.getResult
                _ <- Sync.defer {
                    val target = dom.document.getElementById("replacement-click").asInstanceOf[dom.html.Button]
                    target.click()
                    val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                        "keyup",
                        scalajs.Dynamic.literal(key = "x", bubbles = true)
                    )
                    discard(target.asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                }
                _     <- barrier.await
                value <- count.get
            yield assert(value == 1)
        }
    }

    "closed nested mounts keep leftover DOM outside the parent's event tree" in {
        Scope.run {
            for
                count      <- AtomicInt.init(0)
                barrier    <- Latch.init(1)
                outerReady <- Sync.defer(new DomTestEnv.MountReady)
                innerReady <- Sync.defer(new DomTestEnv.MountReady)
                _          <- Sync.defer { dom.document.body.innerHTML = "<div id='closed-outer'></div>" }
                outer      <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.div("placeholder").id("closed-inner"), UI.button("marker").id("closed-marker"))
                        .onClick(count.incrementAndGet.unit).onKeyUp(_ => barrier.release),
                    "#closed-outer",
                    outerReady
                )))
                _     <- Scope.ensure(outer.interrupt.andThen(outer.getResult).unit)
                _     <- assertEventually(Sync.defer(outerReady.installed))
                inner <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.button("leftover").id("closed-click"),
                    "#closed-inner",
                    innerReady
                )))
                _ <- Scope.ensure(inner.interrupt.andThen(inner.getResult).unit)
                _ <- assertEventually(Sync.defer(innerReady.installed))
                _ <- inner.interrupt
                _ <- inner.getResult
                _ <- Sync.defer {
                    dom.document.getElementById("closed-click").asInstanceOf[dom.html.Button].click()
                    val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                        "keyup",
                        scalajs.Dynamic.literal(key = "x", bubbles = true)
                    )
                    discard(dom.document.getElementById("closed-marker").asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                }
                _     <- barrier.await
                value <- count.get
            yield assert(value == 0)
        }
    }

    "raw HTML in a nested mount cannot borrow the container's outer event path" in {
        Scope.run {
            for
                count      <- AtomicInt.init(0)
                barrier    <- Latch.init(1)
                outerReady <- Sync.defer(new DomTestEnv.MountReady)
                innerReady <- Sync.defer(new DomTestEnv.MountReady)
                _          <- Sync.defer { dom.document.body.innerHTML = "<div id='raw-outer'></div>" }
                outer      <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.div(UI.div("spacer"), UI.div("placeholder").id("raw-inner")),
                    "#raw-outer",
                    outerReady
                )))
                _     <- Scope.ensure(outer.interrupt.andThen(outer.getResult).unit)
                _     <- assertEventually(Sync.defer(outerReady.installed))
                inner <- Fiber.initUnscoped(Scope.run(DomBackend.mount(
                    UI.Ast.Reactive(Signal.initConst(UI.fragment(
                        UI.rawHtml("<button id='raw-click'>raw</button>"),
                        UI.button("marker").id("raw-marker").onClick(count.incrementAndGet.unit).onKeyUp(_ => barrier.release)
                    ))),
                    "#raw-inner",
                    innerReady
                )))
                _ <- Scope.ensure(inner.interrupt.andThen(inner.getResult).unit)
                _ <- assertEventually(Sync.defer(innerReady.installed))
                _ <- Sync.defer {
                    dom.document.getElementById("raw-click").asInstanceOf[dom.html.Button].click()
                    val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].KeyboardEvent)(
                        "keyup",
                        scalajs.Dynamic.literal(key = "x", bubbles = true)
                    )
                    discard(dom.document.getElementById("raw-marker").asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
                }
                _     <- barrier.await
                value <- count.get
            yield assert(value == 0, s"Observed clicks: $value")
        }
    }

end DomBackendDelegationTest
