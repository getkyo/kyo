package kyo

import kyo.internal.KeyboardEventData
import kyo.internal.MouseEventData
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange
import scala.language.implicitConversions

/** Typed event payloads. Unit tests dispatching UIEvents directly through ReactiveUI.
  *
  * These tests do NOT use a browser. They normalize a UI tree, subscribe it, dispatch UIEvents directly through the
  * handler returned by ReactiveUI.subscribe, and assert on AtomicRef values set by the typed handlers. This exercises
  * the full server-side typed dispatch path without CDP overhead.
  */
class TypedEventTest extends kyo.test.Test[Any]:

    import UI.*

    /** Minimal UIExchange stub that discards onChange notifications. */
    private class NoopExchange extends UIExchange:
        def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async = ()

    /** Normalize a UI, subscribe it with a NoopExchange, and return the dispatch handle. The dispatch handle re-reads
      * the current signal state on each event, so it stays valid after the subscription's Scope closes; these tests
      * exercise event routing only, so the subscription runs under a local Scope.run that discharges its Scope row.
      */
    private def makeDispatch(ui: UI)(using Frame): ((Seq[String], UIEvent) => Boolean < Async) < Async =
        Scope.run {
            for
                root         <- ReactiveUI.normalize(ui, Seq.empty)
                subscription <- ReactiveUI.subscribe(root, new NoopExchange)
            yield subscription.handle
        }

    // ---- typed onClick fires with Modifiers.none ----

    "typed onClick fires with modifiers.none when Click dispatched with Modifiers.none" in {
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

    // ---- typed onClick handler observes shift=true ----

    "typed onClick observes mouse.modifiers.shift == true when wire payload shift is true" in {
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

    // ---- typed onClick handler observes targetId ----

    "typed onClick handler observes mouse.targetId == Present(id) when wire payload sets targetId" in {
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

    // ---- both by-name onClick and typed onClick(f) fire ----

    "both onClick(action) and onClick(f) fire on dispatch; typed fires first, then by-name" in {
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

    // ---- bubbling. Parent onClick still fires after child ----

    "bubbling: parent onClick fires after child typed onClick when child does not stop propagation" in {
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

    // ---- renamed KeyboardEvent carries key and Modifiers ----

    "renamed KeyboardEvent: onKeyDown fires correctly for KeyDown with ctrl=true" in {
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

    // ---- handler that throws does not prevent bubbling ----

    "a handler that throws does not prevent the bubble chain from continuing" in {
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

    // ---- UIEvent.Click CanEqual end-to-end ----

    "UIEvent.Click with structurally equal MouseEventData equals another Click" in {
        val a = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
        val b = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
        assert(a == b)
    }

    // ---- typed overload typechecks ----

    "typed onClick overload: UI.button.onClick { (mouse: MouseEvent) => ... } typechecks" in {
        val check: UI =
            UI.button("ok").onClick { (mouse: UI.MouseEvent) =>
                Sync.defer(assert(mouse.modifiers == UI.Modifiers.none))
            }
        assert(check.isInstanceOf[UI])
    }

    // ---- Form typed onSubmit ----

    "Form.onSubmit(f) typed overload fires with correct MouseEvent" in {
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

    // ---- typed onFocus overload ----

    "typed onFocus overload fires with correct MouseEvent on UIEvent.Focus dispatch" in {
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

    // ---- typed onBlur overload ----

    "typed onBlur overload fires with correct MouseEvent on UIEvent.Blur dispatch" in {
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

    // ---- dispatch resolves the click target through a Reactive boundary ----
    //
    // A signal-typed setter such as `.disabled(Signal)` wraps its element in a `Reactive`. The
    // server-side dispatch must still recognise the wrapped element by its concrete type (Button) and
    // read its current attributes (disabled), otherwise a submit button wrapped in `.disabled(valid)`
    // (the canonical "enable submit only when valid" pattern) no longer submits the form. Regression
    // for that bug.

    "Form.onSubmit fires when a Click targets a submit button wrapped in .disabled(Signal=false)" in {
        for
            fired    <- AtomicRef.init(false)
            disabled <- Signal.initRef(false)
            form = UI.form.id("f").onSubmit(fired.set(true))(
                UI.input.id("x"),
                UI.button("Go").id("go").disabled(disabled)
            )
            dispatch <- makeDispatch(form)
            // The button (index 1) is wrapped in a Reactive, so its path is the wrapper's path Seq("1").
            _      <- dispatch(Seq("1"), UIEvent.Click(Seq("1"), MouseEventData(UI.Modifiers.none, Present("go"))))
            result <- fired.get
        yield assert(result)
    }

    "Form.onSubmit does NOT fire when the wrapped submit button is disabled via Signal=true" in {
        for
            fired    <- AtomicRef.init(false)
            disabled <- Signal.initRef(true)
            form = UI.form.id("f").onSubmit(fired.set(true))(
                UI.input.id("x"),
                UI.button("Go").id("go").disabled(disabled)
            )
            dispatch <- makeDispatch(form)
            _        <- dispatch(Seq("1"), UIEvent.Click(Seq("1"), MouseEventData(UI.Modifiers.none, Present("go"))))
            result   <- fired.get
        yield assert(!result)
    }

    "Form.onSubmit fires after a disabled-wrapped submit button is re-enabled by its Signal" in {
        for
            fired    <- AtomicRef.init(false)
            disabled <- Signal.initRef(true)
            form = UI.form.id("f").onSubmit(fired.set(true))(
                UI.input.id("x"),
                UI.button("Go").id("go").disabled(disabled)
            )
            dispatch <- makeDispatch(form)
            _        <- dispatch(Seq("1"), UIEvent.Click(Seq("1"), MouseEventData(UI.Modifiers.none, Present("go"))))
            blocked  <- fired.get
            _        <- disabled.set(false)
            _        <- dispatch(Seq("1"), UIEvent.Click(Seq("1"), MouseEventData(UI.Modifiers.none, Present("go"))))
            allowed  <- fired.get
        yield
            assert(!blocked)
            assert(allowed)
    }

end TypedEventTest
