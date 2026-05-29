package kyo

import kyo.internal.KeyboardEventData
import kyo.internal.MouseEventData
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange
import scala.language.implicitConversions

/** Phase 2: Typed event payloads. Unit tests dispatching UIEvents directly through ReactiveUI.
  *
  * These tests do NOT use a browser. They normalize a UI tree, subscribe it, dispatch UIEvents directly through the
  * handler returned by ReactiveUI.subscribe, and assert on AtomicRef values set by the typed handlers. This exercises
  * the full server-side typed dispatch path without CDP overhead.
  */
class TypedEventTest extends Test:

    import UI.*

    /** Minimal UIExchange stub that discards onChange notifications. */
    private class NoopExchange extends UIExchange:
        def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async = ()

    /** Normalize a UI, subscribe it with a NoopExchange, and return the dispatch handle. */
    private def makeDispatch(ui: UI)(using Frame): ((Seq[String], UIEvent) => Boolean < Async) < Async =
        for
            root         <- ReactiveUI.normalize(ui, Seq.empty)
            subscription <- ReactiveUI.subscribe(root, new NoopExchange)
        yield subscription.handle

    // ---- Test 1: typed onClick fires with Modifiers.none ----

    "typed onClick fires with modifiers.none when Click dispatched with Modifiers.none" in run {
        for
            fired <- AtomicRef.init(false)
            ui = UI.div.onClick { (mouse: UI.MouseEvent) =>
                fired.set(mouse.modifiers == UI.Modifiers.none)
            }
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Click(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            result   <- fired.get
        yield assert(result)
    }

    // ---- Test 2: typed onClick handler observes shift=true ----

    "typed onClick observes mouse.modifiers.shift == true when wire payload shift is true" in run {
        for
            observed <- AtomicRef.init(false)
            ui = UI.div.onClick { (mouse: UI.MouseEvent) =>
                observed.set(mouse.modifiers.shift)
            }
            dispatch <- makeDispatch(ui)
            shiftData = MouseEventData(UI.Modifiers(shift = true), Absent)
            _      <- dispatch(Seq.empty, UIEvent.Click(Seq.empty, shiftData))
            result <- observed.get
        yield assert(result)
    }

    // ---- Test 3: typed onClick handler observes targetId ----

    "typed onClick handler observes mouse.targetId == Present(id) when wire payload sets targetId" in run {
        for
            captured <- AtomicRef.init(Absent: Maybe[String])
            ui = UI.div.onClick { (mouse: UI.MouseEvent) =>
                captured.set(mouse.targetId)
            }
            dispatch <- makeDispatch(ui)
            clickData = MouseEventData(UI.Modifiers.none, Present("my-button"))
            _      <- dispatch(Seq.empty, UIEvent.Click(Seq.empty, clickData))
            result <- captured.get
        yield assert(result == Present("my-button"))
    }

    // ---- Test 4: both by-name onClick and typed onClick(f) fire ----

    "both onClick(action) and onClick(f) fire on dispatch; typed fires first, then by-name" in run {
        for
            order <- AtomicRef.init(Chunk.empty[String])
            ui = UI.div
                .onClick { (mouse: UI.MouseEvent) =>
                    order.getAndUpdate(_.appended("typed")).unit
                }
                .onClick(order.getAndUpdate(_.appended("byname")).unit)
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Click(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            result   <- order.get
        yield assert(result == Chunk("typed", "byname"))
    }

    // ---- Test 5: bubbling. Parent onClick still fires after child ----

    "bubbling: parent onClick fires after child typed onClick when child does not stop propagation" in run {
        for
            order <- AtomicRef.init(Chunk.empty[String])
            child = UI.button("child").id("btn").onClick { (mouse: UI.MouseEvent) =>
                order.getAndUpdate(_.appended("child")).unit
            }
            parent = UI.div.onClick(order.getAndUpdate(_.appended("parent")).unit)(child)
            dispatch <- makeDispatch(parent)
            // path to child is Seq("0"), the first child of root div
            _      <- dispatch(Seq("0"), UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent)))
            result <- order.get
        yield assert(result == Chunk("child", "parent"))
    }

    // ---- Test 6: renamed KeyboardEvent carries key and Modifiers ----

    "renamed KeyboardEvent: onKeyDown fires correctly for KeyDown with ctrl=true" in run {
        for
            captured <- AtomicRef.init(Absent: Maybe[(UI.Keyboard, Boolean)])
            ui = UI.div.onKeyDown { (ke: UI.KeyboardEvent) =>
                captured.set(Present((ke.key, ke.modifiers.ctrl)))
            }
            dispatch <- makeDispatch(ui)
            kbData = KeyboardEventData("Enter", UI.Modifiers(ctrl = true), Absent)
            _      <- dispatch(Seq.empty, UIEvent.KeyDown(Seq.empty, kbData))
            result <- captured.get
        yield assert(result == Present((UI.Keyboard.Enter, true)))
    }

    // ---- Test 7: handler that throws does not prevent bubbling ----

    "a handler that throws does not prevent the bubble chain from continuing" in run {
        for
            parentFired <- AtomicRef.init(false)
            child = UI.button("child").id("c").onClick { (_: UI.MouseEvent) =>
                Abort.fail(new RuntimeException("handler threw"))
            }
            parent = UI.div.onClick(parentFired.set(true))(child)
            dispatch <- makeDispatch(parent)
            _        <- dispatch(Seq("0"), UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent)))
            result   <- parentFired.get
        yield assert(result)
    }

    // ---- Test 8: UIEvent.Click CanEqual end-to-end ----

    "UIEvent.Click with structurally equal MouseEventData equals another Click" in {
        val a = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
        val b = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
        assert(a == b)
    }

    // ---- Test 9: typed overload typechecks ----

    "typed onClick overload: UI.button.onClick { (mouse: MouseEvent) => ... } typechecks" in {
        val check: UI =
            UI.button("ok").onClick { (mouse: UI.MouseEvent) =>
                Sync.defer(assert(mouse.modifiers == UI.Modifiers.none))
            }
        assert(check.isInstanceOf[UI])
    }

    // ---- Test 10: Form typed onSubmit ----

    "Form.onSubmit(f) typed overload fires with correct MouseEvent" in run {
        for
            captured <- AtomicRef.init(Absent: Maybe[UI.MouseEvent])
            form = UI.form.id("login").onSubmit { (mouse: UI.MouseEvent) =>
                captured.set(Present(mouse))
            }
            dispatch <- makeDispatch(form)
            submitData = MouseEventData(UI.Modifiers.none, Present("login"))
            _      <- dispatch(Seq.empty, UIEvent.Submit(Seq.empty, submitData))
            result <- captured.get
        yield
            assert(result.nonEmpty)
            assert(result.get.modifiers == UI.Modifiers.none)
            assert(result.get.targetId == Present("login"))
    }

    // ---- Test 11: typed onFocus overload ----

    "typed onFocus overload fires with correct MouseEvent on UIEvent.Focus dispatch" in run {
        for
            captured <- AtomicRef.init(Absent: Maybe[UI.MouseEvent])
            ui = UI.div.tabIndex(0).onFocus { (mouse: UI.MouseEvent) =>
                captured.set(Present(mouse))
            }
            dispatch <- makeDispatch(ui)
            focusData = MouseEventData(UI.Modifiers.none, Present("my-input"))
            _      <- dispatch(Seq.empty, UIEvent.Focus(Seq.empty, focusData))
            result <- captured.get
        yield
            assert(result.nonEmpty)
            assert(result.get.targetId == Present("my-input"))
    }

    // ---- Test 12: typed onBlur overload ----

    "typed onBlur overload fires with correct MouseEvent on UIEvent.Blur dispatch" in run {
        for
            captured <- AtomicRef.init(Absent: Maybe[UI.MouseEvent])
            ui = UI.div.onBlur { (mouse: UI.MouseEvent) =>
                captured.set(Present(mouse))
            }
            dispatch <- makeDispatch(ui)
            blurData = MouseEventData(UI.Modifiers.none, Present("my-input"))
            _      <- dispatch(Seq.empty, UIEvent.Blur(Seq.empty, blurData))
            result <- captured.get
        yield
            assert(result.nonEmpty)
            assert(result.get.targetId == Present("my-input"))
    }

end TypedEventTest
