package kyo

import kyo.internal.*
import org.scalajs.dom
import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js as scalajs

class DomDragRuntimeTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    private def mediaType(value: String): Drag.MediaType = Drag.MediaType.parse(value).get

    private def mediaTypePattern(value: String): Drag.MediaTypePattern = Drag.MediaTypePattern.parse(value).get

    private def dragText(representations: (String, String)*): Drag.Item.Text =
        Drag.Item.Text(representations.iterator.map((media, value) => mediaType(media) -> value).toMap)

    final private class FakeTransfer:
        private val values       = scalajs.Dictionary.empty[String]
        private val exposedFiles = scalajs.Array[scalajs.Any]()
        val types                = scalajs.Array[String]()
        val files                = scalajs.Array[scalajs.Any]()
        val items                = scalajs.Array[scalajs.Any]()
        var dragImageCalls       = 0
        private var readable     = false

        val raw: scalajs.Dynamic = scalajs.Dynamic.literal(
            types = types,
            files = exposedFiles,
            items = items,
            effectAllowed = "uninitialized",
            dropEffect = "none",
            setData = (mediaType: String, value: String) => setData(mediaType, value),
            getData = ((mediaType: String) =>
                if readable then values.getOrElse(mediaType, "")
                else throw new scalajs.JavaScriptException("DataTransfer is protected")),
            setDragImage = (_: dom.Element, _: Double, _: Double) => dragImageCalls += 1
        )

        def effectAllowed: String = raw.effectAllowed.asInstanceOf[String]
        def dropEffect: String    = raw.dropEffect.asInstanceOf[String]

        def allow(value: String): Unit = raw.effectAllowed = value

        def beginDrop(): Unit =
            readable = true
            raw.files = files

        def addFile(file: scalajs.Dynamic, item: scalajs.Dynamic): Unit =
            if scalajs.typeOf(item.getAsFile) != "function" then
                item.updateDynamic("getAsFile")(() => if readable then file else null)
            discard(files.push(file))
            discard(items.push(item))
        end addFile

        def setData(mediaType: String, value: String): Unit =
            values(mediaType) = value
            if !types.contains(mediaType) then
                discard(types.push(mediaType))
                discard(items.push(scalajs.Dynamic.literal(kind = "string", `type` = mediaType)))
        end setData

        def inspectData(mediaType: String): String = values.getOrElse(mediaType, "")

        def setDragImage(image: dom.Element, x: Double, y: Double): Unit =
            dragImageCalls += 1
    end FakeTransfer

    private def dragEvent(
        eventType: String,
        target: dom.Element,
        transfer: FakeTransfer,
        x: Double,
        y: Double,
        alt: Boolean = false,
        ctrl: Boolean = false,
        meta: Boolean = false,
        related: dom.Element = null
    ): dom.Event =
        val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].Event)(
            eventType,
            scalajs.Dynamic.literal(bubbles = true, cancelable = true)
        ).asInstanceOf[dom.Event]
        val dyn = event.asInstanceOf[scalajs.Dynamic]
        val obj = scalajs.Dynamic.global.Object
        def prop(name: String, value: scalajs.Any): Unit =
            discard(obj.defineProperty(dyn, name, scalajs.Dynamic.literal(value = value, configurable = true)))
        prop("dataTransfer", transfer.raw)
        prop("clientX", x)
        prop("clientY", y)
        prop("altKey", alt)
        prop("ctrlKey", ctrl)
        prop("shiftKey", false)
        prop("metaKey", meta)
        prop("relatedTarget", related)
        event
    end dragEvent

    private def success[E, A](result: Result[E, A]): A = result match
        case Result.Success(value) => value.asInstanceOf[A]
        case other                 => throw new AssertionError(s"expected success, got $other")

    final private class FakeTiming(var now: Double = 0d) extends DomDragRuntime.Timing:
        private var callback: () => Unit = () => ()
        var activeTimers                 = 0

        def nowMillis(): Double = now

        def every(millis: Int)(run: () => Unit): DomDragRuntime.CancelTimer =
            callback = run
            activeTimers += 1
            () => activeTimers -= 1
        end every

        def checkAt(value: Double): Unit =
            now = value
            callback()
    end FakeTiming

    final private class MountedDiagnostics extends DomBackend.MountDiagnostics:
        var runtime: DomDragRuntime.Handle                                    = null
        var drain: Maybe[Fiber[Unit, Any]]                                    = Absent
        val queued                                                            = ArrayBuffer.empty[UIEvent]
        val handled                                                           = ArrayBuffer.empty[UIEvent]
        var started                                                           = false
        def channelClosed(): Unit                                             = ()
        def drainInterrupting(): Unit                                         = ()
        def drainJoined(): Unit                                               = ()
        override def dragRuntimeInstalled(value: DomDragRuntime.Handle): Unit = runtime = value
        override def dragEventQueued(event: UIEvent): Unit                    = queued += event
        override def dragEventHandled(event: UIEvent): Unit                   = handled += event
        override def drainInstalled(value: Fiber[Unit, Any]): Unit            = drain = Present(value)
        override def drainStarted(): Unit                                     = started = true
    end MountedDiagnostics

    "runs a native internal drag through scoped capture listeners" in {
        val source = Drag.Source(
            "source-key",
            Chunk(
                dragText("text/plain" -> "card", "text/html" -> "<b>card</b>"),
                Drag.Item.Uri("https://kyo.dev/card")
            ),
            operations = Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Move, Drag.Operation.Link)),
            preview = Drag.Preview.Native
        )
        val target = Drag.Target(
            "target-key",
            Drag.Accept(
                mediaTypes = Set(
                    mediaTypePattern("text/plain"),
                    mediaTypePattern("text/html"),
                    mediaTypePattern("text/uri-list")
                ),
                operations = Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Link)),
                maxItems = Present(3)
            )
        )
        val sourceJson = success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
        val targetJson = success(DragProtocol.encodedTargetConfig(target, DragProtocol.Limits.default))
        val events     = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        s"""<div data-kyo-path="root.source" data-kyo-drag-source='${sourceJson}' data-kyo-drag-source-key="source-key"><span id="handle">drag</span></div>
                           |<div data-kyo-path="root.target" data-kyo-drop-target='${targetJson}' data-kyo-drop-target-key="target-key"><span id="nested">drop</span></div>""".stripMargin
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    val handle   = fixture.querySelector("#handle").asInstanceOf[dom.Element]
                    val nested   = fixture.querySelector("#nested").asInstanceOf[dom.Element]
                    discard(handle.dispatchEvent(dragEvent("dragstart", handle, transfer, 10, 20)))
                    assert(runtime.stateName == "Dragging")
                    assert(runtime.sourceItemConversions == 1)
                    val domainItemsIdentity = runtime.domainItemsIdentity
                    assert(domainItemsIdentity != 0)
                    assert(transfer.effectAllowed == "all")
                    assert(transfer.inspectData("text/plain") == "card")
                    assert(transfer.inspectData("text/html") == "<b>card</b>")
                    assert(transfer.inspectData("text/uri-list") == "https://kyo.dev/card")

                    val enter = dragEvent("dragenter", nested, transfer, 30, 40, alt = true)
                    discard(nested.dispatchEvent(enter))
                    assert(enter.defaultPrevented)
                    assert(transfer.dropEffect == "link")
                    assert(runtime.targetConfigConversions == 1)

                    val over = dragEvent("dragover", nested, transfer, 31, 41, ctrl = true)
                    discard(nested.dispatchEvent(over))
                    assert(over.defaultPrevented)
                    assert(transfer.dropEffect == "copy")
                    assert(runtime.domainItemsIdentity == domainItemsIdentity)
                    assert(runtime.targetConfigConversions == 1)

                    val drop = dragEvent("drop", nested, transfer, 32, 42, ctrl = true)
                    discard(nested.dispatchEvent(drop))
                    assert(drop.defaultPrevented)
                    assert(runtime.stateName == "AwaitingDecision")

                    val start = events(0).asInstanceOf[UIEvent.DragStart]
                    val id    = start.event.sessionId
                    assert(events == Seq(
                        UIEvent.DragStart(
                            Seq("root", "source"),
                            DragProtocol.StartData(
                                id,
                                success(DragProtocol.sourceConfig(source, DragProtocol.Limits.default)).items,
                                Drag.Operation.Move,
                                Present("source-key"),
                                Drag.Point(10, 20),
                                UI.Modifiers.none
                            )
                        ),
                        UIEvent.DragEnter(
                            Seq("root", "target"),
                            DragProtocol.TargetData(
                                id,
                                Drag.Operation.Link,
                                Present("target-key"),
                                Drag.Point(30, 40),
                                UI.Modifiers(false, true, false, false),
                                Present(Drag.Position.Inside)
                            )
                        ),
                        UIEvent.DragOver(
                            Seq("root", "target"),
                            DragProtocol.TargetData(
                                id,
                                Drag.Operation.Copy,
                                Present("target-key"),
                                Drag.Point(31, 41),
                                UI.Modifiers(true, false, false, false),
                                Present(Drag.Position.Inside)
                            )
                        ),
                        UIEvent.Drop(
                            Seq("root", "target"),
                            DragProtocol.TargetData(
                                id,
                                Drag.Operation.Copy,
                                Present("target-key"),
                                Drag.Point(32, 42),
                                UI.Modifiers(true, false, false, false),
                                Present(Drag.Position.Inside)
                            )
                        )
                    ))

                    runtime.resolve(id, Drag.Decision.Accept)
                    assert(runtime.stateName == "AcceptedAwaitingEnd")
                    discard(handle.dispatchEvent(dragEvent("dragend", handle, transfer, 32, 42)))
                    assert(runtime.stateName == "Idle")
                    assert(events.last == UIEvent.DragEnd(
                        Seq("root", "source"),
                        DragProtocol.EndData(id, Drag.Operation.Copy, cancelled = false)
                    ))
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)

                    events.clear()
                    discard(handle.dispatchEvent(dragEvent("dragstart", handle, transfer, 50, 60)))
                    discard(nested.dispatchEvent(dragEvent("drop", nested, transfer, 51, 61)))
                    val awaitingId = events.head.asInstanceOf[UIEvent.DragStart].event.sessionId
                    assert(runtime.stateName == "AwaitingDecision")
                    runtime.resolve(awaitingId, Drag.Decision.Reject(Drag.Rejection.Application("rejected")))
                    assert(runtime.stateName == "Idle")
                    assert(events.last == UIEvent.DragEnd(
                        Seq("root", "source"),
                        DragProtocol.EndData(awaitingId, Drag.Operation.Copy, cancelled = true)
                    ))

                    events.clear()
                    discard(handle.dispatchEvent(dragEvent("dragstart", handle, transfer, 70, 80)))
                    discard(nested.dispatchEvent(dragEvent("drop", nested, transfer, 71, 81)))
                    val endedAwaitingId = events.head.asInstanceOf[UIEvent.DragStart].event.sessionId
                    discard(handle.dispatchEvent(dragEvent("dragend", handle, transfer, 71, 81)))
                    assert(runtime.stateName == "AwaitingDecisionAfterEnd")
                    runtime.resolve(endedAwaitingId, Drag.Decision.Reject(Drag.Rejection.Application("rejected after end")))
                    assert(runtime.stateName == "Idle")
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 1)
                    fixture.remove()
                }
            yield ()
        }
    }

    "selects the nearest accepted target and suppresses false descendant leaves" in {
        val source = Drag.Source("source", Chunk(dragText("text/plain" -> "one")))
        val rejected = Drag.Target(
            "inner",
            Drag.Accept(mediaTypes = Set(mediaTypePattern("image/png")), operations = Drag.AllowedOperations.copy)
        )
        val accepted = Drag.Target(
            "outer",
            Drag.Accept(mediaTypes = Set(mediaTypePattern("text/plain")), operations = Drag.AllowedOperations.move)
        )
        val sourceJson   = success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
        val rejectedJson = success(DragProtocol.encodedTargetConfig(rejected, DragProtocol.Limits.default))
        val acceptedJson = success(DragProtocol.encodedTargetConfig(accepted, DragProtocol.Limits.default))
        val events       = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        """<div data-kyo-path="source" data-kyo-drag-source="" data-kyo-drag-source-key="source"><span id="source-child"></span></div>
                          |<div id="outer" data-kyo-path="outer" data-kyo-drop-target="" data-kyo-drop-target-key="outer">
                          |  <div id="inner" data-kyo-path="outer.inner" data-kyo-drop-target="" data-kyo-drop-target-key="inner"><span id="a"></span><span id="b"></span></div>
                          |</div>""".stripMargin
                    root.querySelector("[data-kyo-drag-source]").asInstanceOf[dom.Element].setAttribute("data-kyo-drag-source", sourceJson)
                    root.querySelector("#outer").asInstanceOf[dom.Element].setAttribute("data-kyo-drop-target", acceptedJson)
                    root.querySelector("#inner").asInstanceOf[dom.Element].setAttribute("data-kyo-drop-target", rejectedJson)
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    val sourceEl = fixture.querySelector("#source-child").asInstanceOf[dom.Element]
                    val a        = fixture.querySelector("#a").asInstanceOf[dom.Element]
                    val b        = fixture.querySelector("#b").asInstanceOf[dom.Element]
                    discard(sourceEl.dispatchEvent(dragEvent("dragstart", sourceEl, transfer, 0, 0)))
                    val enter = dragEvent("dragenter", a, transfer, 4, 5)
                    discard(a.dispatchEvent(enter))
                    assert(enter.defaultPrevented)
                    assert(events.last.asInstanceOf[UIEvent.DragEnter].event.targetKey == Present("outer"))
                    discard(a.dispatchEvent(dragEvent("dragleave", a, transfer, 6, 7, related = b)))
                    assert(events.count(_.isInstanceOf[UIEvent.DragLeave]) == 0)
                    discard(a.dispatchEvent(dragEvent("dragleave", a, transfer, 8, 9, related = sourceEl)))
                    assert(events.last.isInstanceOf[UIEvent.DragLeave])
                    val id = events.head.asInstanceOf[UIEvent.DragStart].event.sessionId
                    discard(sourceEl.dispatchEvent(dragEvent("dragend", sourceEl, transfer, 8, 9)))
                    assert(events.last == UIEvent.DragEnd(Seq("source"), DragProtocol.EndData(id, Drag.Operation.Move, cancelled = true)))
                    fixture.remove()
                }
            yield ()
        }
    }

    "snapshots external text URI file and directory metadata and resolves once from CustomEvent" in {
        val target = Drag.Target(
            "uploads",
            Drag.Accept(
                operations = Drag.AllowedOperations.copy,
                maxItems = Present(4),
                maxFileSize = Present(ByteSize.fromBytes(1024)),
                directories = true
            )
        )
        val targetJson = success(DragProtocol.encodedTargetConfig(target, DragProtocol.Limits.default))
        val events     = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        """<div id="upload" data-kyo-path="root.upload" data-kyo-drop-target="" data-kyo-drop-target-key="uploads"><span id="upload-child"></span></div>"""
                    root.querySelector("#upload").asInstanceOf[dom.Element].setAttribute("data-kyo-drop-target", targetJson)
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    transfer.allow("copy")
                    transfer.setData("text/plain", "external")
                    transfer.setData("text/uri-list", "https://kyo.dev/external")
                    transfer.addFile(
                        scalajs.Dynamic.literal(name = "report.txt", `type` = "text/plain", size = 12d, lastModified = 1_700_000_000_000d),
                        scalajs.Dynamic.literal(kind = "file")
                    )
                    val directoryEntry = scalajs.Dynamic.literal(isDirectory = true, name = "assets")
                    transfer.addFile(
                        scalajs.Dynamic.literal(
                            name = "assets",
                            `type` = "application/octet-stream",
                            size = 0d,
                            lastModified = 1_700_000_000_000d
                        ),
                        scalajs.Dynamic.literal(kind = "file", webkitGetAsEntry = () => directoryEntry)
                    )

                    val child = fixture.querySelector("#upload-child").asInstanceOf[dom.Element]
                    val enter = dragEvent("dragenter", child, transfer, 20, 30, meta = true)
                    discard(child.dispatchEvent(enter))
                    assert(enter.defaultPrevented)
                    assert(transfer.dropEffect == "copy")
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 1)
                    assert(runtime.fileTokens == 0)
                    assert(runtime.stateName == "ExternalProbing")
                    val contextIdentity = runtime.contextIdentity
                    val transitionCount = runtime.transitionCount
                    (1 to 20).foreach { _ =>
                        val over = dragEvent("dragover", child, transfer, 20, 30, meta = true)
                        discard(child.dispatchEvent(over))
                        assert(over.defaultPrevented)
                    }
                    discard(child.dispatchEvent(dragEvent("dragstart", child, transfer, 20, 30)))
                    discard(child.dispatchEvent(dragEvent("dragend", child, transfer, 20, 30)))
                    assert(runtime.contextIdentity == contextIdentity)
                    assert(runtime.transitionCount == transitionCount)
                    assert(runtime.stateName == "ExternalProbing")
                    assert(events.isEmpty)

                    val probedId  = runtime.activeSessionId.getOrElse(fail("missing protected drag session"))
                    val rejection = Drag.Decision.Reject(Drag.Rejection.Application("capacity"))
                    runtime.resolve(probedId, rejection)
                    runtime.resolve(probedId, rejection)
                    assert(runtime.stateName == "Idle")
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)
                    assert(events.isEmpty)

                    val resumed = dragEvent("dragover", child, transfer, 20, 30, meta = true)
                    discard(child.dispatchEvent(resumed))
                    assert(resumed.defaultPrevented)
                    assert(runtime.stateName == "ExternalProbing")

                    transfer.beginDrop()
                    val drop = dragEvent("drop", child, transfer, 21, 31)
                    discard(child.dispatchEvent(drop))
                    assert(drop.defaultPrevented)
                    assert(events.size == 2)
                    assert(runtime.stateName == "AwaitingDecisionAfterEnd")
                    val start = events(0).asInstanceOf[UIEvent.DragStart]
                    assert(events(1).isInstanceOf[UIEvent.Drop])
                    assert(start.path == Seq.empty)
                    assert(start.event.sourceKey == Absent)
                    assert(start.event.operation == Drag.Operation.Copy)
                    assert(start.event.items.size == 4)
                    assert(start.event.items(0) == DragProtocol.ItemData.Uri("https://kyo.dev/external"))
                    assert(start.event.items(1) == DragProtocol.ItemData.Text(Map("text/plain" -> "external")))
                    val fileData = start.event.items(2).asInstanceOf[DragProtocol.ItemData.File].meta
                    assert(fileData.name == "report.txt")
                    assert(fileData.mediaType == "text/plain")
                    assert(fileData.size.value == ByteSize.fromBytes(12))
                    assert(start.event.items(3).asInstanceOf[DragProtocol.ItemData.Directory].name == "assets")
                    assert(runtime.fileTokens == 2)

                    val awaitingIdentity    = runtime.contextIdentity
                    val awaitingTransitions = runtime.transitionCount
                    Seq("dragenter", "dragover", "dragleave", "dragend", "drop", "dragstart").foreach { eventType =>
                        discard(child.dispatchEvent(dragEvent(eventType, child, transfer, 22, 32)))
                    }
                    assert(runtime.stateName == "AwaitingDecisionAfterEnd")
                    assert(runtime.contextIdentity == awaitingIdentity)
                    assert(runtime.transitionCount == awaitingTransitions)
                    assert(events.size == 2)

                    val detail = Json.encode[HtmlOp](HtmlOp.ResolveDrag(start.event.sessionId, Drag.Decision.Accept))
                    val resolution = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].CustomEvent)(
                        "kyo:resolve-drag",
                        scalajs.Dynamic.literal(detail = detail)
                    ).asInstanceOf[dom.Event]
                    discard(dom.document.dispatchEvent(resolution))
                    discard(dom.document.dispatchEvent(resolution))
                    assert(runtime.stateName == "Idle")
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 1)
                    assert(events.last == UIEvent.DragEnd(
                        Seq.empty,
                        DragProtocol.EndData(start.event.sessionId, Drag.Operation.Copy, cancelled = false)
                    ))
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "rejects incompatible targets and unsafe external file numbers synchronously" in {
        val target = Drag.Target(
            "images",
            Drag.Accept(mediaTypes = Set(mediaTypePattern("image/png")), operations = Drag.AllowedOperations.copy)
        )
        val targetJson = success(DragProtocol.encodedTargetConfig(target, DragProtocol.Limits.default))
        val events     = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        """<div id="images" data-kyo-path="images" data-kyo-drop-target="" data-kyo-drop-target-key="images"></div>"""
                    root.querySelector("#images").asInstanceOf[dom.Element].setAttribute("data-kyo-drop-target", targetJson)
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    transfer.allow("copy")
                    transfer.addFile(
                        scalajs.Dynamic.literal(
                            name = "bad.png",
                            `type` = "image/png",
                            size = scalajs.Dynamic.global.Number.POSITIVE_INFINITY,
                            lastModified = 0d
                        ),
                        scalajs.Dynamic.literal(kind = "file")
                    )
                    val targetEl = fixture.querySelector("#images").asInstanceOf[dom.Element]
                    Seq("dragenter", "dragover").foreach { eventType =>
                        val event = dragEvent(eventType, targetEl, transfer, 1, 2)
                        discard(targetEl.dispatchEvent(event))
                        assert(event.defaultPrevented)
                        assert(transfer.dropEffect == "copy")
                    }
                    assert(events.isEmpty)
                    transfer.beginDrop()
                    val drop = dragEvent("drop", targetEl, transfer, 1, 2)
                    discard(targetEl.dispatchEvent(drop))
                    assert(!drop.defaultPrevented)
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "rejects invalid external media types during probing and snapshotting" in {
        val targetJson = success(DragProtocol.encodedTargetConfig(
            Drag.Target("target", Drag.Accept(operations = Drag.AllowedOperations.copy)),
            DragProtocol.Limits.default
        ))
        val events = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        """<div id="target" data-kyo-path="target" data-kyo-drop-target="" data-kyo-drop-target-key="target"></div>"""
                    root.querySelector("#target").asInstanceOf[dom.Element].setAttribute("data-kyo-drop-target", targetJson)
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val targetEl = fixture.querySelector("#target").asInstanceOf[dom.Element]

                    val invalidProbe = new FakeTransfer
                    invalidProbe.allow("copy")
                    invalidProbe.setData("text/plain", "valid")
                    invalidProbe.setData("text/*", "invalid")
                    val enter = dragEvent("dragenter", targetEl, invalidProbe, 1, 2)
                    discard(targetEl.dispatchEvent(enter))
                    assert(!enter.defaultPrevented)
                    assert(runtime.stateName == "Idle")

                    val invalidSnapshot = new FakeTransfer
                    invalidSnapshot.allow("copy")
                    invalidSnapshot.setData(" TEXT/PLAIN ", "canonical")
                    val validEnter = dragEvent("dragenter", targetEl, invalidSnapshot, 3, 4)
                    discard(targetEl.dispatchEvent(validEnter))
                    assert(validEnter.defaultPrevented)
                    assert(runtime.stateName == "ExternalProbing")
                    invalidSnapshot.setData("text/*", "invalid")
                    invalidSnapshot.beginDrop()
                    val drop = dragEvent("drop", targetEl, invalidSnapshot, 5, 6)
                    discard(targetEl.dispatchEvent(drop))
                    assert(!drop.defaultPrevented)
                    assert(runtime.stateName == "Idle")
                    assert(events.isEmpty)

                    val collidingProbe = new FakeTransfer
                    collidingProbe.allow("copy")
                    collidingProbe.setData("TEXT/PLAIN", "first")
                    collidingProbe.setData("text/plain", "second")
                    val rejectedCollisionEnter = dragEvent("dragenter", targetEl, collidingProbe, 6, 7)
                    discard(targetEl.dispatchEvent(rejectedCollisionEnter))
                    assert(!rejectedCollisionEnter.defaultPrevented)
                    assert(runtime.stateName == "Idle")

                    val collidingSnapshot = new FakeTransfer
                    collidingSnapshot.allow("copy")
                    collidingSnapshot.setData("TEXT/PLAIN", "first")
                    val collisionEnter = dragEvent("dragenter", targetEl, collidingSnapshot, 7, 8)
                    discard(targetEl.dispatchEvent(collisionEnter))
                    assert(collisionEnter.defaultPrevented)
                    collidingSnapshot.setData("text/plain", "second")
                    collidingSnapshot.beginDrop()
                    val collisionDrop = dragEvent("drop", targetEl, collidingSnapshot, 9, 10)
                    discard(targetEl.dispatchEvent(collisionDrop))
                    assert(!collisionDrop.defaultPrevented)
                    assert(runtime.stateName == "Idle")
                    assert(events.isEmpty)
                    fixture.remove()
                }
            yield ()
        }
    }

    "maps every allowed operation set and cleans custom previews" in {
        val cases: Seq[(Drag.AllowedOperations, (String, Maybe[Drag.Operation]))] = Seq(
            Drag.AllowedOperations.none                                           -> ("none", Absent),
            Drag.AllowedOperations.copy                                           -> ("copy", Present(Drag.Operation.Copy)),
            Drag.AllowedOperations.move                                           -> ("move", Present(Drag.Operation.Move)),
            Drag.AllowedOperations.link                                           -> ("link", Present(Drag.Operation.Link)),
            Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Move)) -> ("copyMove", Present(Drag.Operation.Move)),
            Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Link)) -> ("copyLink", Present(Drag.Operation.Copy)),
            Drag.AllowedOperations(Set(Drag.Operation.Move, Drag.Operation.Link)) -> ("linkMove", Present(Drag.Operation.Move)),
            Drag.AllowedOperations.all                                            -> ("all", Present(Drag.Operation.Move))
        )
        val events = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    cases.zipWithIndex.foreach { entry =>
                        val (allowed, expected)       = entry._1
                        val (browserValue, operation) = expected
                        val index                     = entry._2
                        val source = Drag.Source(
                            s"source-$index",
                            Chunk(dragText("text/plain" -> s"item-$index")),
                            operations = allowed,
                            preview = Drag.Preview.Native
                        )
                        val element = dom.document.createElement("div")
                        element.setAttribute("data-kyo-path", s"source.$index")
                        element.setAttribute("data-kyo-drag-source-key", source.key)
                        element.setAttribute(
                            "data-kyo-drag-source",
                            success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
                        )
                        discard(fixture.appendChild(element))
                        val transfer = new FakeTransfer
                        val before   = events.size
                        discard(element.dispatchEvent(dragEvent("dragstart", element, transfer, index, index)))
                        assert(transfer.effectAllowed == browserValue)
                        operation match
                            case Present(expected) =>
                                assert(events.size == before + 1)
                                assert(events.last.asInstanceOf[UIEvent.DragStart].event.operation == expected)
                                discard(element.dispatchEvent(dragEvent("dragend", element, transfer, index, index)))
                            case Absent => assert(events.size == before)
                        end match
                        element.remove()
                    }

                    Seq(
                        Drag.Preview.Clone               -> Absent,
                        Drag.Preview.Hidden              -> Absent,
                        Drag.Preview.Label("safe label") -> Present("safe label")
                    ).zipWithIndex.foreach { case ((preview, label), index) =>
                        val source = Drag.Source(
                            s"preview-$index",
                            Chunk(dragText("text/plain" -> "preview")),
                            preview = preview
                        )
                        val element = dom.document.createElement("div")
                        element.textContent = "original"
                        element.setAttribute("data-kyo-path", s"preview.$index")
                        element.setAttribute("data-kyo-drag-source-key", source.key)
                        element.setAttribute(
                            "data-kyo-drag-source",
                            success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
                        )
                        discard(fixture.appendChild(element))
                        val transfer = new FakeTransfer
                        discard(element.dispatchEvent(dragEvent("dragstart", element, transfer, 0, 0)))
                        assert(transfer.dragImageCalls == 1)
                        val previewNode = dom.document.querySelector("[data-kyo-drag-preview]").asInstanceOf[dom.Element]
                        assert(previewNode != null)
                        label.foreach(value => assert(previewNode.textContent == value))
                        discard(element.dispatchEvent(dragEvent("dragend", element, transfer, 0, 0)))
                        assert(dom.document.querySelector("[data-kyo-drag-preview]") == null)
                        element.remove()
                    }
                    assert(runtime.activeSessions == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "rejects decoded source and target configs mutated past protocol limits" in {
        val validSource = success(DragProtocol.sourceConfig(
            Drag.Source("source", Chunk(dragText("text/plain" -> "safe")), preview = Drag.Preview.Clone),
            DragProtocol.Limits.default
        ))
        val invalidSource = validSource.copy(label = Present("x" * (DragProtocol.Limits.default.maxNameLength + 1)))
        val validTarget = success(DragProtocol.targetConfig(
            Drag.Target("target", Drag.Accept(mediaTypes = Set(mediaTypePattern("text/plain")))),
            DragProtocol.Limits.default
        ))
        val invalidTarget = validTarget.copy(label = Present("x" * (DragProtocol.Limits.default.maxNameLength + 1)))
        val sensorSource  = validSource.copy(activation = Drag.Activation.Sensors)
        val events        = ArrayBuffer.empty[UIEvent]

        Scope.run {
            for
                fixture <- Sync.defer {
                    val root = dom.document.createElement("div")
                    root.innerHTML =
                        """<div id="bad-source" data-kyo-path="bad.source" data-kyo-drag-source-key="source"></div>
                          |<div id="sensor-source" data-kyo-path="sensor.source" data-kyo-drag-source-key="source"></div>
                          |<div id="bad-target" data-kyo-path="bad.target" data-kyo-drop-target-key="target"></div>""".stripMargin
                    root.querySelector("#bad-source").asInstanceOf[dom.Element]
                        .setAttribute("data-kyo-drag-source", Json.encode(invalidSource))
                    root.querySelector("#bad-target").asInstanceOf[dom.Element]
                        .setAttribute("data-kyo-drop-target", Json.encode(invalidTarget))
                    root.querySelector("#sensor-source").asInstanceOf[dom.Element]
                        .setAttribute("data-kyo-drag-source", Json.encode(sensorSource))
                    discard(dom.document.body.appendChild(root))
                    root
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    val sourceEl = fixture.querySelector("#bad-source").asInstanceOf[dom.Element]
                    discard(sourceEl.dispatchEvent(dragEvent("dragstart", sourceEl, transfer, 0, 0)))
                    assert(transfer.effectAllowed == "uninitialized")
                    assert(transfer.dragImageCalls == 0)
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 0)

                    val sensorTransfer = new FakeTransfer
                    val sensorEl       = fixture.querySelector("#sensor-source").asInstanceOf[dom.Element]
                    discard(sensorEl.dispatchEvent(dragEvent("dragstart", sensorEl, sensorTransfer, 0, 0)))
                    assert(sensorTransfer.effectAllowed == "uninitialized")
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 0)

                    transfer.allow("copy")
                    transfer.setData("text/plain", "external")
                    val targetEl = fixture.querySelector("#bad-target").asInstanceOf[dom.Element]
                    val over     = dragEvent("dragover", targetEl, transfer, 0, 0)
                    discard(targetEl.dispatchEvent(over))
                    assert(!over.defaultPrevented)
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "cleans an active source exactly once when rejected before drop" in {
        val source = Drag.Source("early", Chunk(dragText("text/plain" -> "early")))
        val events = ArrayBuffer.empty[UIEvent]
        Scope.run {
            for
                fixture <- Sync.defer {
                    val element = dom.document.createElement("div")
                    element.setAttribute("data-kyo-path", "early")
                    element.setAttribute("data-kyo-drag-source-key", source.key)
                    element.setAttribute(
                        "data-kyo-drag-source",
                        success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
                    )
                    discard(dom.document.body.appendChild(element))
                    element
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event)
                _ <- Sync.defer {
                    val transfer = new FakeTransfer
                    discard(fixture.dispatchEvent(dragEvent("dragstart", fixture, transfer, 0, 0)))
                    val id        = events.head.asInstanceOf[UIEvent.DragStart].event.sessionId
                    val rejection = Drag.Decision.Reject(Drag.Rejection.Application("duplicate"))
                    runtime.resolve(id, rejection)
                    runtime.resolve(id, rejection)
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 1)
                    assert(events.last == UIEvent.DragEnd(Seq("early"), DragProtocol.EndData(id, Drag.Operation.Move, cancelled = true)))
                    assert(runtime.activeSessions == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "expires at the exact five minute boundary using scoped timing" in {
        val source = Drag.Source("timed", Chunk(dragText("text/plain" -> "timed")))
        val events = ArrayBuffer.empty[UIEvent]
        val timing = new FakeTiming
        Scope.run {
            for
                fixture <- Sync.defer {
                    val element = dom.document.createElement("div")
                    element.setAttribute("data-kyo-path", "timed")
                    element.setAttribute("data-kyo-drag-source-key", source.key)
                    element.setAttribute(
                        "data-kyo-drag-source",
                        success(DragProtocol.encodedSourceConfig(source, DragProtocol.Limits.default))
                    )
                    discard(dom.document.body.appendChild(element))
                    element
                }
                runtime <- DomDragRuntime.install(fixture, event => events += event, timing)
                _ <- Sync.defer {
                    discard(fixture.dispatchEvent(dragEvent("dragstart", fixture, new FakeTransfer, 0, 0)))
                    assert(timing.activeTimers == 1)
                    timing.checkAt(DomDragRuntime.pendingTimeoutMs - 1)
                    assert(runtime.activeSessions == 1)
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 0)
                    timing.checkAt(DomDragRuntime.pendingTimeoutMs)
                    assert(runtime.activeSessions == 0)
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 1)
                    fixture.remove()
                }
            yield ()
        }.map(_ => assert(timing.activeTimers == 0))
    }

    "mount dispatches a protected external drop through handlers and local resolution" in {
        val source = Drag.Source("mounted-source", Chunk(dragText("text/plain" -> "internal")))
        val target = Drag.Target(
            "mounted-target",
            Drag.Accept(
                mediaTypes = Set(mediaTypePattern("text/plain")),
                operations = Drag.AllowedOperations.copy,
                maxItems = Present(2)
            )
        )
        val transfer = new FakeTransfer
        transfer.allow("copy")
        transfer.setData("text/plain", "external")
        transfer.addFile(
            scalajs.Dynamic.literal(name = "note.txt", `type` = "text/plain", size = 4d, lastModified = 1000d),
            scalajs.Dynamic.literal(kind = "file", `type` = "text/plain")
        )
        val diagnostics = new MountedDiagnostics

        for
            starts  <- AtomicRef.init(Chunk.empty[Drag.Event])
            drops   <- AtomicRef.init(Chunk.empty[Drag.Event])
            ends    <- AtomicRef.init(Chunk.empty[Drag.End])
            started <- Latch.init(1)
            dropped <- Latch.init(1)
            ended   <- Latch.init(1)
            ui = UI.div
                .onDragStart((event: Drag.Event) => starts.getAndUpdate(_.append(event)).andThen(started.release).unit)
                .onDragEnd((event: Drag.End) => ends.getAndUpdate(_.append(event)).andThen(ended.release).unit)(
                    UI.div
                        .id("mounted-source")
                        .dragSource(source)("source"),
                    UI.div
                        .id("mounted-target")
                        .dropTarget(target)
                        .onDrop((event: Drag.Event) =>
                            drops.getAndUpdate(_.append(event)).andThen(dropped.release).andThen(Drag.Decision.Accept)
                        )("target")
                )
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, diagnostics)))
            _ <- assertEventually(Sync.defer(
                diagnostics.runtime != null && diagnostics.started && dom.document.getElementById("mounted-target") != null
            ))
            _ <- Sync.defer {
                val targetEl = dom.document.getElementById("mounted-target")
                val over     = dragEvent("dragover", targetEl, transfer, 7, 8, ctrl = true)
                discard(targetEl.dispatchEvent(over))
                assert(over.defaultPrevented)
                assert(transfer.dropEffect == "copy")
            }
            _ <- starts.get.map(value => assert(value.isEmpty))
            _ <- drops.get.map(value => assert(value.isEmpty))
            _ <- Sync.defer {
                transfer.beginDrop()
                val targetEl = dom.document.getElementById("mounted-target")
                val drop     = dragEvent("drop", targetEl, transfer, 9, 10, ctrl = true)
                discard(targetEl.dispatchEvent(drop))
                assert(drop.defaultPrevented)
            }
            _            <- started.await
            _            <- dropped.await
            _            <- ended.await
            actualStarts <- starts.get
            actualDrops  <- drops.get
            actualEnds   <- ends.get
            mountDone    <- fiber.done
            drainDone    <- diagnostics.drain.getOrElse(Fiber.unit).done
            _ <- Sync.defer {
                assert(
                    (
                        actualStarts.size,
                        actualDrops.size,
                        actualEnds.size,
                        diagnostics.runtime.activeSessions,
                        diagnostics.runtime.fileTokens,
                        diagnostics.queued.size,
                        diagnostics.handled.size,
                        mountDone,
                        drainDone
                    ) ==
                        (1, 1, 1, 0, 0, 3, 3, false, false)
                )
                val start = actualStarts.head
                val drop  = actualDrops.head
                assert(start.sessionId == drop.sessionId)
                assert(start.items.size == 2)
                assert(start.items(0) == dragText("text/plain" -> "external"))
                assert(start.items(1).asInstanceOf[Drag.Item.File].meta.name == "note.txt")
                assert(start.operation == Drag.Operation.Copy)
                assert(start.sourceKey == Absent)
                assert(drop.targetKey == Present("mounted-target"))
                assert(drop.point == Present(Drag.Point(9, 10)))
                assert(drop.modifiers == UI.Modifiers(ctrl = true))
                assert(actualEnds == Chunk(Drag.End(drop, canceled = false)))
                assert(diagnostics.runtime.activeSessions == 0)
                assert(diagnostics.runtime.fileTokens == 0)
            }
            _ <- fiber.interrupt
            _ <- fiber.getResult
            _ <- Sync.defer {
                val detachedTarget = dom.document.getElementById("mounted-target")
                assert(detachedTarget == null || diagnostics.runtime.activeSessions == 0)
                assert(diagnostics.runtime.fileTokens == 0)
            }
        yield ()
        end for
    }

end DomDragRuntimeTest
