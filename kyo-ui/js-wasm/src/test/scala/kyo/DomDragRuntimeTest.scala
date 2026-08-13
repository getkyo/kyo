package kyo

import kyo.internal.*
import org.scalajs.dom
import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js as scalajs

class DomDragRuntimeTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    final private class FakeTransfer:
        private val values = scalajs.Dictionary.empty[String]
        val types          = scalajs.Array[String]()
        val files          = scalajs.Array[scalajs.Any]()
        val items          = scalajs.Array[scalajs.Any]()
        var dragImageCalls = 0

        val raw: scalajs.Dynamic = scalajs.Dynamic.literal(
            types = types,
            files = files,
            items = items,
            effectAllowed = "uninitialized",
            dropEffect = "none",
            setData = (mediaType: String, value: String) => setData(mediaType, value),
            getData = (mediaType: String) => getData(mediaType),
            setDragImage = (_: dom.Element, _: Double, _: Double) => dragImageCalls += 1
        )

        def effectAllowed: String = raw.effectAllowed.asInstanceOf[String]
        def dropEffect: String    = raw.dropEffect.asInstanceOf[String]

        def allow(value: String): Unit = raw.effectAllowed = value

        def addFile(file: scalajs.Dynamic, item: scalajs.Dynamic): Unit =
            discard(files.push(file))
            discard(items.push(item))

        def setData(mediaType: String, value: String): Unit =
            values(mediaType) = value
            if !types.contains(mediaType) then discard(types.push(mediaType))

        def getData(mediaType: String): String = values.getOrElse(mediaType, "")

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

    "runs a native internal drag through scoped capture listeners" in {
        val source = Drag.Source(
            "source-key",
            Chunk(
                Drag.Item.Text(Map("text/plain" -> "card", "text/html" -> "<b>card</b>")),
                Drag.Item.Uri("https://kyo.dev/card")
            ),
            operations = Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Move, Drag.Operation.Link)),
            preview = Drag.Preview.Native
        )
        val target = Drag.Target(
            "target-key",
            Drag.Accept(
                mediaTypes = Set("text/plain", "text/html", "text/uri-list"),
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
                    assert(transfer.effectAllowed == "all")
                    assert(transfer.getData("text/plain") == "card")
                    assert(transfer.getData("text/html") == "<b>card</b>")
                    assert(transfer.getData("text/uri-list") == "https://kyo.dev/card")

                    val enter = dragEvent("dragenter", nested, transfer, 30, 40, alt = true)
                    discard(nested.dispatchEvent(enter))
                    assert(enter.defaultPrevented)
                    assert(transfer.dropEffect == "link")

                    val over = dragEvent("dragover", nested, transfer, 31, 41, ctrl = true)
                    discard(nested.dispatchEvent(over))
                    assert(over.defaultPrevented)
                    assert(transfer.dropEffect == "copy")

                    val drop = dragEvent("drop", nested, transfer, 32, 42, ctrl = true)
                    discard(nested.dispatchEvent(drop))
                    assert(drop.defaultPrevented)

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
                    discard(handle.dispatchEvent(dragEvent("dragend", handle, transfer, 32, 42)))
                    assert(events.last == UIEvent.DragEnd(
                        Seq("root", "source"),
                        DragProtocol.EndData(id, Drag.Operation.Copy, cancelled = false)
                    ))
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)
                    fixture.remove()
                }
            yield ()
        }
    }

    "selects the nearest accepted target and suppresses false descendant leaves" in {
        val source       = Drag.Source("source", Chunk(Drag.Item.Text(Map("text/plain" -> "one"))))
        val rejected     = Drag.Target("inner", Drag.Accept(mediaTypes = Set("image/png"), operations = Drag.AllowedOperations.copy))
        val accepted     = Drag.Target("outer", Drag.Accept(mediaTypes = Set("text/plain"), operations = Drag.AllowedOperations.move))
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
                    val start = events.head.asInstanceOf[UIEvent.DragStart]
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

                    val drop = dragEvent("drop", child, transfer, 21, 31)
                    discard(child.dispatchEvent(drop))
                    assert(drop.defaultPrevented)
                    val detail = Json.encode[HtmlOp](HtmlOp.ResolveDrag(start.event.sessionId, Drag.Decision.Accept))
                    val resolution = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].CustomEvent)(
                        "kyo:resolve-drag",
                        scalajs.Dynamic.literal(detail = detail)
                    ).asInstanceOf[dom.Event]
                    discard(child.dispatchEvent(dragEvent("dragend", child, transfer, 21, 31)))
                    assert(events.count(_.isInstanceOf[UIEvent.DragEnd]) == 0)
                    discard(dom.document.dispatchEvent(resolution))
                    discard(dom.document.dispatchEvent(resolution))
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
            Drag.Accept(mediaTypes = Set("image/png"), operations = Drag.AllowedOperations.copy)
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
                    Seq("dragenter", "dragover", "drop").foreach { eventType =>
                        val event = dragEvent(eventType, targetEl, transfer, 1, 2)
                        discard(targetEl.dispatchEvent(event))
                        assert(!event.defaultPrevented)
                        assert(transfer.dropEffect == "none")
                    }
                    assert(events.isEmpty)
                    assert(runtime.activeSessions == 0)
                    assert(runtime.fileTokens == 0)
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
                            Chunk(Drag.Item.Text(Map("text/plain" -> s"item-$index"))),
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
                            Chunk(Drag.Item.Text(Map("text/plain" -> "preview"))),
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

end DomDragRuntimeTest
