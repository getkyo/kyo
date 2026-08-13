package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.collection.mutable
import scala.scalajs.js

/** Scoped native HTML drag runtime for a locally mounted UI. */
private[kyo] object DomDragRuntime:

    private val limits             = DragProtocol.Limits.default
    private val pendingTimeoutMs   = 5 * 60 * 1000
    private val expiryIntervalMs   = 1000
    private val sourceAttribute    = "data-kyo-drag-source"
    private val sourceKeyAttribute = "data-kyo-drag-source-key"
    private val targetAttribute    = "data-kyo-drop-target"
    private val targetKeyAttribute = "data-kyo-drop-target-key"

    /** Runtime control and diagnostics retained for drag file handoff support. */
    private[kyo] trait Handle:
        def resolve(sessionId: String, decision: Drag.Decision): Unit
        def activeSessions: Int
        def fileTokens: Int
        private[kyo] def fileToken(token: String): Maybe[js.Any]
    end Handle

    final private case class Located[A](element: dom.Element, path: Seq[String], config: A)

    final private class Session(
        val id: String,
        val items: Chunk[DragProtocol.ItemData],
        val source: Maybe[Located[DragProtocol.SourceConfig]],
        val allowed: Drag.AllowedOperations,
        val tokens: mutable.Map[String, js.Any],
        val preview: Maybe[dom.Element],
        val startedAt: Double,
        var operation: Drag.Operation,
        var target: Maybe[Located[DragProtocol.TargetConfig]],
        var dropped: Boolean,
        var accepted: Boolean,
        var browserEnded: Boolean,
        var ended: Boolean
    )

    /** Installs one capture listener for each native drag phase and one scoped resolution listener. */
    private[kyo] def install(container: dom.Element, enqueue: UIEvent => Unit)(using Frame): Handle < (Sync & Scope) =
        Sync.defer {
            val runtime = new Runtime(container, enqueue)
            Scope.ensure(Sync.defer(runtime.close())).andThen(runtime.install.map(_ => runtime))
        }
    end install

    final private class Runtime(container: dom.Element, enqueue: UIEvent => Unit)(using Frame) extends Handle:
        private var active: Session           = null
        private var closed                    = false
        private var sequence                  = 0L
        private var interval: js.UndefOr[Int] = js.undefined

        private val dragListener: js.Function1[dom.Event, Unit] = (event: dom.Event) => handle(event)

        private val resolveListener: js.Function1[dom.Event, Unit] = (event: dom.Event) => resolveEvent(event)

        def activeSessions: Int = if active == null then 0 else 1
        def fileTokens: Int     = if active == null then 0 else active.tokens.size

        private[kyo] def fileToken(token: String): Maybe[js.Any] =
            if active == null then Absent else Maybe.fromOption(active.tokens.get(token))

        def resolve(sessionId: String, decision: Drag.Decision): Unit =
            val session = active
            if !closed && session != null && !session.ended && session.id == sessionId && session.dropped then
                decision match
                    case Drag.Decision.Accept =>
                        session.accepted = true
                        if session.browserEnded then finish(session, cancelled = false)
                    case _: Drag.Decision.Reject => finish(session, cancelled = true)
            end if
        end resolve

        def install(using Frame): Unit < (Sync & Scope) =
            for
                _ <- add(container, "dragstart", dragListener)
                _ <- add(container, "dragenter", dragListener)
                _ <- add(container, "dragover", dragListener)
                _ <- add(container, "dragleave", dragListener)
                _ <- add(container, "drop", dragListener)
                _ <- add(container, "dragend", dragListener)
                _ <- add(document, "kyo:resolve-drag", resolveListener)
                _ <- Scope.acquireRelease(Sync.defer {
                    val id = dom.window.setInterval(() => expire(), expiryIntervalMs)
                    interval = id
                    id
                })(id =>
                    Sync.defer {
                        dom.window.clearInterval(id)
                        interval = js.undefined
                    }
                ).unit
            yield ()

        private def add(target: dom.EventTarget, eventType: String, listener: js.Function1[dom.Event, Unit])(using
            Frame
        ): Unit < (Sync & Scope) =
            final case class Installed(target: dom.EventTarget, eventType: String, listener: js.Function1[dom.Event, Unit])
            Scope.acquireRelease(Sync.defer {
                target.addEventListener(eventType, listener, useCapture = true)
                Installed(target, eventType, listener)
            })(installed =>
                Sync.defer(installed.target.removeEventListener(installed.eventType, installed.listener, useCapture = true))
            ).unit
        end add

        def close(): Unit =
            if !closed then
                closed = true
                val session = active
                if session != null then finish(session, cancelled = true)
            end if
        end close

        private def handle(event: dom.Event): Unit =
            if !closed then
                event.`type` match
                    case "dragstart" => start(event)
                    case "dragenter" => targetEvent(event, UIEvent.DragEnter.apply)
                    case "dragover"  => targetEvent(event, UIEvent.DragOver.apply)
                    case "dragleave" => leave(event)
                    case "drop"      => drop(event)
                    case "dragend"   => end(event)
                    case _           => ()
        end handle

        private def start(event: dom.Event): Unit =
            if active != null then finish(active, cancelled = true)
            val eventTarget = asElement(event.target)
            closestSource(eventTarget).foreach { source =>
                if !source.config.handle || closestAttribute(eventTarget, "data-kyo-drag-handle", source.element).nonEmpty then
                    val transfer = dataTransfer(event)
                    if transfer != null then
                        transfer.updateDynamic("effectAllowed")(effectAllowed(source.config.operations))
                        preferred(source.config.operations, null, modifiers(event)).foreach { operation =>
                            val id = token("drag")
                            setInternalData(transfer, source.config)
                            val preview = createPreview(source.element, source.config.preview, transfer)
                            val session = Session(
                                id,
                                source.config.items,
                                Present(source),
                                source.config.operations,
                                mutable.Map.empty,
                                preview,
                                js.Date.now(),
                                operation,
                                Absent,
                                dropped = false,
                                accepted = false,
                                browserEnded = false,
                                ended = false
                            )
                            active = session
                            emitStart(session, source.path, event)
                        }
                    end if
                end if
            }
        end start

        private def targetEvent(
            event: dom.Event,
            make: (Seq[String], DragProtocol.TargetData) => UIEvent
        ): Unit =
            val session = ensureSession(event)
            if session != null then
                acceptedTarget(asElement(event.target), session, modifiers(event)).foreach { case (target, operation) =>
                    event.preventDefault()
                    setDropEffect(event, operation)
                    session.operation = operation
                    session.target = Present(target)
                    enqueue(make(target.path, targetData(session, target, event, operation)))
                }
            end if
        end targetEvent

        private def leave(event: dom.Event): Unit =
            val session = active
            if session != null && !session.ended then
                session.target.foreach { target =>
                    val related = asElement(event.asInstanceOf[js.Dynamic].relatedTarget.asInstanceOf[Any])
                    val remains = related != null && target.element.contains(related)
                    if !remains then
                        enqueue(UIEvent.DragLeave(target.path, targetData(session, target, event, session.operation)))
                        session.target = Absent
                }
            end if
        end leave

        private def drop(event: dom.Event): Unit =
            val session = ensureSession(event)
            if session != null then
                acceptedTarget(asElement(event.target), session, modifiers(event)).foreach { case (target, operation) =>
                    event.preventDefault()
                    setDropEffect(event, operation)
                    session.operation = operation
                    session.target = Present(target)
                    session.dropped = true
                    enqueue(UIEvent.Drop(target.path, targetData(session, target, event, operation)))
                }
            end if
        end drop

        private def end(event: dom.Event): Unit =
            val session = active
            if session != null && !session.ended then
                session.browserEnded = true
                if !session.dropped then finish(session, cancelled = true)
                else if session.accepted then finish(session, cancelled = false)
            end if
        end end

        private def finish(session: Session, cancelled: Boolean): Unit =
            if !session.ended then
                session.ended = true
                session.preview.foreach(_.remove())
                session.tokens.clear()
                val path = session.source.map(_.path).getOrElse(Seq.empty)
                enqueue(UIEvent.DragEnd(path, DragProtocol.EndData(session.id, session.operation, cancelled)))
                if active eq session then active = null
            end if
        end finish

        private def expire(): Unit =
            val session = active
            if session != null && js.Date.now() - session.startedAt >= pendingTimeoutMs - expiryIntervalMs then
                finish(session, cancelled = true)
        end expire

        private def resolveEvent(event: dom.Event): Unit =
            val detail = event.asInstanceOf[js.Dynamic].detail
            if js.typeOf(detail) == "string" then
                Json.decode[HtmlOp](detail.asInstanceOf[String]) match
                    case Result.Success(HtmlOp.ResolveDrag(sessionId, decision)) => resolve(sessionId, decision)
                    case _                                                       => ()
            end if
        end resolveEvent

        private def ensureSession(event: dom.Event): Session =
            if active != null then active
            else
                val transfer = dataTransfer(event)
                if transfer == null then null
                else
                    snapshot(transfer) match
                        case Present((items, allowed, tokens)) =>
                            val operation = preferred(allowed, null, modifiers(event)).getOrElse(Drag.Operation.Move)
                            val session = Session(
                                token("drag"),
                                items,
                                Absent,
                                allowed,
                                tokens,
                                Absent,
                                js.Date.now(),
                                operation,
                                Absent,
                                dropped = false,
                                accepted = false,
                                browserEnded = false,
                                ended = false
                            )
                            active = session
                            emitStart(session, Seq.empty, event)
                            session
                        case Absent => null
                end if
        end ensureSession

        private def emitStart(session: Session, path: Seq[String], event: dom.Event): Unit =
            val sourceKey = session.source.map(_.config.key)
            val start = UIEvent.DragStart(
                path,
                DragProtocol.StartData(session.id, session.items, session.operation, sourceKey, point(event), modifiers(event))
            )
            DragProtocol.validate(DragProtocol.ClientMessage.Event(start), limits) match
                case Result.Success(_) => enqueue(start)
                case _                 => discardSession(session)
        end emitStart

        private def discardSession(session: Session): Unit =
            session.ended = true
            session.preview.foreach(_.remove())
            session.tokens.clear()
            if active eq session then active = null
        end discardSession

        private def acceptedTarget(
            from: dom.Element,
            session: Session,
            mods: UI.Modifiers
        ): Maybe[(Located[DragProtocol.TargetConfig], Drag.Operation)] =
            var current = from
            while current != null && container.contains(current) do
                decodeTarget(current).foreach { target =>
                    val operation = preferred(session.allowed, target.config.accepts.operations, mods)
                    operation.foreach { selected =>
                        if accepts(target.config.accepts, session.items) then return Present((target, selected))
                    }
                }
                current = parent(current)
            end while
            Absent
        end acceptedTarget

        private def accepts(config: DragProtocol.AcceptConfig, items: Chunk[DragProtocol.ItemData]): Boolean =
            import DragWireByteSize.*
            val accept = Drag.Accept(
                config.mediaTypes,
                config.operations,
                config.maxItems,
                config.maxFileSize.map(_.value),
                config.directories
            )
            config.maxItems.forall(items.size <= _) && items.forall(item => accept.accepts(item.toDomain))
        end accepts

        private def targetData(
            session: Session,
            target: Located[DragProtocol.TargetConfig],
            event: dom.Event,
            operation: Drag.Operation
        ): DragProtocol.TargetData =
            DragProtocol.TargetData(
                session.id,
                operation,
                Present(target.config.key),
                point(event),
                modifiers(event),
                Present(Drag.Position.Inside)
            )

        private def closestSource(from: dom.Element): Maybe[Located[DragProtocol.SourceConfig]] =
            var current = from
            while current != null && container.contains(current) do
                decodeSource(current) match
                    case value @ Present(_) => return value
                    case Absent             => current = parent(current)
            end while
            Absent
        end closestSource

        private def decodeSource(element: dom.Element): Maybe[Located[DragProtocol.SourceConfig]] =
            decode[DragProtocol.SourceConfig](element, sourceAttribute).filter { located =>
                val dedicated = element.getAttribute(sourceKeyAttribute)
                dedicated != null && dedicated == located.config.key
            }

        private def decodeTarget(element: dom.Element): Maybe[Located[DragProtocol.TargetConfig]] =
            decode[DragProtocol.TargetConfig](element, targetAttribute).filter { located =>
                val dedicated = element.getAttribute(targetKeyAttribute)
                dedicated != null && dedicated == located.config.key
            }

        private def decode[A: Schema](element: dom.Element, attribute: String): Maybe[Located[A]] =
            val raw = element.getAttribute(attribute)
            if raw == null || raw.length > limits.maxAttributeLength then Absent
            else
                validPath(element.getAttribute("data-kyo-path")).flatMap { path =>
                    Json.decode[A](raw) match
                        case Result.Success(config) => Present(Located(element, path, config))
                        case _                      => Absent
                }
            end if
        end decode

        private def validPath(raw: String): Maybe[Seq[String]] =
            if raw == null then Absent
            else
                val path = if raw.isEmpty then Seq.empty else raw.split("\\.", -1).toSeq
                if path.size <= limits.maxPathDepth && path.forall(segment =>
                        segment.nonEmpty && segment.length <= limits.maxIdentifierLength
                    )
                then Present(path)
                else Absent
                end if

        private def setInternalData(transfer: js.Dynamic, config: DragProtocol.SourceConfig): Unit =
            config.items.foreach {
                case DragProtocol.ItemData.Text(representations) =>
                    representations.foreach((mediaType, value) => discard(transfer.setData(mediaType, value)))
                case DragProtocol.ItemData.Uri(value) => discard(transfer.setData("text/uri-list", value))
                case _                                => ()
            }

        private def snapshot(
            transfer: js.Dynamic
        ): Maybe[(Chunk[DragProtocol.ItemData], Drag.AllowedOperations, mutable.Map[String, js.Any])] =
            val collected = ChunkBuilder.init[DragProtocol.ItemData]
            val tokens    = mutable.Map.empty[String, js.Any]
            val text      = mutable.Map.empty[String, String]
            val rawTypes  = transfer.types
            if !js.isUndefined(rawTypes) && rawTypes != null then
                val types = rawTypes.asInstanceOf[js.Array[String]]
                var index = 0
                while index < types.length && text.size < limits.maxTextRepresentationCount do
                    val mediaType = types(index)
                    if mediaType == "text/uri-list" then
                        val value = transfer.getData(mediaType).asInstanceOf[String]
                        if value.nonEmpty then collected.addOne(DragProtocol.ItemData.Uri(value))
                    else if mediaType.contains("/") then
                        text(mediaType) = transfer.getData(mediaType).asInstanceOf[String]
                    end if
                    index += 1
                end while
            end if
            if text.nonEmpty then collected.addOne(DragProtocol.ItemData.Text(text.toMap))

            val rawItems  = transfer.items
            val rawFiles  = transfer.files
            val itemList  = if js.isUndefined(rawItems) || rawItems == null then null else rawItems.asInstanceOf[js.Array[js.Dynamic]]
            val files     = if js.isUndefined(rawFiles) || rawFiles == null then null else rawFiles.asInstanceOf[js.Array[js.Dynamic]]
            val fileCount = if files == null then 0 else files.length
            var index     = 0
            var valid     = true
            while index < fileCount do
                val file = files(index)
                val item =
                    if itemList == null || index >= itemList.length then null
                    else itemList(index)
                directory(item) match
                    case Present(entry) =>
                        val fallback =
                            if js.isUndefined(file.name) || file.name == null then "directory" else file.name.asInstanceOf[String]
                        val name = safeName(entry.name, fallback)
                        val id   = token("directory")
                        tokens(id) = entry
                        collected.addOne(DragProtocol.ItemData.Directory(id, name))
                    case Absent =>
                        fileMeta(file) match
                            case Present(metadata) =>
                                tokens(metadata.token) = file
                                collected.addOne(DragProtocol.ItemData.File(metadata))
                            case Absent => valid = false
                end match
                index += 1
            end while

            val result = collected.result()
            val allowed = allowedFromBrowser(
                if js.isUndefined(transfer.effectAllowed) then "all" else transfer.effectAllowed.asInstanceOf[String]
            )
            val start = UIEvent.DragStart(
                Seq.empty,
                DragProtocol.StartData("validation", result, Drag.Operation.Move, Absent, Drag.Point(0, 0), UI.Modifiers.none)
            )
            if !valid then Absent
            else
                DragProtocol.validate(DragProtocol.ClientMessage.Event(start), limits) match
                    case Result.Success(_) if result.nonEmpty && allowed.values.nonEmpty => Present((result, allowed, tokens))
                    case _                                                               => Absent
            end if
        end snapshot

        private def directory(item: js.Dynamic): Maybe[js.Dynamic] =
            if item == null then Absent
            else if js.typeOf(item.webkitGetAsEntry) == "function" then
                val entry = item.webkitGetAsEntry()
                if entry != null && !js.isUndefined(entry) && entry.isDirectory.asInstanceOf[Boolean] then Present(entry)
                else Absent
            else Absent

        private def fileMeta(file: js.Dynamic): Maybe[DragProtocol.FileMetaData] =
            import DragWireByteSize.*
            val size         = file.size.asInstanceOf[Double]
            val lastModified = file.lastModified.asInstanceOf[Double]
            if !size.isFinite || size < 0 || size > 9_007_199_254_740_991d || size != math.floor(size) ||
                !lastModified.isFinite || math.abs(lastModified) > 9_007_199_254_740_991d || lastModified != math.floor(lastModified)
            then Absent
            else
                val name = safeName(file.name, "file")
                val media =
                    val value = if js.isUndefined(file.`type`) then "" else file.`type`.asInstanceOf[String]
                    if value.nonEmpty then value else "application/octet-stream"
                val id = token("file")
                Present(DragProtocol.FileMetaData(
                    id,
                    name,
                    media,
                    DragProtocol.WireByteSize(ByteSize.fromBytes(size.toLong)),
                    Instant.fromJava(java.time.Instant.ofEpochMilli(lastModified.toLong))
                ))
            end if
        end fileMeta

        private def safeName(primary: js.Dynamic, fallback: String): String =
            val first    = if primary == null || js.isUndefined(primary) then "" else primary.asInstanceOf[String]
            val selected = if first.nonEmpty then first else fallback
            if selected.nonEmpty && selected.length <= limits.maxNameLength then selected else "unnamed"
        end safeName

        private def preferred(
            source: Drag.AllowedOperations,
            target: Drag.AllowedOperations | Null,
            mods: UI.Modifiers
        ): Maybe[Drag.Operation] =
            val common = if target == null then source.values else source.values.intersect(target.values)
            val requested =
                if mods.alt then Drag.Operation.Link
                else if mods.ctrl || mods.meta then Drag.Operation.Copy
                else Drag.Operation.Move
            if common.contains(requested) then Present(requested)
            else
                Seq(Drag.Operation.Move, Drag.Operation.Copy, Drag.Operation.Link).find(common.contains) match
                    case Some(value) => Present(value)
                    case None        => Absent
            end if
        end preferred

        private def effectAllowed(allowed: Drag.AllowedOperations): String =
            val values = allowed.values
            if values.isEmpty then "none"
            else if values == Set(Drag.Operation.Copy) then "copy"
            else if values == Set(Drag.Operation.Move) then "move"
            else if values == Set(Drag.Operation.Link) then "link"
            else if values == Set(Drag.Operation.Copy, Drag.Operation.Move) then "copyMove"
            else if values == Set(Drag.Operation.Copy, Drag.Operation.Link) then "copyLink"
            else if values == Set(Drag.Operation.Move, Drag.Operation.Link) then "linkMove"
            else "all"
            end if
        end effectAllowed

        private def allowedFromBrowser(value: String): Drag.AllowedOperations = value match
            case "none"     => Drag.AllowedOperations.none
            case "copy"     => Drag.AllowedOperations.copy
            case "move"     => Drag.AllowedOperations.move
            case "link"     => Drag.AllowedOperations.link
            case "copyMove" => Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Move))
            case "copyLink" => Drag.AllowedOperations(Set(Drag.Operation.Copy, Drag.Operation.Link))
            case "linkMove" => Drag.AllowedOperations(Set(Drag.Operation.Link, Drag.Operation.Move))
            case _          => Drag.AllowedOperations.all

        private def createPreview(element: dom.Element, preview: Drag.Preview, transfer: js.Dynamic): Maybe[dom.Element] =
            preview match
                case Drag.Preview.Native => Absent
                case Drag.Preview.Clone =>
                    val clone = element.cloneNode(deep = true).asInstanceOf[dom.Element]
                    previewNode(clone, transfer, hidden = false)
                case Drag.Preview.Hidden =>
                    val hidden = document.createElement("div")
                    hidden.setAttribute("aria-hidden", "true")
                    hidden.asInstanceOf[dom.html.Element].style.opacity = "0"
                    hidden.asInstanceOf[dom.html.Element].style.width = "1px"
                    hidden.asInstanceOf[dom.html.Element].style.height = "1px"
                    previewNode(hidden, transfer, hidden = true)
                case Drag.Preview.Label(value) =>
                    val label = document.createElement("div")
                    label.textContent = value
                    previewNode(label, transfer, hidden = false)

        private def previewNode(node: dom.Element, transfer: js.Dynamic, hidden: Boolean): Maybe[dom.Element] =
            node.setAttribute("data-kyo-drag-preview", "true")
            node.setAttribute("aria-hidden", "true")
            val style = node.asInstanceOf[dom.html.Element].style
            style.position = "fixed"
            style.left = "-10000px"
            style.top = "-10000px"
            discard(document.body.appendChild(node))
            if js.typeOf(transfer.setDragImage) == "function" then discard(transfer.setDragImage(node, 0, 0))
            Present(node)
        end previewNode

        private def setDropEffect(event: dom.Event, operation: Drag.Operation): Unit =
            val transfer = dataTransfer(event)
            if transfer != null then
                transfer.updateDynamic("dropEffect")(operation.toString.toLowerCase)
        end setDropEffect

        private def dataTransfer(event: dom.Event): js.Dynamic =
            val value = event.asInstanceOf[js.Dynamic].dataTransfer
            if js.isUndefined(value) || value == null then null else value.asInstanceOf[js.Dynamic]

        private def modifiers(event: dom.Event): UI.Modifiers =
            val dyn = event.asInstanceOf[js.Dynamic]
            UI.Modifiers(bool(dyn.ctrlKey), bool(dyn.altKey), bool(dyn.shiftKey), bool(dyn.metaKey))

        private def point(event: dom.Event): Drag.Point =
            val dyn = event.asInstanceOf[js.Dynamic]
            Drag.Point(number(dyn.clientX), number(dyn.clientY))

        private def bool(value: js.Dynamic): Boolean =
            !js.isUndefined(value) && value != null && value.asInstanceOf[Boolean]

        private def number(value: js.Dynamic): Double =
            if js.typeOf(value) == "number" && value.asInstanceOf[Double].isFinite then value.asInstanceOf[Double] else 0d

        private def asElement(value: Any): dom.Element = value match
            case element: dom.Element => element
            case _                    => null

        private def parent(element: dom.Element): dom.Element = element.parentNode match
            case value: dom.Element => value
            case _                  => null

        private def closestAttribute(from: dom.Element, attribute: String, stop: dom.Element): Maybe[dom.Element] =
            var current = from
            while current != null do
                if current.hasAttribute(attribute) then return Present(current)
                if current eq stop then return Absent
                current = parent(current)
            end while
            Absent
        end closestAttribute

        private def token(prefix: String): String =
            sequence += 1
            val crypto = js.Dynamic.global.selectDynamic("crypto")
            if !js.isUndefined(crypto) && crypto != null && js.typeOf(crypto.randomUUID) == "function" then
                s"$prefix-${crypto.randomUUID().asInstanceOf[String]}"
            else
                s"$prefix-${js.Date.now().toLong.toHexString}-${sequence.toHexString}-${(math.random() * Int.MaxValue).toInt.toHexString}"
            end if
        end token

    end Runtime

end DomDragRuntime
