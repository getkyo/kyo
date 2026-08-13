package kyo

import kyo.internal.DragProtocol
import kyo.internal.HtmlRenderer
import kyo.internal.MouseEventData
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange

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

    private def htmlUnescape(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private def htmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private def attribute(html: String, name: String)(using kyo.test.AssertScope): String =
        val prefix = s"$name=\""
        val start  = html.indexOf(prefix)
        assert(start >= 0, s"missing $name in $html")
        val valueStart = start + prefix.length
        val valueEnd   = html.indexOf('"', valueStart)
        assert(valueEnd >= valueStart, s"unterminated $name in $html")
        html.substring(valueStart, valueEnd)
    end attribute

    // ---- Declarative drag AST and rendering ----

    "drag capabilities preserve chained HTML attributes and render complete escaped metadata" in {
        val source = Drag.Source(
            key = "source\"&<key>",
            items = Chunk(Drag.Item.Text(Map("text/plain" -> "card\"&<text>"))),
            operations = Drag.AllowedOperations.copy,
            label = Present("source\"&<label>"),
            handle = true,
            preview = Drag.Preview.Label("preview\"&<label>")
        )
        val target = Drag.Target(
            key = "target\"&<key>",
            accepts = Drag.Accept(
                mediaTypes = Set("text/plain"),
                operations = Drag.AllowedOperations.copy,
                maxItems = Present(2),
                directories = true
            ),
            label = Present("target\"&<label>")
        )
        val ui = UI.div
            .id("drag-node")
            .cssClass("card")
            .data("kind", "task")
            .dragSource(source)
            .dropTarget(target)
            .onDragStart((_: Drag.Event) => ())
            .onDragEnd((_: Drag.End) => ())
            .onDragEnter((_: Drag.Event) => ())
            .onDragLeave((_: Drag.Event) => ())
            .onDragOver((_: Drag.Event) => ())
            .onDrop((_: Drag.Event) => Drag.Decision.Accept)
            .onSortMove((_: Drag.Move) => Drag.Decision.Accept)
            .onClick(())(UI.span("child<&>"))

        val expectedSourceJson =
            """{"key":"source\"&<key>","items":[{"Text":{"representations":{"text/plain":"card\"&<text>"}}}],"operations":{"values":[{"Copy":{}}]},"label":"source\"&<label>","handle":true,"preview":{"Label":{"value":"preview\"&<label>"}}}"""
        val expectedTargetJson =
            """{"key":"target\"&<key>","accepts":{"mediaTypes":["text/plain"],"operations":{"values":[{"Copy":{}}]},"maxItems":2,"directories":true},"label":"target\"&<label>"}"""

        assert(ui.attrs.dragSource == Present(source))
        assert(ui.attrs.dropTarget == Present(target))
        assert(ui.attrs.onClick.nonEmpty)
        assert(ui.attrs.onDragStartEvt.nonEmpty)
        assert(ui.attrs.onDragEndEvt.nonEmpty)
        assert(ui.attrs.onDragEnterEvt.nonEmpty)
        assert(ui.attrs.onDragLeaveEvt.nonEmpty)
        assert(ui.attrs.onDragOverEvt.nonEmpty)
        assert(ui.attrs.onDropEvt.nonEmpty)
        assert(ui.attrs.onSortMoveEvt.nonEmpty)
        assert(ui.children.size == 1)

        for html <- HtmlRenderer.render(ui, Seq.empty)
        yield
            val sourceJson = htmlUnescape(attribute(html, "data-kyo-drag-source"))
            val targetJson = htmlUnescape(attribute(html, "data-kyo-drop-target"))
            assert(sourceJson == expectedSourceJson)
            assert(targetJson == expectedTargetJson)
            assert(attribute(html, "data-kyo-drag-source") == htmlEscape(expectedSourceJson))
            assert(attribute(html, "data-kyo-drop-target") == htmlEscape(expectedTargetJson))
            assert(Json.decode[Drag.Source](sourceJson) == Result.succeed(source))
            assert(Json.decode[Drag.Target](targetJson) == Result.succeed(target))
            assert(attribute(html, "data-kyo-drag-key") == "source&quot;&amp;&lt;key&gt;")
            assert(html.contains("draggable=\"true\""))
            assert(html.contains("id=\"drag-node\""))
            assert(html.contains("class=\"card\""))
            assert(html.contains("data-kind=\"task\""))
            assert(html.contains("child&lt;&amp;&gt;"))
            assert(
                attribute(html, "data-kyo-ev") ==
                    "click,dragstart,dragend,dragenter,dragleave,dragover,drop,sortmove"
            )
            assert(!html.contains("Function"))
            assert(!html.contains("Drag.Event"))
        end for
    }

    "shared drag API renders SVG target metadata without making it draggable" in {
        val target = Drag.Target("svg\"&<target>", Drag.Accept.types("image/svg+xml"), Present("svg target"))
        val ui = Svg.rect
            .id("drop-zone")
            .dropTarget(target)
            .onDragStart("drag-start-handler-secret")
            .onDragEnd("drag-end-handler-secret")
            .onDragEnter("drag-enter-handler-secret")
            .onDragLeave("drag-leave-handler-secret")
            .onDragOver("drag-over-handler-secret")
            .onDrop("drop-handler-secret")
            .onSortMove("sort-move-handler-secret")

        assert(ui.attrs.dropTarget == Present(target))
        assert(ui.attrs.onDragStart.nonEmpty)
        assert(ui.attrs.onDragEnd.nonEmpty)
        assert(ui.attrs.onDragEnter.nonEmpty)
        assert(ui.attrs.onDragLeave.nonEmpty)
        assert(ui.attrs.onDragOver.nonEmpty)
        assert(ui.attrs.onDrop.nonEmpty)
        assert(ui.attrs.onSortMove.nonEmpty)

        val dropDecision = ui.attrs.onDrop match
            case Present(handler) => handler
            case Absent           => fail("missing drop action handler")
        val sortDecision = ui.attrs.onSortMove match
            case Present(handler) => handler
            case Absent           => fail("missing sort action handler")

        for
            dropResult <- dropDecision
            sortResult <- sortDecision
            html       <- HtmlRenderer.render(ui, Seq.empty)
        yield
            assert(dropResult == Drag.Decision.Accept)
            assert(sortResult == Drag.Decision.Accept)
            assert(Json.decode[Drag.Target](htmlUnescape(attribute(html, "data-kyo-drop-target"))) == Result.succeed(target))
            assert(attribute(html, "data-kyo-drag-key") == "svg&quot;&amp;&lt;target&gt;")
            assert(!html.contains("draggable=\"true\""))
            assert(!html.contains("handler-secret"))
            assert(
                attribute(html, "data-kyo-ev") ==
                    "dragstart,dragend,dragenter,dragleave,dragover,drop,sortmove"
            )
        end for
    }

    "typed drag observers preserve concrete effect results and immutable wiring" in {
        val source = Drag.Source("observer-source", Chunk.empty)
        val target = Drag.Target("observer-target", Drag.Accept())
        val event = Drag.Event(
            sessionId = "observer-session",
            items = Chunk.empty,
            operation = Drag.Operation.Move,
            sourceKey = Present(source.key),
            targetKey = Present(target.key),
            point = Absent,
            modifiers = UI.Modifiers.none,
            position = Absent
        )
        val end = Drag.End(event, canceled = false)
        val ui = UI.div
            .id("observer")
            .dragSource(source)
            .dropTarget(target)
            .onDragStart((_: Drag.Event) => Sync.defer("start-result"))
            .onDragEnd((_: Drag.End) => Sync.defer("end-result"))
            .onDragEnter((_: Drag.Event) => Sync.defer("enter-result"))
            .onDragLeave((_: Drag.Event) => Sync.defer("leave-result"))
            .onDragOver((_: Drag.Event) => Sync.defer("over-result"))

        val startHandler = ui.attrs.onDragStartEvt.getOrElse(fail("missing drag-start observer"))
        val endHandler   = ui.attrs.onDragEndEvt.getOrElse(fail("missing drag-end observer"))
        val enterHandler = ui.attrs.onDragEnterEvt.getOrElse(fail("missing drag-enter observer"))
        val leaveHandler = ui.attrs.onDragLeaveEvt.getOrElse(fail("missing drag-leave observer"))
        val overHandler  = ui.attrs.onDragOverEvt.getOrElse(fail("missing drag-over observer"))

        for
            startResult <- startHandler(event)
            endResult   <- endHandler(end)
            enterResult <- enterHandler(event)
            leaveResult <- leaveHandler(event)
            overResult  <- overHandler(event)
            html        <- HtmlRenderer.render(ui, Seq.empty)
        yield
            assert(Chunk(startResult, endResult, enterResult, leaveResult, overResult).map(_.toString) ==
                Chunk("start-result", "end-result", "enter-result", "leave-result", "over-result"))
            assert(ui.attrs.dragSource == Present(source))
            assert(ui.attrs.dropTarget == Present(target))
            assert(html.contains("id=\"observer\""))
            assert(attribute(html, "data-kyo-ev") == "dragstart,dragend,dragenter,dragleave,dragover")
        end for
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

    // ---- Drag event wire round-trips ----

    "drag UIEvents round-trip through their exact JSON wire representations" in {
        val startData = DragProtocol.StartData(
            sessionId = "session-1",
            items = Chunk(
                DragProtocol.ItemData.Text(Map("text/plain" -> "card")),
                DragProtocol.ItemData.Directory("dir-token", "assets")
            ),
            operation = Drag.Operation.Copy,
            sourceKey = Present("source"),
            point = Drag.Point(12.5, 24.0),
            modifiers = UI.Modifiers(ctrl = true, shift = true)
        )
        val targetData = DragProtocol.TargetData(
            sessionId = "session-1",
            operation = Drag.Operation.Copy,
            targetKey = Present("target"),
            point = Drag.Point(12.5, 24.0),
            modifiers = UI.Modifiers(ctrl = true, shift = true),
            position = Present(Drag.Position.Before)
        )
        val endData = DragProtocol.EndData("session-1", Drag.Operation.Move, cancelled = true)
        val move = Drag.Move(
            keys = Chunk("alpha", "beta", "gamma"),
            source = Drag.Location("backlog"),
            destination = Drag.Location("done"),
            anchor = Present("omega"),
            position = Drag.Position.After,
            operation = Drag.Operation.Move
        )
        val cases = Chunk[(UIEvent, String)](
            UIEvent.DragStart(Seq("root", "source"), startData) ->
                """{"DragStart":{"path":["root","source"],"event":{"sessionId":"session-1","items":[{"Text":{"representations":{"text/plain":"card"}}},{"Directory":{"token":"dir-token","name":"assets"}}],"operation":{"Copy":{}},"sourceKey":"source","point":{"x":12.5,"y":24.0},"modifiers":{"ctrl":true,"alt":false,"shift":true,"meta":false}}}}""",
            UIEvent.DragEnd(Seq("root", "source"), endData) ->
                """{"DragEnd":{"path":["root","source"],"event":{"sessionId":"session-1","operation":{"Move":{}},"cancelled":true}}}""",
            UIEvent.DragEnter(Seq("root", "target"), targetData) ->
                """{"DragEnter":{"path":["root","target"],"event":{"sessionId":"session-1","operation":{"Copy":{}},"targetKey":"target","point":{"x":12.5,"y":24.0},"modifiers":{"ctrl":true,"alt":false,"shift":true,"meta":false},"position":{"Before":{}}}}}""",
            UIEvent.DragLeave(Seq("root", "target"), targetData) ->
                """{"DragLeave":{"path":["root","target"],"event":{"sessionId":"session-1","operation":{"Copy":{}},"targetKey":"target","point":{"x":12.5,"y":24.0},"modifiers":{"ctrl":true,"alt":false,"shift":true,"meta":false},"position":{"Before":{}}}}}""",
            UIEvent.DragOver(Seq("root", "target"), targetData) ->
                """{"DragOver":{"path":["root","target"],"event":{"sessionId":"session-1","operation":{"Copy":{}},"targetKey":"target","point":{"x":12.5,"y":24.0},"modifiers":{"ctrl":true,"alt":false,"shift":true,"meta":false},"position":{"Before":{}}}}}""",
            UIEvent.Drop(Seq("root", "target"), targetData) ->
                """{"Drop":{"path":["root","target"],"event":{"sessionId":"session-1","operation":{"Copy":{}},"targetKey":"target","point":{"x":12.5,"y":24.0},"modifiers":{"ctrl":true,"alt":false,"shift":true,"meta":false},"position":{"Before":{}}}}}""",
            UIEvent.SortMove(Seq("board"), "session-1", move) ->
                """{"SortMove":{"path":["board"],"sessionId":"session-1","move":{"keys":["alpha","beta","gamma"],"source":{"collection":"backlog"},"destination":{"collection":"done"},"anchor":"omega","position":{"After":{}},"operation":{"Move":{}}}}}"""
        )

        cases.foreach { case (event, expected) =>
            val encoded = Json.encode[UIEvent](event)
            assert(encoded == expected)
            assert(Json.decode[UIEvent](encoded) == Result.succeed(event))
            event match
                case _: UIEvent.DragEnter | _: UIEvent.DragLeave | _: UIEvent.DragOver | _: UIEvent.Drop =>
                    assert(!encoded.contains("items"))
                    assert(!encoded.contains("text/plain"))
                    assert(!encoded.contains("card"))
                case _ => succeed
            end match
        }
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
