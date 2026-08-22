package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.collection.mutable
import scala.scalajs.js

/** Scoped native HTML drag runtime for a locally mounted UI. */
private[kyo] object DomDragRuntime:

    private val limits                = DragProtocol.Limits.default
    private[kyo] val pendingTimeoutMs = 5 * 60 * 1000
    private val expiryIntervalMs      = 1000
    private val sourceAttribute       = "data-kyo-drag-source"
    private val sourceKeyAttribute    = "data-kyo-drag-source-key"
    private val targetAttribute       = "data-kyo-drop-target"
    private val targetKeyAttribute    = "data-kyo-drop-target-key"
    private val selectedAttribute     = "data-kyo-drag-selected"
    private val draggingAttribute     = "data-kyo-dragging"
    private val dropValidAttribute    = "data-kyo-drop-valid"
    private val dropPositionAttribute = "data-kyo-drop-position"
    private val liveRegionAttribute   = "data-kyo-drag-live"

    private[kyo] val pointerActivationDistancePx = 6d
    private[kyo] val touchHoldDelayMs            = 250
    private[kyo] val touchSlopPx                 = 8d
    private val autoScrollEdgePx                 = 24d
    private val autoScrollStepPx                 = 16d

    private[kyo] trait CancelTimer:
        def cancel(): Unit

    private[kyo] trait Timing:
        def nowMillis(): Double
        def every(millis: Int)(run: () => Unit): CancelTimer

    private object BrowserTiming extends Timing:
        def nowMillis(): Double = js.Date.now()
        def every(millis: Int)(run: () => Unit): CancelTimer =
            val id = dom.window.setInterval(() => run(), millis)
            new CancelTimer:
                def cancel(): Unit = dom.window.clearInterval(id)
        end every
    end BrowserTiming

    final private[kyo] case class Rect(left: Double, top: Double, width: Double, height: Double) derives CanEqual:
        def midX: Double   = left + width / 2
        def midY: Double   = top + height / 2
        def right: Double  = left + width
        def bottom: Double = top + height
    end Rect

    /** Injectable hit testing so jsdom tests can supply element stacks and rectangles. */
    private[kyo] trait Hit:
        def elementsAt(x: Double, y: Double): Seq[dom.Element]
        def rect(element: dom.Element): Rect

    private object BrowserHit extends Hit:
        def elementsAt(x: Double, y: Double): Seq[dom.Element] =
            val raw = document.asInstanceOf[js.Dynamic].elementsFromPoint(x, y)
            if js.isUndefined(raw) || raw == null then Seq.empty
            else raw.asInstanceOf[js.Array[dom.Element]].toSeq
        end elementsAt
        def rect(element: dom.Element): Rect =
            val value = element.getBoundingClientRect()
            Rect(value.left, value.top, value.width, value.height)
    end BrowserHit

    /** Injectable animation-frame scheduler used to coalesce pointer moves. */
    private[kyo] trait Frames:
        def request(run: () => Unit): CancelTimer

    private object BrowserFrames extends Frames:
        def request(run: () => Unit): CancelTimer =
            val id = dom.window.requestAnimationFrame(_ => run())
            new CancelTimer:
                def cancel(): Unit = dom.window.cancelAnimationFrame(id)
        end request
    end BrowserFrames

    final private case class ProbeFile(mediaType: Maybe[Drag.MediaType], directory: Maybe[Boolean])

    final private case class Probe(textTypes: Set[Drag.MediaType], uri: Boolean, files: Chunk[ProbeFile]):
        def itemCount: Int = (if textTypes.nonEmpty then 1 else 0) + (if uri then 1 else 0) + files.size

    final private case class ProbeResult(manifest: Probe, allowed: Drag.AllowedOperations)
    final private case class Snapshot(
        wireItems: Chunk[DragProtocol.ItemData],
        domainItems: Chunk[Drag.Item],
        allowed: Drag.AllowedOperations,
        tokens: mutable.Map[String, js.Any]
    )
    final private case class TargetMatch(target: Located[DragProtocol.ValidatedTargetConfig], operation: Drag.Operation)
    final private case class ConfigCache[A](element: dom.Element, raw: String, value: Maybe[A])

    /** Runtime control and diagnostics retained for drag file handoff support. */
    private[kyo] trait Handle:
        def resolve(sessionId: String, decision: Drag.Decision): Unit
        def activeSessions: Int
        def fileTokens: Int
        def liveRegions: Int
        def activeFrames: Int
        private[kyo] def stateName: String
        private[kyo] def activeSessionId: Maybe[String]
        private[kyo] def contextIdentity: Int
        private[kyo] def domainItemsIdentity: Int
        private[kyo] def transitionCount: Int
        private[kyo] def sourceItemConversions: Int
        private[kyo] def targetConfigConversions: Int
        private[kyo] def fileToken(token: String): Maybe[js.Any]
    end Handle

    final private case class Located[A](element: dom.Element, path: Seq[String], config: A)

    final private class SessionContext(
        val id: String,
        val source: Maybe[Located[DragProtocol.ValidatedSourceConfig]],
        var allowed: Drag.AllowedOperations,
        val tokens: mutable.Map[String, js.Any],
        var preview: Maybe[dom.Element],
        val startedAt: Double,
        var operation: Drag.Operation,
        var target: Maybe[Located[DragProtocol.ValidatedTargetConfig]],
        var wireItems: Maybe[Chunk[DragProtocol.ItemData]],
        var domainItems: Maybe[Chunk[Drag.Item]]
    ) derives CanEqual:
        // Sensor sessions only; native HTML5 sessions leave these at their defaults.
        var sensor: Boolean                                                = false
        var keyboard: Boolean                                              = false
        var pointerId: Maybe[Double]                                       = Absent
        var liveRegion: Maybe[dom.Element]                                 = Absent
        var frame: Maybe[CancelTimer]                                      = Absent
        var lastPoint: Drag.Point                                          = Drag.Point(0, 0)
        var anchorItem: Maybe[Located[DragProtocol.ValidatedSourceConfig]] = Absent
        var anchorAfter: Boolean                                           = false
        var slotIndex: Int                                                 = 0
        var sourceCollection: Maybe[String]                                = Absent
        var selectionKeys: Chunk[String]                                   = Chunk.empty
    end SessionContext

    private enum State derives CanEqual:
        case Idle
        case ExternalProbing(context: SessionContext, probe: Probe)
        case PendingPointer(source: Located[DragProtocol.ValidatedSourceConfig], pointerId: Double, startX: Double, startY: Double)
        case PendingTouch(source: Located[DragProtocol.ValidatedSourceConfig], startX: Double, startY: Double, timer: CancelTimer)
        case Dragging(context: SessionContext)
        case AwaitingDecision(context: SessionContext)
        case AwaitingDecisionAfterEnd(context: SessionContext)
        case AcceptedAwaitingEnd(context: SessionContext)

        def maybeContext: Maybe[SessionContext] = this match
            case Idle                              => Absent
            case ExternalProbing(context, _)       => Present(context)
            case _: PendingPointer                 => Absent
            case _: PendingTouch                   => Absent
            case Dragging(context)                 => Present(context)
            case AwaitingDecision(context)         => Present(context)
            case AwaitingDecisionAfterEnd(context) => Present(context)
            case AcceptedAwaitingEnd(context)      => Present(context)
    end State

    private enum NativeEvent derives CanEqual:
        case Start, Enter, Over, Leave, Drop, End

    /** Installs one capture listener for each native drag phase, the sensor listeners, and one scoped resolution listener. */
    private[kyo] def install(
        container: dom.Element,
        enqueue: UIEvent => Unit,
        timing: Timing = BrowserTiming,
        hit: Hit = BrowserHit,
        frames: Frames = BrowserFrames
    )(using Frame): Handle < (Sync & Scope) =
        Sync.defer {
            val runtime = new Runtime(container, enqueue, timing, hit, frames)
            Scope.ensure(Sync.defer(runtime.close())).andThen(runtime.install.map(_ => runtime))
        }
    end install

    final private class Runtime(
        container: dom.Element,
        enqueue: UIEvent => Unit,
        timing: Timing,
        hit: Hit,
        frames: Frames
    )(using Frame) extends Handle:
        private var state: State                                                        = State.Idle
        private var closed                                                              = false
        private var sequence                                                            = 0L
        private var transitions                                                         = 0
        private var sourceConversions                                                   = 0
        private var targetConversions                                                   = 0
        private var sourceCache: Maybe[ConfigCache[DragProtocol.ValidatedSourceConfig]] = Absent
        private var targetCache: Maybe[ConfigCache[DragProtocol.ValidatedTargetConfig]] = Absent

        private val dragListener: js.Function1[dom.Event, Unit] = (event: dom.Event) => handle(event)

        private val sensorListener: js.Function1[dom.Event, Unit] = (event: dom.Event) => handleSensor(event)

        private val resolveListener: js.Function1[dom.Event, Unit] = (event: dom.Event) => resolveEvent(event)

        def activeSessions: Int                         = state.maybeContext.fold(0)(_ => 1)
        def fileTokens: Int                             = state.maybeContext.fold(0)(_.tokens.size)
        def liveRegions: Int                            = state.maybeContext.fold(0)(_.liveRegion.fold(0)(_ => 1))
        def activeFrames: Int                           = state.maybeContext.fold(0)(_.frame.fold(0)(_ => 1))
        private[kyo] def stateName: String              = state.productPrefix
        private[kyo] def activeSessionId: Maybe[String] = state.maybeContext.map(_.id)
        private[kyo] def contextIdentity: Int           = state.maybeContext.fold(0)(java.lang.System.identityHashCode)
        private[kyo] def domainItemsIdentity: Int =
            state.maybeContext.flatMap(_.domainItems).fold(0)(java.lang.System.identityHashCode)
        private[kyo] def transitionCount: Int         = transitions
        private[kyo] def sourceItemConversions: Int   = sourceConversions
        private[kyo] def targetConfigConversions: Int = targetConversions

        private[kyo] def fileToken(token: String): Maybe[js.Any] =
            state.maybeContext.flatMap(context => Maybe.fromOption(context.tokens.get(token)))

        def resolve(sessionId: String, decision: Drag.Decision): Unit =
            if !closed then
                state.maybeContext.foreach { context =>
                    if context.id == sessionId then
                        decision match
                            case Drag.Decision.Reject(rejection) =>
                                if context.sensor then announce(context, rejectionText(rejection))
                                finish(context, cancelled = true)
                            case Drag.Decision.Accept =>
                                state match
                                    case State.AwaitingDecision(current) if current eq context =>
                                        if context.sensor then
                                            announce(context, s"${sourceLabel(context)} dropped.")
                                            finish(context, cancelled = false)
                                        else move(State.AcceptedAwaitingEnd(context))
                                    case State.AwaitingDecisionAfterEnd(current) if current eq context =>
                                        finish(context, cancelled = false)
                                    case _ => ()
                    end if
                }
        end resolve

        def install(using Frame): Unit < (Sync & Scope) =
            for
                _ <- add(container, "dragstart", dragListener)
                _ <- add(container, "dragenter", dragListener)
                _ <- add(container, "dragover", dragListener)
                _ <- add(container, "dragleave", dragListener)
                _ <- add(container, "drop", dragListener)
                _ <- add(container, "dragend", dragListener)
                _ <- add(container, "pointerdown", sensorListener)
                _ <- add(container, "pointermove", sensorListener)
                _ <- add(container, "pointerup", sensorListener)
                _ <- add(container, "pointercancel", sensorListener)
                _ <- add(container, "touchstart", sensorListener)
                _ <- add(container, "touchmove", sensorListener)
                _ <- add(container, "touchend", sensorListener)
                _ <- add(container, "touchcancel", sensorListener)
                _ <- add(container, "keydown", sensorListener)
                _ <- add(document, "kyo:resolve-drag", resolveListener)
                _ <- Scope.acquireRelease(Sync.defer(timing.every(expiryIntervalMs)(() => expire())))(timer =>
                    Sync.defer(timer.cancel())
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
                state.maybeContext.foreach(finish(_, cancelled = true))
                sourceCache = Absent
                targetCache = Absent
            end if
        end close

        private def handle(event: dom.Event): Unit =
            if !closed then
                event.`type` match
                    case "dragstart" => transition(NativeEvent.Start, event)
                    case "dragenter" => transition(NativeEvent.Enter, event)
                    case "dragover"  => transition(NativeEvent.Over, event)
                    case "dragleave" => transition(NativeEvent.Leave, event)
                    case "drop"      => transition(NativeEvent.Drop, event)
                    case "dragend"   => transition(NativeEvent.End, event)
                    case _           => ()
        end handle

        private def transition(native: NativeEvent, event: dom.Event): Unit =
            (state, native) match
                case (State.Idle, NativeEvent.Start) => start(event)
                case (State.Idle, NativeEvent.Enter) => probeTarget(event, UIEvent.DragEnter.apply)
                case (State.Idle, NativeEvent.Over)  => probeTarget(event, UIEvent.DragOver.apply)
                case (State.Idle, NativeEvent.Drop)  => probeDrop(event)
                case (State.ExternalProbing(context, probe), NativeEvent.Enter) =>
                    targetEvent(event, context, Present(probe), UIEvent.DragEnter.apply)
                case (State.ExternalProbing(context, probe), NativeEvent.Over) =>
                    targetEvent(event, context, Present(probe), UIEvent.DragOver.apply)
                case (State.ExternalProbing(context, _), NativeEvent.Leave) => leave(event, context, emit = false)
                case (State.ExternalProbing(context, _), NativeEvent.Drop)  => externalDrop(event, context)
                case (State.Dragging(context), NativeEvent.Enter) =>
                    targetEvent(event, context, Absent, UIEvent.DragEnter.apply)
                case (State.Dragging(context), NativeEvent.Over) =>
                    targetEvent(event, context, Absent, UIEvent.DragOver.apply)
                case (State.Dragging(context), NativeEvent.Leave)          => leave(event, context, emit = true)
                case (State.Dragging(context), NativeEvent.Drop)           => internalDrop(event, context)
                case (State.Dragging(context), NativeEvent.End)            => finish(context, cancelled = true)
                case (State.AwaitingDecision(context), NativeEvent.End)    => move(State.AwaitingDecisionAfterEnd(context))
                case (State.AcceptedAwaitingEnd(context), NativeEvent.End) => finish(context, cancelled = false)
                case _                                                     => ()
        end transition

        // --- Sensor sessions (pointer, touch, keyboard) ---

        private def handleSensor(event: dom.Event): Unit =
            if !closed then
                (state, event.`type`) match
                    case (State.Idle, "pointerdown") =>
                        sensorSource(event).foreach { source =>
                            val at = point(event)
                            move(State.PendingPointer(source, pointerId(event), at.x, at.y))
                        }
                    case (State.PendingPointer(source, id, startX, startY), "pointermove") if pointerId(event) == id =>
                        val at = point(event)
                        if distance(at.x, at.y, startX, startY) >= pointerActivationDistancePx then
                            sensorLift(source, at, keyboard = false, pointer = Present(id))
                    case (_: State.PendingPointer, "pointerup" | "pointercancel") =>
                        move(State.Idle)
                    case (State.Idle, "touchstart") =>
                        sensorSource(event).foreach { source =>
                            val at = point(event)
                            val lift = () =>
                                state match
                                    case State.PendingTouch(pending, x, y, timer) if pending eq source =>
                                        timer.cancel()
                                        sensorLift(source, Drag.Point(x, y), keyboard = false, pointer = Absent)
                                    case _ => ()
                            val timer = timing.every(touchHoldDelayMs)(() => lift())
                            move(State.PendingTouch(source, at.x, at.y, timer))
                        }
                    case (State.PendingTouch(_, startX, startY, timer), "touchmove") =>
                        val at = point(event)
                        if distance(at.x, at.y, startX, startY) > touchSlopPx then
                            timer.cancel()
                            move(State.Idle)
                    case (State.PendingTouch(_, _, _, timer), "touchend" | "touchcancel") =>
                        timer.cancel()
                        move(State.Idle)
                    case (State.Dragging(context), "pointermove" | "touchmove") if context.sensor && !context.keyboard =>
                        context.lastPoint = point(event)
                        if context.frame.isEmpty then
                            context.frame = Present(frames.request { () =>
                                context.frame = Absent
                                if state.maybeContext.exists(_ eq context) then processSensorFrame(context)
                            })
                        end if
                    case (State.Dragging(context), "pointerup" | "touchend") if context.sensor && !context.keyboard =>
                        context.frame.foreach(_.cancel())
                        context.frame = Absent
                        processSensorFrame(context)
                        if context.target.nonEmpty then sensorDrop(context)
                        else finish(context, cancelled = true)
                    case (State.Dragging(context), "pointercancel" | "touchcancel") if context.sensor =>
                        finish(context, cancelled = true)
                    case (State.Idle, "keydown") if keyOf(event) == "Enter" || keyOf(event) == " " =>
                        sensorSource(event).foreach { source =>
                            event.preventDefault()
                            sensorLift(source, Drag.Point(0, 0), keyboard = true, pointer = Absent)
                        }
                    case (State.Dragging(context), "keydown") if context.sensor && context.keyboard =>
                        keyboardKey(context, event)
                    case _ => ()
        end handleSensor

        private def sensorSource(event: dom.Event): Maybe[Located[DragProtocol.ValidatedSourceConfig]] =
            asElement(event.target).flatMap(closestSource).filter { source =>
                val activation = source.config.wire.activation
                activation == Drag.Activation.Sensors || activation == Drag.Activation.Both
            }

        private def sensorLift(
            source: Located[DragProtocol.ValidatedSourceConfig],
            at: Drag.Point,
            keyboard: Boolean,
            pointer: Maybe[Double]
        ): Unit =
            val config = source.config.wire
            val context = SessionContext(
                token("drag"),
                Present(source),
                config.operations,
                mutable.Map.empty,
                Absent,
                timing.nowMillis(),
                Drag.Operation.Move,
                Absent,
                Present(config.items),
                Present(source.config.items)
            )
            context.sensor = true
            context.keyboard = keyboard
            context.pointerId = pointer
            context.lastPoint = at
            context.sourceCollection = enclosingTarget(source.element).map(_.config.wire.key)
            context.selectionKeys = selectionSnapshot(source)
            source.element.setAttribute(draggingAttribute, "true")
            pointer.foreach { id =>
                val dyn = source.element.asInstanceOf[js.Dynamic]
                if js.typeOf(dyn.setPointerCapture) == "function" then
                    try discard(dyn.setPointerCapture(id))
                    catch case _: Throwable => ()
            }
            if !keyboard then context.preview = sensorPreview(source.element)
            context.liveRegion = Present(createLiveRegion())
            if keyboard then
                enclosingTarget(source.element).foreach { target =>
                    context.target = Present(target)
                    target.element.setAttribute(dropValidAttribute, "true")
                    // The starting slot is the dragged item's own position: the count of remaining items before it.
                    context.slotIndex = keyboardItems(context, target).count { item =>
                        val position = source.element.compareDocumentPosition(item.element)
                        (position & dom.Node.DOCUMENT_POSITION_PRECEDING) != 0
                    }
                }
            end if
            move(State.Dragging(context))
            announce(context, s"${sourceLabel(context)} picked up.")
            emitStart(context, source.path, at, UI.Modifiers.none)
        end sensorLift

        private def sensorPreview(element: dom.Element): Maybe[dom.Element] =
            val clone = element.cloneNode(deep = true).asInstanceOf[dom.Element]
            clone.setAttribute("data-kyo-drag-preview", "true")
            clone.setAttribute("aria-hidden", "true")
            val style = clone.asInstanceOf[dom.html.Element].style
            style.position = "fixed"
            style.pointerEvents = "none"
            style.opacity = "0.8"
            discard(document.body.appendChild(clone))
            Present(clone)
        end sensorPreview

        private def createLiveRegion(): dom.Element =
            val region = document.createElement("div")
            region.setAttribute(liveRegionAttribute, "true")
            region.setAttribute("aria-live", "assertive")
            region.setAttribute("role", "status")
            val style = region.asInstanceOf[dom.html.Element].style
            style.position = "fixed"
            style.left = "-10000px"
            style.top = "-10000px"
            style.width = "1px"
            style.height = "1px"
            style.overflow = "hidden"
            discard(document.body.appendChild(region))
            region
        end createLiveRegion

        private def announce(context: SessionContext, message: String): Unit =
            context.liveRegion.foreach(_.textContent = message)

        private def sourceLabel(context: SessionContext): String =
            context.source.fold("Item")(source => source.config.wire.label.getOrElse(source.config.wire.key))

        private def itemLabel(item: Located[DragProtocol.ValidatedSourceConfig]): String =
            item.config.wire.label.getOrElse(item.config.wire.key)

        private def rejectionText(rejection: Drag.Rejection): String =
            rejection match
                case Drag.Rejection.Application(reason) => s"Move rejected: $reason"
                case other                              => s"Move rejected: ${other.productPrefix}"

        private def processSensorFrame(context: SessionContext): Unit =
            val at    = context.lastPoint
            val stack = hit.elementsAt(at.x, at.y)
            context.preview.foreach { preview =>
                val style = preview.asInstanceOf[dom.html.Element].style
                style.left = s"${at.x + 8}px"
                style.top = s"${at.y + 8}px"
            }
            autoScroll(at)
            val matched = stack.iterator
                .filter(element => (container.contains(element)) || (element eq container))
                .map(element => acceptedTarget(element, context, Absent, UI.Modifiers.none))
                .collectFirst { case Present(value) => value }
            matched match
                case Some(matchedTarget) =>
                    val previousTarget = context.target
                    val changed        = !previousTarget.exists(_.element eq matchedTarget.target.element)
                    if changed then clearDropPresentation(context)
                    previousTarget.filter(_ => changed).foreach(_.element.removeAttribute(dropValidAttribute))
                    context.operation = matchedTarget.operation
                    context.target = Present(matchedTarget.target)
                    matchedTarget.target.element.setAttribute(dropValidAttribute, "true")
                    updatePointerAnchor(context, matchedTarget.target, stack, at)
                    val data = DragProtocol.TargetData(
                        context.id,
                        matchedTarget.operation,
                        Present(matchedTarget.target.config.wire.key),
                        at,
                        UI.Modifiers.none,
                        Present(Drag.Position.Inside)
                    )
                    if changed then enqueue(UIEvent.DragEnter(matchedTarget.target.path, data))
                    else enqueue(UIEvent.DragOver(matchedTarget.target.path, data))
                case None =>
                    context.target.foreach(_.element.removeAttribute(dropValidAttribute))
                    clearDropPresentation(context)
                    context.target = Absent
            end match
        end processSensorFrame

        private def updatePointerAnchor(
            context: SessionContext,
            target: Located[DragProtocol.ValidatedTargetConfig],
            stack: Seq[dom.Element],
            at: Drag.Point
        ): Unit =
            val excluded = context.selectionKeys.toSet
            val anchor = stack.iterator
                .filter(element => target.element.contains(element) && !(element eq target.element))
                .map(element => decodeSource(element))
                .collectFirst { case Present(item) if !excluded.contains(item.config.wire.key) => item }
            context.anchorItem.foreach(_.element.removeAttribute(dropPositionAttribute))
            context.anchorItem = Absent
            anchor.foreach { item =>
                val bounds = hit.rect(item.element)
                val after = target.config.wire.orientation match
                    case Drag.Orientation.Horizontal => at.x >= bounds.midX
                    case _                           => at.y >= bounds.midY
                context.anchorItem = Present(item)
                context.anchorAfter = after
                item.element.setAttribute(dropPositionAttribute, if after then "after" else "before")
                announce(context, s"${if after then "After" else "Before"} ${itemLabel(item)}.")
            }
        end updatePointerAnchor

        private def autoScroll(at: Drag.Point): Unit =
            val bounds = hit.rect(container)
            if bounds.width > 0 && bounds.height > 0 then
                val dyn = container.asInstanceOf[js.Dynamic]
                if at.y - bounds.top < autoScrollEdgePx then dyn.scrollTop = number(dyn.scrollTop) - autoScrollStepPx
                else if bounds.bottom - at.y < autoScrollEdgePx then dyn.scrollTop = number(dyn.scrollTop) + autoScrollStepPx
                if at.x - bounds.left < autoScrollEdgePx then dyn.scrollLeft = number(dyn.scrollLeft) - autoScrollStepPx
                else if bounds.right - at.x < autoScrollEdgePx then dyn.scrollLeft = number(dyn.scrollLeft) + autoScrollStepPx
            end if
        end autoScroll

        private def keyboardItems(
            context: SessionContext,
            target: Located[DragProtocol.ValidatedTargetConfig]
        ): Chunk[Located[DragProtocol.ValidatedSourceConfig]] =
            val excluded = context.selectionKeys.toSet
            val nodes    = target.element.querySelectorAll(s"[$sourceKeyAttribute]")
            val builder  = ChunkBuilder.init[Located[DragProtocol.ValidatedSourceConfig]]
            var index    = 0
            while index < nodes.length do
                val element = nodes(index)
                if element != null then
                    decodeSource(element).foreach { item =>
                        if !excluded.contains(item.config.wire.key) then builder.addOne(item)
                    }
                end if
                index += 1
            end while
            builder.result()
        end keyboardItems

        private def containerTargets(): Chunk[Located[DragProtocol.ValidatedTargetConfig]] =
            val nodes   = container.querySelectorAll(s"[$targetKeyAttribute]")
            val builder = ChunkBuilder.init[Located[DragProtocol.ValidatedTargetConfig]]
            var index   = 0
            while index < nodes.length do
                val element = nodes(index)
                if element != null then decodeTarget(element).foreach(builder.addOne)
                index += 1
            end while
            builder.result()
        end containerTargets

        private def keyboardKey(context: SessionContext, event: dom.Event): Unit =
            val orientation = context.target.fold(Drag.Orientation.Vertical)(_.config.wire.orientation)
            val forwardKeys: Set[String] = orientation match
                case Drag.Orientation.Horizontal => Set("ArrowRight")
                case Drag.Orientation.Vertical   => Set("ArrowDown")
                case Drag.Orientation.Both       => Set("ArrowDown", "ArrowRight")
            val backwardKeys: Set[String] = orientation match
                case Drag.Orientation.Horizontal => Set("ArrowLeft")
                case Drag.Orientation.Vertical   => Set("ArrowUp")
                case Drag.Orientation.Both       => Set("ArrowUp", "ArrowLeft")
            keyOf(event) match
                case key if forwardKeys.contains(key) =>
                    event.preventDefault()
                    moveKeyboardSlot(context, _ + 1)
                case key if backwardKeys.contains(key) =>
                    event.preventDefault()
                    moveKeyboardSlot(context, _ - 1)
                case "Home" =>
                    event.preventDefault()
                    moveKeyboardSlot(context, _ => 0)
                case "End" =>
                    event.preventDefault()
                    moveKeyboardSlot(context, _ => Int.MaxValue)
                case "Tab" =>
                    event.preventDefault()
                    keyboardTab(context, backward = shiftKey(event))
                case "Enter" | " " =>
                    event.preventDefault()
                    if context.target.nonEmpty then sensorDrop(context)
                    else finish(context, cancelled = true)
                case "Escape" =>
                    event.preventDefault()
                    announce(context, s"${sourceLabel(context)} move cancelled.")
                    finish(context, cancelled = true)
                case _ => ()
            end match
        end keyboardKey

        private def moveKeyboardSlot(context: SessionContext, update: Int => Int): Unit =
            context.target.foreach { target =>
                val items   = keyboardItems(context, target)
                val maximum = items.size
                val next    = math.max(0, math.min(maximum, update(context.slotIndex)))
                context.slotIndex = next
                applyKeyboardSlot(context, target, items)
            }

        private def applyKeyboardSlot(
            context: SessionContext,
            target: Located[DragProtocol.ValidatedTargetConfig],
            items: Chunk[Located[DragProtocol.ValidatedSourceConfig]]
        ): Unit =
            context.anchorItem.foreach(_.element.removeAttribute(dropPositionAttribute))
            context.anchorItem = Absent
            if items.isEmpty then
                context.anchorAfter = true
                announce(context, s"Into ${target.config.wire.label.getOrElse(target.config.wire.key)}.")
            else
                val (item, after) =
                    if context.slotIndex <= 0 then (items(0), false)
                    else (items(math.min(context.slotIndex, items.size) - 1), true)
                context.anchorItem = Present(item)
                context.anchorAfter = after
                item.element.setAttribute(dropPositionAttribute, if after then "after" else "before")
                announce(context, s"${if after then "After" else "Before"} ${itemLabel(item)}.")
            end if
        end applyKeyboardSlot

        private def keyboardTab(context: SessionContext, backward: Boolean): Unit =
            val targets = containerTargets()
            if targets.nonEmpty then
                val currentIndex = context.target.fold(-1)(current => targets.indexWhere(_.element eq current.element))
                val nextIndex =
                    if currentIndex < 0 then 0
                    else if backward then (currentIndex - 1 + targets.size) % targets.size
                    else (currentIndex + 1)                                 % targets.size
                val next = targets(nextIndex)
                context.target.foreach(_.element.removeAttribute(dropValidAttribute))
                clearDropPresentation(context)
                context.target = Present(next)
                next.element.setAttribute(dropValidAttribute, "true")
                val items = keyboardItems(context, next)
                context.slotIndex = items.size
                applyKeyboardSlot(context, next, items)
            end if
        end keyboardTab

        private def selectionSnapshot(source: Located[DragProtocol.ValidatedSourceConfig]): Chunk[String] =
            val scope = enclosingTarget(source.element).map(_.element).getOrElse(container)
            val nodes = scope.querySelectorAll(s"[$sourceKeyAttribute][$selectedAttribute='true']")
            val keys  = ChunkBuilder.init[String]
            var index = 0
            var found = false
            while index < nodes.length do
                val element = nodes(index)
                if element != null then
                    decodeSource(element).foreach { item =>
                        keys.addOne(item.config.wire.key)
                        if item.config.wire.key == source.config.wire.key then found = true
                    }
                end if
                index += 1
            end while
            val selected = keys.result()
            if found && selected.nonEmpty then selected else Chunk(source.config.wire.key)
        end selectionSnapshot

        private def enclosingTarget(from: dom.Element): Maybe[Located[DragProtocol.ValidatedTargetConfig]] =
            var current = parent(from)
            while current != null && (container.contains(current) || (current eq container)) do
                decodeTarget(current) match
                    case value @ Present(_) => return value
                    case Absent             => current = parent(current)
            end while
            Absent
        end enclosingTarget

        private def sensorDrop(context: SessionContext): Unit =
            context.target.foreach { target =>
                val sortable = Drag.acceptsSortablePayload(target.config.accept)
                if sortable then
                    val movement = Drag.Move(
                        context.selectionKeys,
                        Drag.Location(context.sourceCollection.getOrElse(target.config.wire.key)),
                        Drag.Location(target.config.wire.key),
                        context.anchorItem.map(_.config.wire.key),
                        if context.anchorItem.isEmpty || context.anchorAfter then Drag.Position.After else Drag.Position.Before,
                        Drag.Operation.Move
                    )
                    move(State.AwaitingDecision(context))
                    enqueue(UIEvent.SortMove(target.path, context.id, movement))
                else
                    move(State.AwaitingDecision(context))
                    enqueue(UIEvent.Drop(
                        target.path,
                        DragProtocol.TargetData(
                            context.id,
                            context.operation,
                            Present(target.config.wire.key),
                            context.lastPoint,
                            UI.Modifiers.none,
                            Present(Drag.Position.Inside)
                        )
                    ))
                end if
            }
        end sensorDrop

        private def pointerId(event: dom.Event): Double =
            number(event.asInstanceOf[js.Dynamic].pointerId)

        private def keyOf(event: dom.Event): String =
            val value = event.asInstanceOf[js.Dynamic].key
            if js.isUndefined(value) || value == null then "" else value.asInstanceOf[String]

        private def shiftKey(event: dom.Event): Boolean =
            bool(event.asInstanceOf[js.Dynamic].shiftKey)

        private def distance(x1: Double, y1: Double, x2: Double, y2: Double): Double =
            math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))

        // --- Native HTML5 sessions ---

        private def start(event: dom.Event): Unit =
            asElement(event.target).flatMap(closestSource).foreach { source =>
                val config = source.config.wire
                val nativeActivation =
                    config.activation == Drag.Activation.Native || config.activation == Drag.Activation.Both
                if nativeActivation &&
                    (!config.handle || asElement(event.target).flatMap(closestAttribute(
                        _,
                        "data-kyo-drag-handle",
                        source.element
                    )).nonEmpty)
                then
                    dataTransfer(event).foreach { transfer =>
                        transfer.updateDynamic("effectAllowed")(effectAllowed(config.operations))
                        preferred(config.operations, Absent, modifiers(event)).foreach { operation =>
                            val id = token("drag")
                            setInternalData(transfer, config)
                            val preview = createPreview(source.element, config.preview, transfer)
                            val context = SessionContext(
                                id,
                                Present(source),
                                config.operations,
                                mutable.Map.empty,
                                preview,
                                timing.nowMillis(),
                                operation,
                                Absent,
                                Present(config.items),
                                Present(source.config.items)
                            )
                            move(State.Dragging(context))
                            emitStart(context, source.path, event)
                        }
                    }
                end if
            }
        end start

        private def probeTarget(event: dom.Event, make: (Seq[String], DragProtocol.TargetData) => UIEvent): Unit =
            newProbe(event).foreach { case State.ExternalProbing(context, probe) =>
                move(State.ExternalProbing(context, probe))
                targetEvent(event, context, Present(probe), make)
                if context.target.isEmpty then discardContext(context)
            }

        private def targetEvent(
            event: dom.Event,
            context: SessionContext,
            probe: Maybe[Probe],
            make: (Seq[String], DragProtocol.TargetData) => UIEvent
        ): Unit =
            asElement(event.target).flatMap(acceptedTarget(_, context, probe, modifiers(event))).foreach { matched =>
                event.preventDefault()
                setDropEffect(event, matched.operation)
                context.operation = matched.operation
                context.target = Present(matched.target)
                if probe.isEmpty then enqueue(make(matched.target.path, targetData(context, matched.target, event, matched.operation)))
            }
        end targetEvent

        private def leave(event: dom.Event, context: SessionContext, emit: Boolean): Unit =
            context.target.foreach { target =>
                val related = asElement(event.asInstanceOf[js.Dynamic].relatedTarget.asInstanceOf[Any])
                val remains = related.exists(target.element.contains)
                if !remains then
                    if emit then enqueue(UIEvent.DragLeave(target.path, targetData(context, target, event, context.operation)))
                    context.target = Absent
                end if
            }
        end leave

        private def probeDrop(event: dom.Event): Unit =
            newProbe(event).foreach { case (external @ State.ExternalProbing(context, _)) =>
                move(external)
                externalDrop(event, context)
            }

        private def externalDrop(event: dom.Event, context: SessionContext): Unit =
            dataTransfer(event).flatMap(snapshot).fold(discardContext(context)) { snapshot =>
                context.wireItems = Present(snapshot.wireItems)
                context.domainItems = Present(snapshot.domainItems)
                context.allowed = snapshot.allowed
                context.tokens.addAll(snapshot.tokens)
                asElement(event.target).flatMap(acceptedTarget(_, context, Absent, modifiers(event))).fold(discardContext(context)) {
                    matched =>
                        event.preventDefault()
                        setDropEffect(event, matched.operation)
                        context.operation = matched.operation
                        context.target = Present(matched.target)
                        move(State.AwaitingDecisionAfterEnd(context))
                        emitStart(context, Seq.empty, event)
                        if state.maybeContext.nonEmpty then
                            enqueue(UIEvent.Drop(matched.target.path, targetData(context, matched.target, event, matched.operation)))
                }
            }
        end externalDrop

        private def internalDrop(event: dom.Event, context: SessionContext): Unit =
            asElement(event.target).flatMap(acceptedTarget(_, context, Absent, modifiers(event))).foreach { matched =>
                event.preventDefault()
                setDropEffect(event, matched.operation)
                context.operation = matched.operation
                context.target = Present(matched.target)
                move(State.AwaitingDecision(context))
                enqueue(UIEvent.Drop(matched.target.path, targetData(context, matched.target, event, matched.operation)))
            }
        end internalDrop

        private def finish(context: SessionContext, cancelled: Boolean): Unit =
            if state.maybeContext.exists(_ eq context) then
                cleanupSensor(context)
                context.preview.foreach(_.remove())
                context.preview = Absent
                context.tokens.clear()
                if context.wireItems.nonEmpty then
                    val path = context.source.map(_.path).getOrElse(Seq.empty)
                    enqueue(UIEvent.DragEnd(path, DragProtocol.EndData(context.id, context.operation, cancelled)))
                move(State.Idle)
            end if
        end finish

        /** Removes every sensor resource and presentation attribute; safe on native sessions and idempotent. */
        private def cleanupSensor(context: SessionContext): Unit =
            context.frame.foreach(_.cancel())
            context.frame = Absent
            context.liveRegion.foreach(_.remove())
            context.liveRegion = Absent
            context.source.foreach { source =>
                source.element.removeAttribute(draggingAttribute)
                context.pointerId.foreach { id =>
                    val dyn = source.element.asInstanceOf[js.Dynamic]
                    if js.typeOf(dyn.releasePointerCapture) == "function" then
                        try discard(dyn.releasePointerCapture(id))
                        catch case _: Throwable => ()
                }
            }
            clearDropPresentation(context)
            if context.sensor && context.keyboard then
                context.source.foreach { source =>
                    val key      = source.config.wire.key
                    val restored = container.querySelector(s"[$sourceKeyAttribute='$key']")
                    if restored != null then
                        val dyn = restored.asInstanceOf[js.Dynamic]
                        if js.typeOf(dyn.focus) == "function" then discard(dyn.focus())
                }
            end if
        end cleanupSensor

        private def clearDropPresentation(context: SessionContext): Unit =
            context.target.foreach(_.element.removeAttribute(dropValidAttribute))
            context.anchorItem.foreach(_.element.removeAttribute(dropPositionAttribute))
            context.anchorItem = Absent
        end clearDropPresentation

        private def expire(): Unit =
            state.maybeContext.foreach { context =>
                val sourceGone = context.sensor && context.source.exists(source => !isConnected(source.element))
                if sourceGone || timing.nowMillis() - context.startedAt >= pendingTimeoutMs then finish(context, cancelled = true)
            }

        private def isConnected(element: dom.Element): Boolean =
            val value = element.asInstanceOf[js.Dynamic].isConnected
            js.isUndefined(value) || value == null || value.asInstanceOf[Boolean]

        private def resolveEvent(event: dom.Event): Unit =
            val detail = event.asInstanceOf[js.Dynamic].detail
            if js.typeOf(detail) == "string" then
                Json.decode[HtmlOp](detail.asInstanceOf[String]) match
                    case Result.Success(HtmlOp.ResolveDrag(sessionId, decision)) => resolve(sessionId, decision)
                    case _                                                       => ()
            end if
        end resolveEvent

        private def newProbe(event: dom.Event): Maybe[State.ExternalProbing] =
            dataTransfer(event).flatMap(probe).map { result =>
                val operation = preferred(result.allowed, Absent, modifiers(event)).getOrElse(Drag.Operation.Move)
                val context = SessionContext(
                    token("drag"),
                    Absent,
                    result.allowed,
                    mutable.Map.empty,
                    Absent,
                    timing.nowMillis(),
                    operation,
                    Absent,
                    Absent,
                    Absent
                )
                State.ExternalProbing(context, result.manifest)
            }

        private def emitStart(context: SessionContext, path: Seq[String], event: dom.Event): Unit =
            emitStart(context, path, point(event), modifiers(event))

        private def emitStart(context: SessionContext, path: Seq[String], at: Drag.Point, mods: UI.Modifiers): Unit =
            val sourceKey = context.source.map(_.config.wire.key)
            val start: UIEvent.DragStart = UIEvent.DragStart(
                path,
                DragProtocol.StartData(
                    context.id,
                    context.wireItems.getOrElse(Chunk.empty),
                    context.operation,
                    sourceKey,
                    at,
                    mods
                )
            )
            context.domainItems.fold(discardContext(context)) { items =>
                DragProtocol.validatedStart(start, items, limits) match
                    case Result.Success(validated) => enqueue(validated.wire)
                    case _                         => discardContext(context)
            }
        end emitStart

        private def discardContext(context: SessionContext): Unit =
            if state.maybeContext.exists(_ eq context) then
                cleanupSensor(context)
                context.preview.foreach(_.remove())
                context.preview = Absent
                context.tokens.clear()
                move(State.Idle)

        private def move(next: State): Unit =
            state = next
            transitions += 1

        private def acceptedTarget(
            from: dom.Element,
            context: SessionContext,
            probe: Maybe[Probe],
            mods: UI.Modifiers
        ): Maybe[TargetMatch] =
            var current = from
            while current != null && container.contains(current) do
                decodeTarget(current).foreach { target =>
                    val operation = preferred(context.allowed, Present(target.config.accept.operations), mods)
                    operation.foreach { selected =>
                        val accepted = probe.fold(context.domainItems.exists(accepts(target.config.accept, _)))(
                            acceptsProbe(target.config.accept, _)
                        )
                        if accepted then return Present(TargetMatch(target, selected))
                    }
                }
                current = parent(current)
            end while
            Absent
        end acceptedTarget

        private def accepts(accept: Drag.Accept, items: Chunk[Drag.Item]): Boolean =
            accept.maxItems.forall(items.size <= _) && items.forall(accept.accepts)
        end accepts

        private def acceptsProbe(accept: Drag.Accept, probe: Probe): Boolean =
            accept.maxItems.forall(probe.itemCount <= _) &&
                (probe.textTypes.isEmpty || accept.accepts(Drag.Item.Text(probe.textTypes.iterator.map(_ -> "").toMap))) &&
                (!probe.uri || accept.accepts(Drag.Item.Uri("probe"))) &&
                probe.files.forall { file =>
                    file.directory match
                        case Present(true) => accept.directories
                        case Present(false) =>
                            file.mediaType.fold(true)(media => acceptsProbeFile(accept, media))
                        case Absent =>
                            accept.directories || file.mediaType.fold(true)(media => acceptsProbeFile(accept, media))
                }
        end acceptsProbe

        private def acceptsProbeFile(accept: Drag.Accept, mediaType: Drag.MediaType): Boolean =
            accept.copy(maxFileSize = Absent).accepts(
                Drag.Item.File(Drag.FileMeta("probe", "probe", mediaType, ByteSize.Zero, Instant.Epoch))
            )

        private def targetData(
            context: SessionContext,
            target: Located[DragProtocol.ValidatedTargetConfig],
            event: dom.Event,
            operation: Drag.Operation
        ): DragProtocol.TargetData =
            DragProtocol.TargetData(
                context.id,
                operation,
                Present(target.config.wire.key),
                point(event),
                modifiers(event),
                Present(Drag.Position.Inside)
            )

        private def closestSource(from: dom.Element): Maybe[Located[DragProtocol.ValidatedSourceConfig]] =
            var current = from
            while current != null && container.contains(current) do
                decodeSource(current) match
                    case value @ Present(_) => return value
                    case Absent             => current = parent(current)
            end while
            Absent
        end closestSource

        private def decodeSource(element: dom.Element): Maybe[Located[DragProtocol.ValidatedSourceConfig]] =
            validPath(element.getAttribute("data-kyo-path")).flatMap { path =>
                cachedSource(element).flatMap { config =>
                    val dedicated = element.getAttribute(sourceKeyAttribute)
                    if dedicated != null && dedicated == config.wire.key then Present(Located(element, path, config)) else Absent
                }
            }

        private def cachedSource(element: dom.Element): Maybe[DragProtocol.ValidatedSourceConfig] =
            val raw = element.getAttribute(sourceAttribute)
            if raw == null then Absent
            else
                sourceCache match
                    case Present(cached) if (cached.element eq element) && cached.raw == raw => cached.value
                    case _ =>
                        val value =
                            if raw.length > limits.maxAttributeLength then Absent
                            else
                                sourceConversions += 1
                                Json.decode[DragProtocol.SourceConfig](raw) match
                                    case Result.Success(config) => DragProtocol.validateSourceConfigAndDomain(config, raw, limits).toMaybe
                                    case _                      => Absent
                        sourceCache = Present(ConfigCache(element, raw, value))
                        value
            end if
        end cachedSource

        private def decodeTarget(element: dom.Element): Maybe[Located[DragProtocol.ValidatedTargetConfig]] =
            validPath(element.getAttribute("data-kyo-path")).flatMap { path =>
                cachedTarget(element).flatMap { config =>
                    val dedicated = element.getAttribute(targetKeyAttribute)
                    if dedicated != null && dedicated == config.wire.key then Present(Located(element, path, config)) else Absent
                }
            }

        private def cachedTarget(element: dom.Element): Maybe[DragProtocol.ValidatedTargetConfig] =
            val raw = element.getAttribute(targetAttribute)
            if raw == null then Absent
            else
                targetCache match
                    case Present(cached) if (cached.element eq element) && cached.raw == raw => cached.value
                    case _ =>
                        val value =
                            if raw.length > limits.maxAttributeLength then Absent
                            else
                                targetConversions += 1
                                Json.decode[DragProtocol.TargetConfig](raw) match
                                    case Result.Success(config) => DragProtocol.validateTargetConfigAndDomain(config, raw, limits).toMaybe
                                    case _                      => Absent
                        targetCache = Present(ConfigCache(element, raw, value))
                        value
            end if
        end cachedTarget

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

        private def probe(transfer: js.Dynamic): Maybe[ProbeResult] =
            val textTypes = mutable.Set.empty[Drag.MediaType]
            var uri       = false
            var valid     = true
            val files     = ChunkBuilder.init[ProbeFile]
            val rawTypes  = transfer.types
            if !js.isUndefined(rawTypes) && rawTypes != null then
                val types     = rawTypes.asInstanceOf[js.Array[String]]
                val seenTypes = mutable.Set.empty[Drag.MediaType]
                var index     = 0
                while index < types.length do
                    val raw = types(index)
                    if !raw.equalsIgnoreCase("Files") then
                        Drag.MediaType.parse(raw) match
                            case Present(mediaType) if seenTypes.contains(mediaType) => valid = false
                            case Present(mediaType) if mediaType.render == "text/uri-list" =>
                                seenTypes += mediaType
                                uri = true
                            case Present(mediaType) =>
                                seenTypes += mediaType
                                textTypes += mediaType
                            case Absent => valid = false
                    end if
                    index += 1
                end while
            end if
            val rawItems = transfer.items
            if !js.isUndefined(rawItems) && rawItems != null then
                val items     = rawItems.asInstanceOf[js.Array[js.Dynamic]]
                val seenTypes = mutable.Set.empty[Drag.MediaType]
                var index     = 0
                while index < items.length do
                    val item = items(index)
                    val kind = if js.isUndefined(item.kind) then "" else item.kind.asInstanceOf[String]
                    val rawMediaType =
                        if js.isUndefined(item.`type`) || item.`type` == null then ""
                        else item.`type`.asInstanceOf[String]
                    val mediaType = if rawMediaType.isEmpty then Absent else Drag.MediaType.parse(rawMediaType)
                    if kind == "string" then
                        mediaType match
                            case Present(media) if seenTypes.contains(media) => valid = false
                            case Present(media) if media.render == "text/uri-list" =>
                                seenTypes += media
                                uri = true
                            case Present(media) =>
                                seenTypes += media
                                textTypes += media
                            case Absent => valid = false
                    else if kind == "file" then
                        if rawMediaType.nonEmpty && mediaType.isEmpty then valid = false
                        files.addOne(ProbeFile(mediaType, probeDirectory(item)))
                    end if
                    index += 1
                end while
            end if
            val manifest = Probe(textTypes.toSet, uri, files.result())
            val allowed = allowedFromBrowser(
                if js.isUndefined(transfer.effectAllowed) then "all" else transfer.effectAllowed.asInstanceOf[String]
            )
            if valid && manifest.itemCount > 0 && manifest.itemCount <= limits.maxItemCount && allowed.values.nonEmpty then
                Present(ProbeResult(manifest, allowed))
            else Absent
        end probe

        private def probeDirectory(item: js.Dynamic): Maybe[Boolean] =
            if js.typeOf(item.webkitGetAsEntry) == "function" then
                try
                    val entry = item.webkitGetAsEntry()
                    if entry == null || js.isUndefined(entry) then Absent
                    else Present(entry.isDirectory.asInstanceOf[Boolean])
                catch case _: Throwable => Absent
            else Absent

        private def snapshot(transfer: js.Dynamic): Maybe[Snapshot] =
            val collected = ChunkBuilder.init[DragProtocol.ItemData]
            val tokens    = mutable.Map.empty[String, js.Any]
            val text      = mutable.Map.empty[Drag.MediaType, String]
            var valid     = true
            val rawTypes  = transfer.types
            if !js.isUndefined(rawTypes) && rawTypes != null then
                val types     = rawTypes.asInstanceOf[js.Array[String]]
                val seenTypes = mutable.Set.empty[Drag.MediaType]
                var index     = 0
                while index < types.length do
                    val raw = types(index)
                    if !raw.equalsIgnoreCase("Files") then
                        Drag.MediaType.parse(raw) match
                            case Present(mediaType) =>
                                if !seenTypes.add(mediaType) then valid = false
                                else if mediaType.render == "text/uri-list" then
                                    val value = transfer.getData(raw).asInstanceOf[String]
                                    if value.nonEmpty then collected.addOne(DragProtocol.ItemData.Uri(value))
                                else if text.size < limits.maxTextRepresentationCount then
                                    text(mediaType) = transfer.getData(raw).asInstanceOf[String]
                                else valid = false
                            case Absent => valid = false
                    end if
                    index += 1
                end while
            end if
            if text.nonEmpty then
                collected.addOne(DragProtocol.ItemData.Text(text.iterator.map((media, value) => media.render -> value).toMap))

            val itemList = defined(transfer.items).map(_.asInstanceOf[js.Array[js.Dynamic]])
            if itemList.exists(_.nonEmpty) then
                val items = itemList.getOrElse(js.Array())
                var index = 0
                while index < items.length do
                    val item = items(index)
                    val kind = if js.isUndefined(item.kind) then "" else item.kind.asInstanceOf[String]
                    if kind == "file" then
                        directory(item) match
                            case Present(entry) =>
                                val name = safeName(entry.name, "directory")
                                val id   = token("directory")
                                tokens(id) = entry
                                collected.addOne(DragProtocol.ItemData.Directory(id, name))
                            case Absent =>
                                val file =
                                    if js.typeOf(item.getAsFile) == "function" then defined(item.getAsFile())
                                    else Absent
                                file match
                                    case Absent => valid = false
                                    case Present(file) =>
                                        fileMeta(file) match
                                            case Present(metadata) =>
                                                tokens(metadata.token) = file
                                                collected.addOne(DragProtocol.ItemData.File(metadata))
                                            case Absent => valid = false
                                end match
                    end if
                    index += 1
                end while
            else
                defined(transfer.files).map(_.asInstanceOf[js.Array[js.Dynamic]]).foreach { files =>
                    var index = 0
                    while index < files.length do
                        val file = files(index)
                        fileMeta(file) match
                            case Present(metadata) =>
                                tokens(metadata.token) = file
                                collected.addOne(DragProtocol.ItemData.File(metadata))
                            case Absent => valid = false
                        end match
                        index += 1
                    end while
                }
            end if

            val result = collected.result()
            val allowed = allowedFromBrowser(
                if js.isUndefined(transfer.effectAllowed) then "all" else transfer.effectAllowed.asInstanceOf[String]
            )
            if !valid then Absent
            else
                DragProtocol.StartData(
                    "validation",
                    result,
                    Drag.Operation.Move,
                    Absent,
                    Drag.Point(0, 0),
                    UI.Modifiers.none
                ).domainItems(limits) match
                    case Result.Success(domainItems) if result.nonEmpty && allowed.values.nonEmpty =>
                        Present(Snapshot(result, domainItems, allowed, tokens))
                    case _ => Absent
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
            val size         = file.size.asInstanceOf[Double]
            val lastModified = file.lastModified.asInstanceOf[Double]
            if !size.isFinite || size < 0 || size > 9_007_199_254_740_991d || size != math.floor(size) ||
                !lastModified.isFinite || math.abs(lastModified) > 9_007_199_254_740_991d || lastModified != math.floor(lastModified)
            then Absent
            else
                val name = safeName(file.name, "file")
                val rawMedia =
                    val value = if js.isUndefined(file.`type`) then "" else file.`type`.asInstanceOf[String]
                    if value.nonEmpty then value else "application/octet-stream"
                DragProtocol.WireByteSize.from(ByteSize.fromBytes(size.toLong)).toMaybe.flatMap { wireSize =>
                    Drag.MediaType.parse(rawMedia).map { media =>
                        val id = token("file")
                        DragProtocol.FileMetaData(
                            id,
                            name,
                            media.render,
                            wireSize,
                            Instant.fromJava(java.time.Instant.ofEpochMilli(lastModified.toLong))
                        )
                    }
                }
            end if
        end fileMeta

        private def safeName(primary: js.Dynamic, fallback: String): String =
            val first    = if primary == null || js.isUndefined(primary) then "" else primary.asInstanceOf[String]
            val selected = if first.nonEmpty then first else fallback
            if selected.nonEmpty && selected.length <= limits.maxNameLength then selected else "unnamed"
        end safeName

        private def preferred(
            source: Drag.AllowedOperations,
            target: Maybe[Drag.AllowedOperations],
            mods: UI.Modifiers
        ): Maybe[Drag.Operation] =
            val common = target.fold(source.values)(value => source.values.intersect(value.values))
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
            dataTransfer(event).foreach { transfer =>
                transfer.updateDynamic("dropEffect")(operation.toString.toLowerCase)
            }

        private def dataTransfer(event: dom.Event): Maybe[js.Dynamic] =
            defined(event.asInstanceOf[js.Dynamic].dataTransfer)

        private def defined(value: js.Dynamic): Maybe[js.Dynamic] =
            if js.isUndefined(value) || value == null then Absent else Present(value)

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

        private def asElement(value: Any): Maybe[dom.Element] = value match
            case element: dom.Element => Present(element)
            case _                    => Absent

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
