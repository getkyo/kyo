package kyo

import kyo.internal.HtmlRenderer
import kyo.internal.MouseEventData
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange
import scala.language.implicitConversions

/** End-to-end wiring of the onHover/onUnhover/onScroll events.
  *
  * These tests do NOT use a browser. They normalize a UI tree, subscribe it, dispatch the new UIEvents directly through
  * the handler returned by ReactiveUI.subscribe, and assert on AtomicRef values set by the handlers. They cover BOTH
  * HTML and SVG elements, which share the `Interactive` trait, plus the SSR `data-kyo-ev` emission and the wire-payload
  * round-trip for the redefined `UIEvent.Scroll`/`Hover`/`Unhover` cases.
  */
class UIEventWiringTest extends kyo.test.Test[Any]:

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

    // ---- onHover action fires (HTML) ----

    "onHover(action) fires on Hover dispatch (HTML div)" in {
        for
            ref <- AtomicRef.init(false)
            ui = UI.div.onHover(ref.set(true))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            result   <- ref.get
        yield assert(result)
    }

    // ---- onHover payload MouseEvent (HTML) ----

    "onHover(f) receives MouseEvent with targetId (HTML div)" in {
        for
            ref <- AtomicRef.init(Absent: Maybe[String])
            ui = UI.div.id("x").onHover((e: UI.MouseEvent) => ref.set(e.targetId))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Present("x"))))
            result   <- ref.get
        yield assert(result == Present("x"))
    }

    // ---- onUnhover fires (HTML) ----

    "onUnhover(action) fires on Unhover dispatch (HTML div)" in {
        for
            ref <- AtomicRef.init(false)
            ui = UI.div.onUnhover(ref.set(true))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Unhover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            result   <- ref.get
        yield assert(result)
    }

    // ---- onScroll WheelEvent deltaY (HTML) ----

    "onScroll(f) receives WheelEvent deltaY (HTML div)" in {
        for
            ref <- AtomicRef.init(0.0)
            ui = UI.div.onScroll((w: UI.WheelEvent) => ref.set(w.deltaY))
            dispatch <- makeDispatch(ui)
            _ <- dispatch(
                Seq.empty,
                UIEvent.Scroll(Seq.empty, deltaX = 0.0, deltaY = 42.0, modifiers = UI.Modifiers.none, targetId = Absent)
            )
            result <- ref.get
        yield assert(result == 42.0)
    }

    // ---- onHover fires on SVG element ----

    "onHover(action) fires on an SVG circle (shared Interactive)" in {
        for
            ref <- AtomicRef.init(false)
            ui = Svg.circle.cx(1).cy(1).r(1).onHover(ref.set(true))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            result   <- ref.get
        yield assert(result)
    }

    // ---- onScroll SVG rect both deltas ----

    "onScroll(f) receives both deltas on an SVG rect" in {
        for
            ref <- AtomicRef.init((0.0, 0.0))
            ui = Svg.rect.onScroll((w: UI.WheelEvent) => ref.set((w.deltaX, w.deltaY)))
            dispatch <- makeDispatch(ui)
            _ <-
                dispatch(Seq.empty, UIEvent.Scroll(Seq.empty, deltaX = 3.0, deltaY = 5.0, modifiers = UI.Modifiers.none, targetId = Absent))
            result <- ref.get
        yield assert(result == (3.0, 5.0))
    }

    // ---- data-kyo-ev emits the 3 events ----

    "data-kyo-ev emits mouseover, mouseout, and wheel for the 3 setters" in {
        val ui = UI.div.onHover(()).onUnhover(()).onScroll(())
        for html <- HtmlRenderer.render(ui, Seq.empty)
        yield
            assert(html.contains("data-kyo-ev"))
            assert(html.contains("mouseover"))
            assert(html.contains("mouseout"))
            assert(html.contains("wheel"))
        end for
    }

    // ---- no event attr when no handler ----

    "no data-kyo-ev attribute when an SVG rect has no handlers" in {
        val ui = Svg.rect
        for html <- HtmlRenderer.render(ui, Seq.empty)
        yield assert(!html.contains("data-kyo-ev"))
    }

    // ---- hover handler error does not break the row ----

    "a hover handler error does not re-throw; dispatch returns true" in {
        // The child's failing handler runs through safeDispatch, which recovers and keeps bubbling.
        val child = UI.div.id("c").onHover { (_: UI.MouseEvent) =>
            Abort.fail(new RuntimeException("Boom"))
        }
        val parent = UI.div(child)
        for
            dispatch <- makeDispatch(parent)
            result   <- dispatch(Seq("0"), UIEvent.Hover(Seq("0"), MouseEventData(UI.Modifiers.none, Absent)))
        yield assert(result)
        end for
    }

    // ---- onClick + onHover coexist ----

    "onClick and onHover coexist on the same element" in {
        for
            clickRef <- AtomicRef.init(0)
            hoverRef <- AtomicRef.init(false)
            ui = UI.div.onClick(clickRef.getAndUpdate(_ + 1).unit).onHover(hoverRef.set(true))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Click(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            _        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            clicks   <- clickRef.get
            hovered  <- hoverRef.get
        yield
            assert(clicks == 1)
            assert(hovered)
    }

    // ---- WheelEvent ctrl modifier ----

    "onScroll(f) observes the ctrl modifier (ctrl-wheel zoom)" in {
        for
            ref <- AtomicRef.init(false)
            ui = UI.div.onScroll((w: UI.WheelEvent) => ref.set(w.modifiers.ctrl))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Scroll(Seq.empty, 0.0, 0.0, UI.Modifiers(ctrl = true), Absent))
            result   <- ref.get
        yield assert(result)
    }

    // ---- no inert stub remains ----

    "the 3 events return true with no handler registered (real arm, not a stub)" in {
        for
            dispatch <- makeDispatch(UI.div)
            h        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            u        <- dispatch(Seq.empty, UIEvent.Unhover(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
            s        <- dispatch(Seq.empty, UIEvent.Scroll(Seq.empty, 0.0, 0.0, UI.Modifiers.none, Absent))
        yield
            assert(h)
            assert(u)
            assert(s)
    }

    // ---- Hover via ancestor data-kyo-ev gate ----

    "only the div carrying onHover gets mouseover; the child span does not" in {
        val ui = UI.div.onHover(Sync.defer(()))(UI.span("child"))
        for html <- HtmlRenderer.render(ui, Seq.empty)
        yield
            assert(html.contains("mouseover"))
            // the inner span has no handler; only one data-kyo-ev attribute is emitted
            assert(html.split("data-kyo-ev").length - 1 == 1)
        end for
    }

    // ---- UIEvent.Scroll wire round-trip ----

    "UIEvent.Scroll round-trips through the JSON wire codec" in {
        val scroll  = UIEvent.Scroll(Seq("a", "b"), 1.0, 2.0, UI.Modifiers.none, Present("id"))
        val encoded = Json.encode[UIEvent](scroll)
        val decoded = Json.decode[UIEvent](encoded)
        assert(decoded == Result.succeed(scroll))
    }

    // ---- Hover/Unhover wire round-trip ----

    "UIEvent.Hover and Unhover round-trip through the JSON wire codec" in {
        val hover     = UIEvent.Hover(Seq("a"), MouseEventData(UI.Modifiers.none, Absent))
        val unhover   = UIEvent.Unhover(Seq("b"), MouseEventData(UI.Modifiers(shift = true), Present("z")))
        val hoverRt   = Json.decode[UIEvent](Json.encode[UIEvent](hover))
        val unhoverRt = Json.decode[UIEvent](Json.encode[UIEvent](unhover))
        assert(hoverRt == Result.succeed(hover))
        assert(unhoverRt == Result.succeed(unhover))
    }

    // ---- two overloads distinct ----

    "the action and typed onHover overloads both fire (distinct Attrs fields)" in {
        for
            actionRef <- AtomicRef.init(false)
            evtRef    <- AtomicRef.init(Absent: Maybe[String])
            ui = UI.div.onHover(actionRef.set(true)).onHover((e: UI.MouseEvent) => evtRef.set(e.targetId))
            dispatch <- makeDispatch(ui)
            _        <- dispatch(Seq.empty, UIEvent.Hover(Seq.empty, MouseEventData(UI.Modifiers.none, Present("foo"))))
            action   <- actionRef.get
            evt      <- evtRef.get
        yield
            assert(action)
            assert(evt == Present("foo"))
    }

end UIEventWiringTest
