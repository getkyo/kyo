package kyo

import kyo.internal.DragCommands
import kyo.internal.DragProtocol
import kyo.internal.HtmlRenderer
import kyo.internal.MouseEventData
import kyo.internal.ReactiveRegion
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

    override def config = super.config.sequential

    import UI.*

    private def mediaType(value: String): Drag.MediaType = Drag.MediaType.parse(value).get

    private def mediaTypePattern(value: String): Drag.MediaTypePattern = Drag.MediaTypePattern.parse(value).get

    private def dragText(representations: (String, String)*): Drag.Item.Text =
        Drag.Item.Text(representations.iterator.map((media, value) => mediaType(media) -> value).toMap)

    /** Minimal UIExchange stub that discards onChange notifications. */
    private class NoopExchange extends UIExchange:
        def onChange(
            region: ReactiveRegion,
            path: Seq[String],
            context: ReactiveRegion.RegionIdentity,
            parentContext: ReactiveRegion.ParentContext,
            ui: UI
        )(using
            Frame
        ): Unit < Async = ()
    end NoopExchange

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

    private def withDispatch[A](ui: UI)(f: ((Seq[String], UIEvent) => Boolean < Async) => A < (Async & Scope))(using
        Frame
    ): A < Async =
        withDispatch(ui, ReactiveUI.DragSessionLimits())(f)

    private def withDispatch[A](ui: UI, limits: ReactiveUI.DragSessionLimits)(
        f: ((Seq[String], UIEvent) => Boolean < Async) => A < (Async & Scope)
    )(using Frame): A < Async =
        withSubscription(ui, limits)(subscription => f(subscription.handle))

    private def withSubscription[A](ui: UI, limits: ReactiveUI.DragSessionLimits)(
        f: ReactiveUI.Subscription => A < (Async & Scope)
    )(using Frame): A < Async =
        Scope.run {
            for
                root         <- ReactiveUI.normalize(ui, Seq.empty)
                subscription <- ReactiveUI.subscribe(root, new NoopExchange, limits)
                result       <- f(subscription)
            yield result
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
            items = Chunk(dragText("text/plain" -> "card\"&<text>")),
            operations = Drag.AllowedOperations.copy,
            label = Present("source\"&<label>"),
            handle = true,
            preview = Drag.Preview.Label("preview\"&<label>")
        )
        val target = Drag.Target(
            key = "target\"&<key>",
            accepts = Drag.Accept(
                mediaTypes = Set(mediaTypePattern("text/plain")),
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
            """{"key":"source\"&<key>","items":[{"Text":{"representations":{"text/plain":"card\"&<text>"}}}],"operations":{"values":[{"Copy":{}}]},"label":"source\"&<label>","handle":true,"preview":{"Label":{"value":"preview\"&<label>"}},"activation":{"Both":{}}}"""
        val expectedTargetJson =
            """{"key":"target\"&<key>","accepts":{"mediaTypes":["text/plain"],"operations":{"values":[{"Copy":{}}]},"maxItems":2,"directories":true},"label":"target\"&<label>","orientation":{"Vertical":{}},"collision":{"ClosestEdge":{}}}"""

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
            assert(Json.decode[DragProtocol.SourceConfig](sourceJson) == DragProtocol.sourceConfig(source, DragProtocol.Limits.default))
            assert(Json.decode[DragProtocol.TargetConfig](targetJson) == DragProtocol.targetConfig(target, DragProtocol.Limits.default))
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
        val target = Drag.Target("svg\"&<target>", Drag.Accept.types(mediaTypePattern("image/svg+xml")), Present("svg target"))
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
            assert(
                Json.decode[DragProtocol.TargetConfig](htmlUnescape(attribute(html, "data-kyo-drop-target"))) ==
                    DragProtocol.targetConfig(target, DragProtocol.Limits.default)
            )
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

    "drag dispatch reconstructs session payloads, bubbles targets, and resolves the first rejection once" in {
        val start = DragProtocol.StartData(
            "drag-1",
            Chunk(DragProtocol.ItemData.Text(Map("text/plain" -> "card"))),
            Drag.Operation.Copy,
            Present("source"),
            Drag.Point(1, 2),
            UI.Modifiers(shift = true)
        )
        val target = DragProtocol.TargetData(
            "drag-1",
            Drag.Operation.Move,
            Present("target"),
            Drag.Point(3, 4),
            UI.Modifiers(ctrl = true),
            Present(Drag.Position.Before)
        )
        val rejection = Drag.Decision.Reject(Drag.Rejection.Application("locked"))

        for
            calls       <- AtomicRef.init(Chunk.empty[String])
            startSeen   <- AtomicRef.init(Absent: Maybe[Drag.Event])
            targetSeen  <- AtomicRef.init(Chunk.empty[Drag.Event])
            endSeen     <- AtomicRef.init(Absent: Maybe[Drag.End])
            resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
            child = UI.div
                .dragSource(Drag.Source("source", Chunk.empty))
                .dropTarget(Drag.Target("target", Drag.Accept()))
                .onDragStart((event: Drag.Event) => startSeen.set(Present(event)))
                .onDragEnter(calls.getAndUpdate(_.append("inner-enter")).unit)
                .onDragEnter((event: Drag.Event) => targetSeen.getAndUpdate(_.append(event)).unit)
                .onDragLeave((event: Drag.Event) => targetSeen.getAndUpdate(_.append(event)).unit)
                .onDragOver((event: Drag.Event) => targetSeen.getAndUpdate(_.append(event)).unit)
                .onDrop(calls.getAndUpdate(_.append("inner-action")).andThen(Drag.Decision.Accept))
                .onDrop((_: Drag.Event) => calls.getAndUpdate(_.append("inner-typed")).andThen(rejection))
                .onDragEnd((end: Drag.End) => endSeen.set(Present(end)))
            ui = UI.div
                .onDragStart(calls.getAndUpdate(_.append("outer-start")).unit)
                .onDragEnter(calls.getAndUpdate(_.append("outer-enter")).unit)
                .onDrop(calls.getAndUpdate(_.append("outer-action")).andThen(Drag.Decision.Accept))(child)
            _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                withDispatch(ui) { dispatch =>
                    dispatch(Seq("0"), UIEvent.DragStart(Seq("0"), start))
                        .andThen(dispatch(Seq("0"), UIEvent.DragEnter(Seq("0"), target)))
                        .andThen(dispatch(Seq("0"), UIEvent.DragLeave(Seq("0"), target)))
                        .andThen(dispatch(Seq("0"), UIEvent.DragOver(Seq("0"), target)))
                        .andThen(dispatch(Seq("0"), UIEvent.Drop(Seq("0"), target)))
                        .andThen(dispatch(
                            Seq("0"),
                            UIEvent.DragEnd(Seq("0"), DragProtocol.EndData("drag-1", Drag.Operation.Link, cancelled = true))
                        ))
                }
            }
            actualStart       <- startSeen.get
            actualTarget      <- targetSeen.get
            actualEnd         <- endSeen.get
            actualCalls       <- calls.get
            actualResolutions <- resolutions.get
        yield
            val expectedStart = Drag.Event(
                "drag-1",
                Chunk(dragText("text/plain" -> "card")),
                Drag.Operation.Copy,
                Present("source"),
                Absent,
                Present(Drag.Point(1, 2)),
                UI.Modifiers(shift = true),
                Absent
            )
            val expectedTarget = expectedStart.copy(
                operation = Drag.Operation.Move,
                targetKey = Present("target"),
                point = Present(Drag.Point(3, 4)),
                modifiers = UI.Modifiers(ctrl = true),
                position = Present(Drag.Position.Before)
            )
            assert(actualStart == Present(expectedStart))
            assert(actualTarget == Chunk(expectedTarget, expectedTarget, expectedTarget))
            assert(actualEnd == Present(Drag.End(expectedTarget.copy(operation = Drag.Operation.Link), canceled = true)))
            assert(actualCalls == Chunk("inner-enter", "outer-enter", "inner-action", "inner-typed", "outer-action"))
            assert(actualResolutions == Chunk("drag-1" -> rejection))
        end for
    }

    "local dispatch rejects malformed drag-start media without invoking handlers" in {
        val invalid = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(
                "invalid-media",
                Chunk(DragProtocol.ItemData.Text(Map("text/*" -> "value"))),
                Drag.Operation.Copy,
                Absent,
                Drag.Point(0, 0),
                UI.Modifiers.none
            )
        )
        for
            calls <- AtomicInt.init(0)
            _ <- withDispatch(UI.div.onDragStart((_: Drag.Event) => calls.incrementAndGet)) { dispatch =>
                dispatch(Seq.empty, invalid)
            }
            count <- calls.get
        yield assert(count == 0)
        end for
    }

    "unknown drop rejects without handlers and a known terminal event resolves only once" in {
        val target = DragProtocol.TargetData(
            "missing",
            Drag.Operation.Copy,
            Present("target"),
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        val noHandler = Drag.Decision.Reject(Drag.Rejection.Application("No drop handler accepted the operation."))
        val knownStart = DragProtocol.StartData(
            "known",
            Chunk.empty,
            Drag.Operation.Copy,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none
        )
        val knownTarget = target.copy(sessionId = "known")
        val sortStart   = knownStart.copy(sessionId = "no-sort")
        val move = Drag.Move(
            Chunk("a"),
            Drag.Location("left"),
            Drag.Location("right"),
            Absent,
            Drag.Position.On,
            Drag.Operation.Move
        )
        val noSort = Drag.Decision.Reject(Drag.Rejection.Application("No sort handler accepted the move."))
        for
            calls       <- AtomicRef.init(0)
            resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
            ui = UI.div.onDrop(calls.getAndUpdate(_ + 1).andThen(Drag.Decision.Accept))
            _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                withDispatch(ui) { dispatch =>
                    dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target))
                        .andThen(dispatch(Seq.empty, UIEvent.SortMove(Seq.empty, "missing-sort", move)))
                        .andThen(dispatch(Seq.empty, UIEvent.DragStart(Seq.empty, knownStart)))
                        .andThen(dispatch(Seq.empty, UIEvent.Drop(Seq.empty, knownTarget)))
                        .andThen(dispatch(Seq.empty, UIEvent.Drop(Seq.empty, knownTarget)))
                        .andThen(dispatch(Seq.empty, UIEvent.DragStart(Seq.empty, sortStart)))
                        .andThen(dispatch(Seq.empty, UIEvent.SortMove(Seq.empty, "no-sort", move)))
                }
            }
            actualCalls <- calls.get
            actual      <- resolutions.get
        yield
            assert(actualCalls == 1)
            assert(actual == Chunk(
                "missing"      -> noHandler,
                "missing-sort" -> noSort,
                "known"        -> Drag.Decision.Accept,
                "no-sort"      -> noSort
            ))
        end for
    }

    "drag resolution outside a runner is a no-op" in {
        DragCommands.resolve("absent", Drag.Decision.Accept).andThen(succeed)
    }

    "sort decisions bubble inner first and stop only at a declared handler" in {
        val start = DragProtocol.StartData(
            "sort-1",
            Chunk.empty,
            Drag.Operation.Move,
            Present("source"),
            Drag.Point(0, 0),
            UI.Modifiers.none
        )
        val move = Drag.Move(
            Chunk("a"),
            Drag.Location("left"),
            Drag.Location("right"),
            Absent,
            Drag.Position.After,
            Drag.Operation.Move
        )
        val reject = Drag.Decision.Reject(Drag.Rejection.Application("sorted locked"))
        for
            calls       <- AtomicRef.init(Chunk.empty[String])
            resolutions <- AtomicRef.init(Chunk.empty[Drag.Decision])
            child = UI.div
                .stopPropagation(true)
                .onSortMove(calls.getAndUpdate(_.append("inner-action")).andThen(Drag.Decision.Accept))
                .onSortMove((_: Drag.Move) => calls.getAndUpdate(_.append("inner-typed")).andThen(reject))
            ui = UI.div.onSortMove(calls.getAndUpdate(_.append("outer")).andThen(Drag.Decision.Accept))(UI.section(child))
            _ <- DragCommands.resolveSink.let(Present((_, decision) => resolutions.getAndUpdate(_.append(decision)).unit)) {
                withDispatch(ui) { dispatch =>
                    dispatch(Seq("0", "0"), UIEvent.DragStart(Seq("0", "0"), start))
                        .andThen(dispatch(Seq("0", "0"), UIEvent.SortMove(Seq("0", "0"), "sort-1", move)))
                }
            }
            actualCalls     <- calls.get
            actualDecisions <- resolutions.get
        yield
            assert(actualCalls == Chunk("inner-action", "inner-typed"))
            assert(actualDecisions == Chunk(reject))
        end for
    }

    "drop handler failures resolve an application rejection and continue bubbling" in {
        val start = DragProtocol.StartData(
            "failed-drop",
            Chunk.empty,
            Drag.Operation.Copy,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none
        )
        val target = DragProtocol.TargetData(
            "failed-drop",
            Drag.Operation.Copy,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        val failure = Drag.Decision.Reject(Drag.Rejection.Application("The drop handler failed."))
        val failingHandler: Drag.Event => Drag.Decision < Async =
            (_: Drag.Event) => Sync.defer(throw new RuntimeException("failed"))
        for
            outer       <- AtomicRef.init(false)
            resolutions <- AtomicRef.init(Chunk.empty[Drag.Decision])
            child = UI.div.onDrop(failingHandler)
            ui    = UI.div.onDrop(outer.set(true).andThen(Drag.Decision.Accept))(child)
            _ <- DragCommands.resolveSink.let(Present((_, decision) => resolutions.getAndUpdate(_.append(decision)).unit)) {
                withDispatch(ui) { dispatch =>
                    dispatch(Seq("0"), UIEvent.DragStart(Seq("0"), start))
                        .andThen(dispatch(Seq("0"), UIEvent.Drop(Seq("0"), target)))
                }
            }
            bubbled <- outer.get
            actual  <- resolutions.get
        yield
            assert(bubbled)
            assert(actual == Chunk(failure))
        end for
    }

    "duplicate drag start preserves the active session and End allows identifier reuse" in {
        def start(item: String, source: String, point: Drag.Point) = DragProtocol.StartData(
            "duplicate",
            Chunk(DragProtocol.ItemData.Text(Map("text/plain" -> item))),
            Drag.Operation.Copy,
            Present(source),
            point,
            UI.Modifiers.none
        )
        val duplicate = Drag.Decision.Reject(
            Drag.Rejection.Application("A drag session with this identifier is already active.")
        )
        for
            starts      <- AtomicRef.init(Chunk.empty[Drag.Event])
            ends        <- AtomicRef.init(Chunk.empty[Drag.End])
            resolutions <- AtomicRef.init(Chunk.empty[Drag.Decision])
            ui = UI.div
                .onDragStart((event: Drag.Event) => starts.getAndUpdate(_.append(event)).unit)
                .onDragEnd((end: Drag.End) => ends.getAndUpdate(_.append(end)).unit)
            _ <- DragCommands.resolveSink.let(Present((_, decision) => resolutions.getAndUpdate(_.append(decision)).unit)) {
                withDispatch(ui) { dispatch =>
                    dispatch(Seq.empty, UIEvent.DragStart(Seq.empty, start("first", "source-1", Drag.Point(1, 1))))
                        .andThen(dispatch(
                            Seq.empty,
                            UIEvent.DragStart(Seq.empty, start("second", "source-2", Drag.Point(9, 9)))
                        ))
                        .andThen(dispatch(
                            Seq.empty,
                            UIEvent.DragEnd(
                                Seq.empty,
                                DragProtocol.EndData("duplicate", Drag.Operation.Link, cancelled = false)
                            )
                        ))
                        .andThen(dispatch(
                            Seq.empty,
                            UIEvent.DragStart(Seq.empty, start("third", "source-3", Drag.Point(3, 3)))
                        ))
                }
            }
            actualStarts      <- starts.get
            actualEnds        <- ends.get
            actualResolutions <- resolutions.get
        yield
            assert(actualStarts.map(_.point) == Chunk(Present(Drag.Point(1, 1)), Present(Drag.Point(3, 3))))
            assert(actualEnds == Chunk(Drag.End(
                Drag.Event(
                    "duplicate",
                    Chunk(dragText("text/plain" -> "first")),
                    Drag.Operation.Link,
                    Present("source-1"),
                    Absent,
                    Present(Drag.Point(1, 1)),
                    UI.Modifiers.none,
                    Absent
                ),
                canceled = false
            )))
            assert(actualResolutions == Chunk(duplicate))
        end for
    }

    "drag lifecycle serializes concurrent duplicate start drop and sort dispatch" in {
        def start(id: String) = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(id, Chunk.empty, Drag.Operation.Move, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        def target(id: String) = DragProtocol.TargetData(
            id,
            Drag.Operation.Move,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        val move = Drag.Move(
            Chunk("a"),
            Drag.Location("left"),
            Drag.Location("right"),
            Absent,
            Drag.Position.On,
            Drag.Operation.Move
        )
        for
            entered     <- Latch.init(1)
            release     <- Latch.init(1)
            starts      <- AtomicRef.init(0)
            drops       <- AtomicRef.init(0)
            sorts       <- AtomicRef.init(0)
            resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
            ui = UI.div
                .onDragStart((_: Drag.Event) => starts.getAndUpdate(_ + 1).unit)
                .onDrop((_: Drag.Event) =>
                    entered.release.andThen(release.await).andThen(drops.getAndUpdate(_ + 1)).andThen(Drag.Decision.Accept)
                )
                .onSortMove((_: Drag.Move) => sorts.getAndUpdate(_ + 1).andThen(Drag.Decision.Accept))
            _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                withDispatch(ui) { dispatch =>
                    for
                        _              <- dispatch(Seq.empty, start("drop"))
                        _              <- dispatch(Seq.empty, start("sort"))
                        firstDrop      <- Fiber.init(dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("drop"))))
                        _              <- entered.await
                        firstSort      <- Fiber.init(dispatch(Seq.empty, UIEvent.SortMove(Seq.empty, "sort", move)))
                        duplicateStart <- Fiber.init(dispatch(Seq.empty, start("drop")))
                        duplicateDrop  <- Fiber.init(dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("drop"))))
                        duplicateSort <-
                            Fiber.init(dispatch(Seq.empty, UIEvent.SortMove(Seq.empty, "sort", move)))
                        _ <- release.release
                        _ <- firstDrop.get
                        _ <- firstSort.get
                        _ <- duplicateStart.get
                        _ <- duplicateDrop.get
                        _ <- duplicateSort.get
                    yield ()
                }
            }
            actualStarts      <- starts.get
            actualDrops       <- drops.get
            actualSorts       <- sorts.get
            actualResolutions <- resolutions.get
        yield
            assert(actualStarts == 2)
            assert(actualDrops == 1)
            assert(actualSorts == 1)
            assert(actualResolutions.count(_._1 == "drop") == 2)
            assert(actualResolutions.count(_._1 == "sort") == 1)
        end for
    }

    "concurrent duplicate start admits one session and rejects the other" in {
        val event = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData("same", Chunk.empty, Drag.Operation.Move, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        val duplicate = Drag.Decision.Reject(
            Drag.Rejection.Application("A drag session with this identifier is already active.")
        )
        for
            entered     <- Latch.init(1)
            release     <- Latch.init(1)
            starts      <- AtomicRef.init(0)
            resolutions <- AtomicRef.init(Chunk.empty[Drag.Decision])
            ui = UI.div.onDragStart((_: Drag.Event) =>
                entered.release.andThen(release.await).andThen(starts.getAndUpdate(_ + 1).unit)
            )
            _ <- DragCommands.resolveSink.let(Present((_, decision) => resolutions.getAndUpdate(_.append(decision)).unit)) {
                withDispatch(ui) { dispatch =>
                    for
                        first  <- Fiber.init(dispatch(Seq.empty, event))
                        _      <- entered.await
                        second <- Fiber.init(dispatch(Seq.empty, event))
                        _      <- release.release
                        _      <- first.get
                        _      <- second.get
                    yield ()
                }
            }
            actualStarts      <- starts.get
            actualResolutions <- resolutions.get
        yield
            assert(actualStarts == 1)
            assert(actualResolutions == Chunk(duplicate))
        end for
    }

    "drag session capacity and expiry are bounded and generation safe" in {
        val limits = ReactiveUI.DragSessionLimits(maxSessions = 1, lifetime = 1.second)
        def start(id: String) = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(id, Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        val capacity = Drag.Decision.Reject(Drag.Rejection.Application("Too many active drag sessions."))
        val expired  = Drag.Decision.Reject(Drag.Rejection.Application("The drag session expired."))
        Clock.withTimeControl { control =>
            for
                starts      <- AtomicRef.init(Chunk.empty[String])
                resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
                ui = UI.div.onDragStart((event: Drag.Event) => starts.getAndUpdate(_.append(event.sessionId)).unit)
                _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                    withDispatch(ui, limits) { dispatch =>
                        for
                            _ <- dispatch(Seq.empty, start("first"))
                            _ <- dispatch(Seq.empty, start("full"))
                            _ <- dispatch(
                                Seq.empty,
                                UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("first", Drag.Operation.Copy, cancelled = true))
                            )
                            _ <- dispatch(Seq.empty, start("after-end"))
                            _ <- control.advance(Duration.Zero, 100.millis)
                            _ <- control.advance(1.second, 100.millis)
                            _ <- dispatch(Seq.empty, start("after-end"))
                        yield ()
                    }
                }
                actualStarts      <- starts.get
                actualResolutions <- resolutions.get
            yield
                assert(actualStarts == Chunk("first", "after-end", "after-end"))
                assert(actualResolutions == Chunk("full" -> capacity, "after-end" -> expired))
        }
    }

    "drop serializes queued over and end while end-first rejects a later drop" in {
        def start(id: String) = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(id, Chunk.empty, Drag.Operation.Move, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        def target(id: String) = DragProtocol.TargetData(
            id,
            Drag.Operation.Move,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        val unknown = Drag.Decision.Reject(Drag.Rejection.Application("No drop handler accepted the operation."))
        for
            entered     <- Latch.init(1)
            release     <- Latch.init(1)
            starts      <- AtomicRef.init(0)
            overs       <- AtomicRef.init(0)
            drops       <- AtomicRef.init(0)
            resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
            ui = UI.div
                .onDragStart((_: Drag.Event) => starts.getAndUpdate(_ + 1).unit)
                .onDragOver((_: Drag.Event) => overs.getAndUpdate(_ + 1).unit)
                .onDrop((_: Drag.Event) =>
                    entered.release.andThen(release.await).andThen(drops.getAndUpdate(_ + 1)).andThen(Drag.Decision.Accept)
                )
            _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                withDispatch(ui) { dispatch =>
                    for
                        _         <- dispatch(Seq.empty, start("ordered"))
                        dropFiber <- Fiber.init(dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("ordered"))))
                        _         <- entered.await
                        overFiber <- Fiber.init(dispatch(Seq.empty, UIEvent.DragOver(Seq.empty, target("ordered"))))
                        endFiber <- Fiber.init(dispatch(
                            Seq.empty,
                            UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("ordered", Drag.Operation.Move, cancelled = false))
                        ))
                        _ <- release.release
                        _ <- dropFiber.get
                        _ <- overFiber.get
                        _ <- endFiber.get
                        _ <- dispatch(Seq.empty, start("ordered"))
                        _ <- dispatch(
                            Seq.empty,
                            UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("ordered", Drag.Operation.Move, cancelled = true))
                        )
                        _ <- dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("ordered")))
                    yield ()
                }
            }
            actualStarts      <- starts.get
            actualOvers       <- overs.get
            actualDrops       <- drops.get
            actualResolutions <- resolutions.get
        yield
            assert(actualStarts == 2)
            assert(actualOvers == 0)
            assert(actualDrops == 1)
            assert(actualResolutions == Chunk("ordered" -> Drag.Decision.Accept, "ordered" -> unknown))
        end for
    }

    "terminal expiry is silent and a stale timer cannot remove a reused identifier" in {
        val limits = ReactiveUI.DragSessionLimits(maxSessions = 2, lifetime = 2.seconds)
        def start(id: String) = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(id, Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        def target(id: String) = DragProtocol.TargetData(
            id,
            Drag.Operation.Copy,
            Absent,
            Drag.Point(0, 0),
            UI.Modifiers.none,
            Absent
        )
        Clock.withTimeControl { control =>
            for
                drops       <- AtomicRef.init(0)
                resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
                ui = UI.div.onDrop(drops.getAndUpdate(_ + 1).andThen(Drag.Decision.Accept))
                _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                    withDispatch(ui, limits) { dispatch =>
                        for
                            _ <- dispatch(Seq.empty, start("terminal"))
                            _ <- dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("terminal")))
                            _ <- dispatch(Seq.empty, start("reuse"))
                            _ <- control.advance(1.second, 100.millis)
                            _ <- dispatch(
                                Seq.empty,
                                UIEvent.DragEnd(Seq.empty, DragProtocol.EndData("reuse", Drag.Operation.Copy, cancelled = true))
                            )
                            _ <- dispatch(Seq.empty, start("reuse"))
                            _ <- control.advance(1.second, 100.millis)
                            _ <- dispatch(Seq.empty, UIEvent.Drop(Seq.empty, target("reuse")))
                            _ <- control.advance(1.second, 100.millis)
                            _ <- dispatch(Seq.empty, start("terminal"))
                        yield ()
                    }
                }
                actualDrops       <- drops.get
                actualResolutions <- resolutions.get
            yield
                assert(actualDrops == 2)
                assert(actualResolutions == Chunk("terminal" -> Drag.Decision.Accept, "reuse" -> Drag.Decision.Accept))
        }
    }

    "drag session limits reject nonpositive values" in {
        intercept[IllegalArgumentException](ReactiveUI.DragSessionLimits(maxSessions = 0))
        intercept[IllegalArgumentException](ReactiveUI.DragSessionLimits(lifetime = Duration.Zero))
        succeed
    }

    "closing the subscription scope cancels active expiry work" in {
        val limits = ReactiveUI.DragSessionLimits(maxSessions = 1, lifetime = 1.second)
        val event = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData("closing", Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        Clock.withTimeControl { control =>
            for
                resolutions <- AtomicRef.init(Chunk.empty[Drag.Decision])
                _ <- DragCommands.resolveSink.let(Present((_, decision) => resolutions.getAndUpdate(_.append(decision)).unit)) {
                    withDispatch(UI.div, limits)(_(Seq.empty, event).unit)
                }
                _      <- control.advance(1.second, 100.millis)
                actual <- resolutions.get
            yield assert(actual.isEmpty)
        }
    }

    "drag Start and End churn keeps one bounded expiry worker" in {
        val limits = ReactiveUI.DragSessionLimits(maxSessions = 1, lifetime = 1.hour)
        val start = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData("churn", Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        val end = UIEvent.DragEnd(
            Seq.empty,
            DragProtocol.EndData("churn", Drag.Operation.Copy, cancelled = true)
        )
        for
            subscription <- Scope.run {
                for
                    root         <- ReactiveUI.normalize(UI.div, Seq.empty)
                    subscription <- ReactiveUI.subscribe(root, new NoopExchange, limits)
                    _ <- Kyo.foreach(0 until 1000) { _ =>
                        subscription.handle(Seq.empty, start).andThen(subscription.handle(Seq.empty, end))
                    }
                    sessions <- subscription.dragSessionCount
                    workers  <- subscription.dragExpiryWorkerCount
                yield
                    assert(sessions == 0)
                    assert(workers == 1)
                    subscription
            }
            sessions <- subscription.dragSessionCount
            workers  <- subscription.dragExpiryWorkerCount
        yield
            assert(sessions == 0)
            assert(workers == 0)
        end for
    }

    "an earlier drag deadline wakes the expiry scheduler" in {
        val limits = ReactiveUI.DragSessionLimits(maxSessions = 2, lifetime = 1.hour)
        def start(id: String) = UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(id, Chunk.empty, Drag.Operation.Copy, Absent, Drag.Point(0, 0), UI.Modifiers.none)
        )
        val expired = Drag.Decision.Reject(Drag.Rejection.Application("The drag session expired."))
        Clock.withTimeControl { control =>
            for
                resolutions <- AtomicRef.init(Chunk.empty[(String, Drag.Decision)])
                _ <- DragCommands.resolveSink.let(Present((id, decision) => resolutions.getAndUpdate(_.append((id, decision))).unit)) {
                    withDispatch(UI.div, limits) { dispatch =>
                        for
                            _ <- dispatch(Seq.empty, start("later"))
                            _ <- control.advance(Duration.Zero, 100.millis)
                            _ <- control.set(Instant.Epoch - 2.hours, 100.millis)
                            _ <- dispatch(Seq.empty, start("earlier"))
                            _ <- control.advance(1.hour, 100.millis)
                        yield ()
                    }
                }
                actual <- resolutions.get
            yield assert(actual == Chunk("earlier" -> expired))
        }
    }

    "drag activation, dedicated identities, and handle markers render by namespace" in {
        val native  = Drag.Source("native", Chunk.empty, activation = Drag.Activation.Native)
        val sensors = Drag.Source("sensors", Chunk.empty, activation = Drag.Activation.Sensors)
        val both    = Drag.Source("source\"&<", Chunk.empty)
        val target  = Drag.Target("target\"&<", Drag.Accept())
        val cases = Chunk(
            UI.div.dragSource(native)   -> true,
            UI.div.dragSource(sensors)  -> false,
            UI.div.dragSource(both)     -> true,
            Svg.rect.dragSource(native) -> false,
            Svg.rect.dragSource(both)   -> false
        )

        for
            rendered   <- Kyo.foreach(cases) { case (ui, _) => HtmlRenderer.render(ui, Seq.empty) }
            dual       <- HtmlRenderer.render(UI.div.dragSource(both).dropTarget(target), Seq.empty)
            htmlHandle <- HtmlRenderer.render(UI.span.dragHandle, Seq.empty)
            svgHandle  <- HtmlRenderer.render(Svg.circle.dragHandle, Seq.empty)
        yield
            rendered.zip(cases).foreach { case (html, (_, expectedDraggable)) =>
                assert(html.contains("draggable=\"true\"") == expectedDraggable)
                assert(html.contains("data-kyo-drag-source="))
            }
            assert(attribute(dual, "data-kyo-drag-source-key") == "source&quot;&amp;&lt;")
            assert(attribute(dual, "data-kyo-drop-target-key") == "target&quot;&amp;&lt;")
            assert(attribute(dual, "data-kyo-drag-key") == "source&quot;&amp;&lt;")
            assert(htmlHandle.contains("data-kyo-drag-handle=\"true\""))
            assert(svgHandle.contains("data-kyo-drag-handle=\"true\""))
            val defaultConfig = htmlUnescape(attribute(rendered(2), "data-kyo-drag-source"))
            assert(defaultConfig.contains("\"activation\":{\"Both\":{}}"))
        end for
    }

    "browser drag configs preserve safe byte boundaries and reject unsafe or excessive metadata" in {
        val maxSafe      = 9_007_199_254_740_991L
        val safeFile     = Drag.FileMeta("file-token", "file.txt", mediaType("text/plain"), ByteSize.fromBytes(maxSafe), Instant.Epoch)
        val unsafeFile   = safeFile.copy(size = ByteSize.fromBytes(maxSafe + 1L))
        val safeSource   = Drag.Source("safe-source", Chunk(Drag.Item.File(safeFile)))
        val unsafeSource = Drag.Source("unsafe-source", Chunk(Drag.Item.File(unsafeFile)))
        val safeTarget   = Drag.Target("safe-target", Drag.Accept(maxFileSize = Present(ByteSize.fromBytes(maxSafe))))
        val unsafeTarget = Drag.Target("unsafe-target", Drag.Accept(maxFileSize = Present(ByteSize.fromBytes(maxSafe + 1L))))
        val tooMany      = Drag.Source("many", Chunk.fill(DragProtocol.Limits.default.maxItemCount + 1)(Drag.Item.Uri("https://kyo.dev")))
        val tooMuchText =
            Drag.Source("text", Chunk(dragText("text/plain" -> ("x" * (DragProtocol.Limits.default.maxTextLength + 1)))))
        val aggregateText = "x" * DragProtocol.Limits.default.maxAttributeLength
        val tooLargeJson  = Drag.Source("aggregate", Chunk(dragText("text/plain" -> aggregateText)))
        val tooManyRepresentations = Drag.Source(
            "representations",
            Chunk(Drag.Item.Text(
                (0 to DragProtocol.Limits.default.maxTextRepresentationCount).map(i => mediaType(s"text/x-$i") -> "x").toMap
            ))
        )
        val invalidKey = Drag.Source("x" * (DragProtocol.Limits.default.maxIdentifierLength + 1), Chunk.empty)
        val invalidLabel = Drag.Source(
            "label",
            Chunk.empty,
            label = Present("x" * (DragProtocol.Limits.default.maxNameLength + 1))
        )
        val invalidTargetMedia = DragProtocol.TargetConfig(
            "media",
            DragProtocol.AcceptConfig(Set("not-a-media-type"), Drag.AllowedOperations.all, Absent, Absent, directories = false),
            Absent,
            Drag.Orientation.Vertical,
            Drag.Collision.ClosestEdge
        )
        val invalidTargetCount = Drag.Target(
            "count",
            Drag.Accept(maxItems = Present(DragProtocol.Limits.default.maxItemCount + 1))
        )

        val safeSourceConfig = DragProtocol.sourceConfig(safeSource, DragProtocol.Limits.default)
        val safeTargetConfig = DragProtocol.targetConfig(safeTarget, DragProtocol.Limits.default)
        val safeSourceJson   = DragProtocol.encodedSourceConfig(safeSource, DragProtocol.Limits.default)
        val safeTargetJson   = DragProtocol.encodedTargetConfig(safeTarget, DragProtocol.Limits.default)
        assert(safeSourceConfig.isSuccess)
        assert(safeTargetConfig.isSuccess)
        assert(safeSourceJson == safeSourceConfig.map(Json.encode(_)))
        assert(safeTargetJson == safeTargetConfig.map(Json.encode(_)))
        assert(DragProtocol.sourceConfig(unsafeSource, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.targetConfig(unsafeTarget, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(tooMany, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(tooMuchText, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(tooLargeJson, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(tooManyRepresentations, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(invalidKey, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.sourceConfig(invalidLabel, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.validateTargetConfig(invalidTargetMedia, DragProtocol.Limits.default).isFailure)
        assert(DragProtocol.targetConfig(invalidTargetCount, DragProtocol.Limits.default).isFailure)
        Chunk[UI](
            UI.div.dragSource(unsafeSource),
            UI.div.dropTarget(unsafeTarget),
            UI.div.dragSource(tooMany),
            UI.div.dragSource(tooMuchText),
            UI.div.dragSource(tooLargeJson)
        ).foreach { invalidUi =>
            val error = intercept[IllegalArgumentException](HtmlRenderer.render(invalidUi, Seq.empty))
            assert(error.getMessage.contains("Invalid"))
        }

        for
            sourceHtml <- HtmlRenderer.render(UI.div.dragSource(safeSource), Seq.empty)
            targetHtml <- HtmlRenderer.render(UI.div.dropTarget(safeTarget), Seq.empty)
        yield
            val sourceJson = htmlUnescape(attribute(sourceHtml, "data-kyo-drag-source"))
            val targetJson = htmlUnescape(attribute(targetHtml, "data-kyo-drop-target"))
            assert(safeSourceJson == Result.succeed(sourceJson))
            assert(safeTargetJson == Result.succeed(targetJson))
            assert(Json.decode[DragProtocol.SourceConfig](sourceJson) == safeSourceConfig)
            assert(Json.decode[DragProtocol.TargetConfig](targetJson) == safeTargetConfig)
            assert(sourceJson.contains(maxSafe.toString))
            assert(targetJson.contains(maxSafe.toString))
        end for
    }

    "drag action handlers stay lazy, propagate failures, and coexist with typed handlers" in {
        val dropFailure = new RuntimeException("drop failure")
        val sortFailure = new RuntimeException("sort failure")
        val ui = UI.div
            .onDrop(Sync.defer(throw dropFailure))
            .onDrop((_: Drag.Event) => Drag.Decision.Accept)
            .onSortMove(Sync.defer(throw sortFailure))
            .onSortMove((_: Drag.Move) => Drag.Decision.Accept)
            .onDragOver("action-result")
            .onDragOver((_: Drag.Event) => "typed-result")

        assert(ui.attrs.onDrop.nonEmpty)
        assert(ui.attrs.onDropEvt.nonEmpty)
        assert(ui.attrs.onSortMove.nonEmpty)
        assert(ui.attrs.onSortMoveEvt.nonEmpty)
        assert(ui.attrs.onDragOver.nonEmpty)
        assert(ui.attrs.onDragOverEvt.nonEmpty)

        val drop = ui.attrs.onDrop.getOrElse(fail("missing drop action"))
        val sort = ui.attrs.onSortMove.getOrElse(fail("missing sort action"))
        for
            html       <- HtmlRenderer.render(ui, Seq.empty)
            dropResult <- Abort.run[Any](drop)
            sortResult <- Abort.run[Any](sort)
        yield
            assert(attribute(html, "data-kyo-ev") == "dragover,drop,sortmove")
            dropResult match
                case Result.Panic(error) => assert(error eq dropFailure)
                case other               => fail(s"expected drop panic, got $other")
            sortResult match
                case Result.Panic(error) => assert(error eq sortFailure)
                case other               => fail(s"expected sort panic, got $other")
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
