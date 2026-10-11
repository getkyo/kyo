package kyo.internal

import EventDispatch.*
import MountRegions.*
import kyo.*
import kyo.Svg
import kyo.UI.*
import kyo.kernel.ContextEffect

/** Normalized reactive UI node. */
private[kyo] case class ReactiveUI(
    path: Seq[String],
    signal: Signal[UI],
    isConst: Boolean,
    children: Seq[ReactiveUI],
    handle: (Seq[String], UIEvent) => Boolean < Async,
    region: ReactiveRegion,
    contentContext: ReactiveRegion.RegionIdentity,
    parentContext: ReactiveRegion.ParentContext,
    discoverContentRootBound: Boolean,
    mounted: Maybe[MountedSpec] = Absent,
    mountDispatch: Maybe[MountDispatch] = Absent,
    // The call site of the node a region comes from and the row-key depths of its path since the enclosing region (see `rowKeys`):
    // what places a region's mount table, independently of the region's position among its siblings.
    origin: Maybe[Frame] = Absent,
    rows: Long = 0L,
    deepRows: Chunk[Int] = Chunk.empty
)

private[kyo] object ReactiveUI:

    import UI.Ast.*

    def init(
        path: Seq[String],
        signal: Signal[UI],
        isConst: Boolean,
        children: Seq[ReactiveUI],
        svgContext: Boolean,
        regionIdentity: ReactiveRegion.RegionIdentity,
        contentContext: ReactiveRegion.RegionIdentity,
        parentContext: ReactiveRegion.ParentContext,
        origin: Maybe[Frame] = Absent,
        rows: Long = 0L,
        deepRows: Chunk[Int] = Chunk.empty
    )(
        handle: (Seq[String], UIEvent) => Boolean < Async
    ): ReactiveUI =
        ReactiveUI(
            path,
            signal,
            isConst,
            children,
            handle,
            ReactiveRegion.from(regionIdentity, svgContext),
            contentContext,
            parentContext,
            discoverContentRootBound = true,
            origin = origin,
            rows = rows,
            deepRows = deepRows
        )

    /** The root of a mount session: creates the session's [[MountDispatch]] and records it on the returned node for `subscribe`. */
    def normalize(ui: UI, path: Seq[String], svg: Boolean = false): ReactiveUI < Sync =
        given Frame = ui.frame
        for
            dispatch <- MountDispatch.init
            root     <- normalizeAt(
                ui,
                path,
                svg,
                ReactiveRegion.RegionIdentity.root(path),
                ReactiveRegion.ParentContext.Other,
                dispatch,
                rows = 0L,
                deepRows = Chunk.empty
            )
        yield root.copy(mountDispatch = Present(dispatch))
        end for
    end normalize

    /* Normalization and the tree walk carry, for the mounted nodes and regions they meet, the session's dispatch table and which
     * depths of the path, since the enclosing reactive region, hold the key of a keyed list row: a bit per depth below 63 in `rows`,
     * and the rare deeper ones in `deepRows`. Both are read out of the path only when a mount or a region's table needs them, so the
     * walk itself allocates nothing for them.
     */

    /** The row keys `rows` and `deepRows` mark in `path`, innermost first. */
    private[internal] def rowKeys(path: Seq[String], rows: Long, deepRows: Chunk[Int]): Chunk[String] =
        if rows == 0L && deepRows.isEmpty then Chunk.empty
        else
            path.iterator.zipWithIndex.foldLeft(Chunk.empty[String]) { case (acc, (segment, depth)) =>
                val isRow = if depth < 63 then (rows & (1L << depth)) != 0L else deepRows.contains(depth)
                if isRow then acc.append(segment) else acc
            }.reverse

    private def normalizeAt(
        ui: UI,
        path: Seq[String],
        svg: Boolean,
        regionIdentity: ReactiveRegion.RegionIdentity,
        parentContext: ReactiveRegion.ParentContext,
        cells: MountDispatch,
        rows: Long,
        deepRows: Chunk[Int]
    ): ReactiveUI < Sync =
        given Frame = ui.frame
        ui match
            case ui: Reactive[?] =>
                val contentContext = regionIdentity.transparent
                for
                    current <- ui.signal.current
                    contentParentContext = nestedParentContext(parentContext, current)
                    (kids, _) <-
                        walkStatic(current, path, svg, contentContext, contentParentContext, cells, rows = 0L, deepRows = Chunk.empty)
                yield init(
                    path,
                    ui.signal,
                    isConst = false,
                    kids,
                    svg,
                    regionIdentity,
                    contentContext,
                    parentContext,
                    Present(ui.frame),
                    rows,
                    deepRows
                ) {
                    (targetPath, event) =>
                        for
                            currentUI <- ui.signal.current
                            contentParentContext = nestedParentContext(parentContext, currentUI)
                            (_, freshHdl) <-
                                walkStatic(
                                    currentUI,
                                    path,
                                    svg,
                                    contentContext,
                                    contentParentContext,
                                    cells,
                                    rows = 0L,
                                    deepRows = Chunk.empty
                                )
                            result <- freshHdl(targetPath, event)
                        yield result
                }
                end for

            case ui: Foreach[?, ?] @unchecked =>
                val contentContext = regionIdentity
                val sig            =
                    ui.signal.map { items =>
                        val arr = items.toSeq.zipWithIndex.map { (item, i) =>
                            val key = if ui.key.nonEmpty then ui.key.get(item) else i.toString
                            KeyedChild[UI](key, ui.render(i, item))
                        }
                        Fragment[UI](Chunk.from(arr)): UI
                    }
                for
                    current <- sig.current
                    contentParentContext = nestedParentContext(parentContext, current)
                    (kids, _) <-
                        walkStatic(current, path, svg, contentContext, contentParentContext, cells, rows = 0L, deepRows = Chunk.empty)
                yield init(
                    path,
                    sig,
                    isConst = false,
                    kids,
                    svg,
                    regionIdentity,
                    contentContext,
                    parentContext,
                    Present(ui.frame),
                    rows,
                    deepRows
                ) {
                    (targetPath, event) =>
                        for
                            currentUI <- sig.current
                            contentParentContext = nestedParentContext(parentContext, currentUI)
                            (_, freshHdl) <-
                                walkStatic(
                                    currentUI,
                                    path,
                                    svg,
                                    contentContext,
                                    contentParentContext,
                                    cells,
                                    rows = 0L,
                                    deepRows = Chunk.empty
                                )
                            result <- freshHdl(targetPath, event)
                        yield result
                }
                end for

            case ui: Element =>
                // An element with a SignalRef-bound attribute (`.value(ref)` xor `.checked(ref)`; an element binds at most
                // one, see collectSignalRef) must re-render when that signal changes. The change is carried by the ref
                // (read afresh at render time), not by the rendered value's identity: the value is always the same `ui`
                // object, kept with its `Bound.Ref` attributes so the rendered HTML carries the auto-binding event markers
                // `data-kyo-ev="input"/"change"` the client needs and the dispatch handler resolves the ref. The region's
                // signal is that ref mapped to the constant `ui`; mapping the leaf `SignalRef` keeps the signal exact, so
                // subscribeScoped's `observe` delegates to the ref's register-before-read leaf loop (lossless, no
                // deferred next-capture, no repair timer / idle churn), and each ref edit is a distinct ref VALUE that
                // re-renders without the value-dedup ever suppressing a real edit. An element with no bound ref is const.
                val (elementSignal, isConstNode) =
                    collectSignalRef(ui).fold((Signal.initConst(ui: UI), true))(ref => (ref.map(_ => ui: UI), false))
                // A constant element belongs to the enclosing region; a bound one is a region of its own.
                val contentRows     = if isConstNode then rows else 0L
                val contentDeepRows = if isConstNode then deepRows else Chunk.empty
                for
                    (kids, hdl) <- walkStatic(
                        ui,
                        path,
                        svg,
                        regionIdentity,
                        parentContext,
                        cells,
                        contentRows,
                        contentDeepRows,
                        discoverRootBound = false
                    )
                yield ReactiveUI(
                    path,
                    elementSignal,
                    isConst = isConstNode,
                    kids,
                    hdl,
                    ReactiveRegion.from(regionIdentity, svg),
                    regionIdentity,
                    parentContext,
                    discoverContentRootBound = false,
                    origin = Present(ui.frame),
                    rows = rows,
                    deepRows = deepRows
                )

            case ui: Mounted =>
                val spec           = MountedSpec(ui, path, rowKeys(path, rows, deepRows))
                val contentContext = regionIdentity.transparent
                // The node value is fresh on every dispatch, so the handler finds the live instance through the dispatch table.
                val handle: Handler = (targetPath, event) =>
                    cells.lookup(path).map {
                        case Absent        => true
                        case Present(cell) =>
                            cell.current.map {
                                case MountState.Pending(Present(_)) => true
                                case state                          =>
                                    val content = state.content(spec)
                                    for
                                        (_, freshHdl) <- walkStatic(
                                            content,
                                            path,
                                            svg,
                                            contentContext,
                                            nestedParentContext(parentContext, content),
                                            cells,
                                            rows = 0L,
                                            deepRows = Chunk.empty
                                        )
                                        result <- freshHdl(targetPath, event)
                                    yield result
                                    end for
                            }
                    }
                ReactiveUI(
                    path = path,
                    signal = Signal.initConst(spec.placeholder),
                    isConst = false,
                    children = Seq.empty,
                    handle = handle,
                    region = ReactiveRegion.from(regionIdentity, svg),
                    contentContext = contentContext,
                    parentContext = parentContext,
                    discoverContentRootBound = true,
                    mounted = Present(spec)
                )

            case ui =>
                // Catch-all for static leaf nodes: Text, Fragment, KeyedChild.
                // All interactive types extend Element (handled above) and all reactive types are
                // Reactive, Foreach or Mounted (also handled above). If a new UI subtype is added, the
                // exhaustiveness checker will NOT warn here; any new type that is interactive must
                // be added as an explicit case above before this catch-all.
                ReactiveUI(
                    path,
                    Signal.initConst(ui),
                    isConst = true,
                    Seq.empty,
                    (_, _) => true,
                    ReactiveRegion.from(regionIdentity, svg),
                    regionIdentity,
                    parentContext,
                    discoverContentRootBound = false
                )
        end match
    end normalizeAt

    /** Returns the element's single SignalRef-bound attribute (its `.value` or `.checked`), if any, so the element can be made reactive over
      * it (its HTML re-renders when that signal changes). An element binds at most one such ref.
      */
    private[kyo] def collectSignalRef(elem: Element): Maybe[Signal[?]] =
        def refOf(v: Maybe[Bound[?]]): Maybe[Signal[?]] = v match
            case Present(Bound.Ref(ref)) => Present(ref)
            case _                       => Absent
        elem match
            case ti: TextInput    => refOf(ti.value)
            case ri: RangeInput   => refOf(ri.value)
            case pi: PickerInput  => refOf(pi.value)
            case bi: BooleanInput => refOf(bi.checked)
            case h: HiddenInput   => refOf(h.value)
            case _                => Absent
        end match
    end collectSignalRef

    private[internal] def nestedParentContext(parentContext: ReactiveRegion.ParentContext, current: UI): ReactiveRegion.ParentContext =
        (parentContext, ReactiveRegion.tableContent(current)) match
            case (ReactiveRegion.ParentContext.HtmlTable, ReactiveRegion.TableContent.Rows) => ReactiveRegion.ParentContext.Other
            case _                                                                          => parentContext

    private type DragResolver = Maybe[(String, Drag.Decision) => Unit < Async]

    final private case class DragSession(
        event: Drag.Event,
        terminalResolved: Boolean,
        /** A reading of [[Clock.nowMonotonic]]. */
        expiresAt: Duration,
        resolver: DragResolver
    )

    final private[kyo] case class DragSessionLimits(
        maxSessions: Int = 128,
        lifetime: Duration = 5.minutes
    ):
        require(maxSessions > 0, s"DragSessionLimits.maxSessions must be positive: $maxSessions")
        require(lifetime > Duration.Zero, s"DragSessionLimits.lifetime must be positive: $lifetime")
    end DragSessionLimits

    /** Walk a static UI tree. Collect reactive children, build handle. */
    private[internal] def walkStatic(
        ui: UI,
        basePath: Seq[String],
        svg: Boolean = false,
        context: ReactiveRegion.RegionIdentity,
        parentContext: ReactiveRegion.ParentContext,
        cells: MountDispatch,
        rows: Long,
        deepRows: Chunk[Int],
        discoverRootBound: Boolean = true
    )(using Frame): (Seq[ReactiveUI], Handler) < Sync =
        ui match
            case elem: Element if discoverRootBound && collectSignalRef(elem).nonEmpty =>
                for rui <- normalizeAt(elem, basePath, svg, context, parentContext, cells, rows, deepRows)
                yield (Seq(rui), rui.handle)

            case elem: Element =>
                // ForeignObject bridges back to HTML, so reset svg context to false. It MUST be matched
                // before SvgElement (ForeignObject IS an SvgElement).
                val childSvg = elem match
                    case _: Svg.ForeignObject => false
                    case _: Svg.SvgElement    => true
                    case _                    => svg
                val childParentContext = elem match
                    case _: Table => ReactiveRegion.ParentContext.HtmlTable
                    case _        => ReactiveRegion.ParentContext.Other
                for childWalks <- Kyo.foreach(elem.children.toSeq.zipWithIndex) { (child, i) =>
                        val childPath    = basePath :+ i.toString
                        val childContext = context.child(i.toString)
                        child match
                            case _: Reactive[?] | _: Foreach[?, ?] | _: Mounted =>
                                for rui <- normalizeAt(child, childPath, childSvg, childContext, childParentContext, cells, rows, deepRows)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case childElem: Element if collectSignalRef(childElem).nonEmpty =>
                                // Element with SignalRef-bound attributes is reactive over those signals;
                                // normalize it so subscribeNode wires updates.
                                for rui <-
                                        normalizeAt(childElem, childPath, childSvg, childContext, childParentContext, cells, rows, deepRows)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case _ =>
                                for
                                    (innerKids, innerHandle) <-
                                        walkStatic(child, childPath, childSvg, childContext, childParentContext, cells, rows, deepRows)
                                yield (innerKids, Seq((i, innerHandle)))
                        end match
                    }
                yield
                    val reactiveChildren = childWalks.flatMap(_._1)
                    val staticHandlers   = childWalks.flatMap(_._2)
                    val handle: Handler  = (targetPath, event) =>
                        dispatch(elem, basePath, targetPath, event, reactiveChildren, staticHandlers, cells)
                    (reactiveChildren, handle)
                end for

            case Fragment(children) =>
                for childWalks <- Kyo.foreach(children.toSeq.zipWithIndex) { (child, i) =>
                        val childPath = child match
                            case kc: KeyedChild[?] => basePath :+ kc.key
                            case _                 => basePath :+ i.toString
                        val childContext = child match
                            case kc: KeyedChild[?] => context.child(kc.key)
                            case _                 => context.child(i.toString)
                        child match
                            case kc: KeyedChild[?] =>
                                val depth = basePath.size
                                if depth < 63 then
                                    walkStatic(kc.child, childPath, svg, childContext, parentContext, cells, rows | (1L << depth), deepRows)
                                else walkStatic(kc.child, childPath, svg, childContext, parentContext, cells, rows, deepRows.append(depth))
                                end if
                            case _ => walkStatic(child, childPath, svg, childContext, parentContext, cells, rows, deepRows)
                        end match
                    }
                yield
                    val allKids    = childWalks.flatMap(_._1)
                    val allHandles = childWalks.zipWithIndex.map { case ((_, h), i) => (i, h) }
                    val keyMap     = children.toSeq.zipWithIndex.collect {
                        case (kc: KeyedChild[?], i) => kc.key -> i
                    }.toMap
                    val handle: Handler = (targetPath, event) =>
                        if targetPath.size > basePath.size then
                            val segment = targetPath(basePath.size)
                            val idx     = Maybe.fromOption(keyMap.get(segment)).orElse(Maybe.fromOption(segment.toIntOption))
                            idx.flatMap(i => Maybe.fromOption(allHandles.lift(i)).map(_._2))
                                .fold(false: Boolean < Async)(h => h(targetPath, event))
                        else true
                    (allKids, handle)

            case _: Reactive[?] | _: Foreach[?, ?] | _: Mounted =>
                // When walkStatic is called with a Reactive, Foreach or Mounted as the top-level node
                // (not as a child of an Element), normalize it at basePath so subscribeNode
                // sets up a subscription for it. This handles the case where an outer reactive's
                // signal value is itself a Reactive or Foreach (e.g. outer.map { _ => inner.map(UI.span(_)) }).
                for rui <- normalizeAt(ui, basePath, svg, context, parentContext, cells, rows, deepRows)
                yield (Seq(rui), rui.handle)

            case _ =>
                val noHandle: Handler = (_, _) => true
                (Seq.empty[ReactiveUI], noHandle)

    // ---- Subscribe ----

    /** Result of subscribe: dispatch handler + signal change tracking. The enclosing Scope owns every
      * region fiber's lifecycle; the per-value Scope (opened by observe) owns each region's children,
      * so closing the root scope cascade-tears-down the tree.
      */
    case class Subscription(
        handle: Handler,
        private[kyo] val handleValidated: ValidatedHandler,
        lastSignalChangeTime: AtomicRef[Instant],
        private val dragSessionCountFn: () => Int < Sync = () => 0,
        private val dragExpiryWorkerCountFn: () => Int < Sync = () => 0,
        private val dragLock: Meter = Meter.Noop
    ):
        private[kyo] def dragSessionCount(using Frame): Int < Sync      = dragSessionCountFn()
        private[kyo] def dragExpiryWorkerCount(using Frame): Int < Sync = dragExpiryWorkerCountFn()

        /** Runs `v` under the mutex that serializes drag dispatch and the expiry worker's clock reading and timer arming. A test moves a
          * controlled clock here so the move never splits the worker's reading from its arming.
          */
        private[kyo] def withDragLock[A](v: => A < Async)(using Frame): A < (Async & Abort[Closed]) = dragLock.run(v)
    end Subscription

    private enum OwnedFiberState derives CanEqual:
        case Waiting
        case Running(fiber: Fiber[Unit, Any])
        case Closed
    end OwnedFiberState

    private[internal] def startOwnedFiber(task: => Unit < Async)(using Frame): Unit < (Async & Scope) =
        for
            state <- AtomicRef.init[OwnedFiberState](OwnedFiberState.Waiting)
            start <- Promise.init[Unit, Any]
            _     <- Scope.ensure(closeOwnedFiber(state))
            fiber <- Fiber.initUnscoped(start.get.andThen(task))
            _     <- installOwnedFiber(state, start, fiber)
        yield ()
    end startOwnedFiber

    private def installOwnedFiber(
        state: AtomicRef[OwnedFiberState],
        start: Promise[Unit, Any],
        fiber: Fiber[Unit, Any]
    )(using Frame): Unit < Async =
        state.get.flatMap {
            case OwnedFiberState.Waiting =>
                state.compareAndSet(OwnedFiberState.Waiting, OwnedFiberState.Running(fiber)).flatMap { installed =>
                    if installed then start.completeUnitDiscard
                    else installOwnedFiber(state, start, fiber)
                }
            case _: OwnedFiberState.Running =>
                Abort.panic(IllegalStateException("Reactive observer fiber was installed twice"))
            case OwnedFiberState.Closed =>
                fiber.interrupt.andThen(fiber.getResult.unit)
        }
    end installOwnedFiber

    private def closeOwnedFiber(state: AtomicRef[OwnedFiberState])(using Frame): Unit < Async =
        state.getAndSet(OwnedFiberState.Closed).flatMap {
            case OwnedFiberState.Running(fiber) => fiber.interrupt.andThen(fiber.getResult.unit)
            case OwnedFiberState.Waiting        => ()
            case OwnedFiberState.Closed         => ()
        }
    end closeOwnedFiber

    /** Subscribe all reactive boundaries under the caller's Scope. Returns the dispatch handle + change
      * time; lifecycle is owned by the enclosing Scope (closing it interrupts every region fiber and awaits its unwind).
      */
    def subscribe(
        rui: ReactiveUI,
        exchange: UIExchange,
        dragLimits: DragSessionLimits = DragSessionLimits()
    )(using Frame): Subscription < (Async & Scope) =
        for
            signalChangeTime <- AtomicRef.init(Instant.Epoch)
            dragSessions     <- AtomicRef.init(Map.empty[String, DragSession])
            dragMutex        <- Meter.initMutex
            expiryWake       <- Channel.init[Unit](1)
            expiryWorkers    <- AtomicInt.init(0)
            _                <- Scope.ensure(dragSessions.set(Map.empty))
            // The worker clears its own count as it unwinds, which is right when it exits on its own but is not
            // enough at scope close: interrupting a fiber and awaiting its result does not await the finalizer
            // that clears the count, so the count can still read 1 after the scope has returned. Scope
            // finalizers run last-registered-first, so registering this BEFORE the worker is started puts it
            // after the worker's own teardown and makes "closed scope implies no worker" hold on every exit.
            _ <- Scope.ensure(expiryWorkers.set(0))
            _ <- expiryWorkers.set(1)
            _ <- startOwnedFiber(
                Abort.run[Any] {
                    Sync.ensure(expiryWorkers.set(0))(expiryScheduler(dragSessions, dragMutex, expiryWake))
                }.unit
            )
            // Absent only for a ReactiveUI a test builds by hand; `normalize` always records the table.
            dispatch <- rui.mountDispatch match
                case Present(d) => Kyo.lift(d)
                case Absent     => MountDispatch.init
            rootMounts = MountTableRef.init()
            _ <- Scope.ensure(rootMounts.evictAll)
            _ <- subscribeScoped(rui, exchange, signalChangeTime, rootMounts, dispatch, RegionKeys(Seq(rui)))
            _ <- rootMounts.endWalk
            validatedHandle = dragHandle(rui.handle, dragSessions, dragMutex, expiryWake, dragLimits)
        yield Subscription(
            (path, event) =>
                DragProtocol.validateEventAndDomain(event, DragProtocol.Limits.default) match
                    case Result.Success(validated) => validatedHandle(path, validated)
                    case _                         => true,
            validatedHandle,
            signalChangeTime,
            () => dragSessions.get.map(_.size),
            () => expiryWorkers.get,
            dragMutex
        )

    /** Runs `ui` under `exchange` in a scope of its own and hands over the subscription. What runs a tree for its effects, and what a test
      * drives one with.
      */
    private[kyo] def subscribed[A, S](
        ui: UI,
        exchange: UIExchange,
        dragLimits: DragSessionLimits = DragSessionLimits()
    )(f: Subscription => A < (Async & Scope & S))(using Frame): A < (Async & S) =
        Scope.run(normalize(ui, Seq.empty).map(root => subscribe(root, exchange, dragLimits).map(f)))

    private def dragHandle(
        handle: Handler,
        sessions: AtomicRef[Map[String, DragSession]],
        mutex: Meter,
        expiryWake: Channel[Unit],
        limits: DragSessionLimits
    )(using Frame): ValidatedHandler =
        (path, event) =>
            event match
                case event: DragProtocol.ValidatedEvent.Click         => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.ClickSelf     => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Input         => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Change        => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.ChangeChecked => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.ChangeNumeric => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Submit        => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.KeyDown       => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.KeyUp         => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Focus         => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Blur          => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Scroll        => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Hover         => safeDispatch(handle, path, event.wire)
                case event: DragProtocol.ValidatedEvent.Unhover       => safeDispatch(handle, path, event.wire)
                case _: DragProtocol.ValidatedEvent.Start | _: DragProtocol.ValidatedEvent.End |
                    _: DragProtocol.ValidatedEvent.Enter | _: DragProtocol.ValidatedEvent.Leave |
                    _: DragProtocol.ValidatedEvent.Over | _: DragProtocol.ValidatedEvent.Drop |
                    _: DragProtocol.ValidatedEvent.SortMove =>
                    Abort.runPartial[Closed](
                        mutex.run(dragDispatch(handle, sessions, expiryWake, limits, path, event))
                    ).map {
                        case Result.Success(value) => value
                        case Result.Failure(_)     => true
                    }

    private def dragDispatch(
        handle: Handler,
        sessions: AtomicRef[Map[String, DragSession]],
        expiryWake: Channel[Unit],
        limits: DragSessionLimits,
        path: Seq[String],
        event: DragProtocol.ValidatedEvent
    )(using Frame): Boolean < Async =
        event match
            case DragProtocol.ValidatedEvent.Start(wire @ UIEvent.DragStart(_, data), domainItems) =>
                DragCommands.resolveSink.use { resolver =>
                    sessions.get.map { current =>
                        if current.contains(data.sessionId) then
                            DragCommands.resolve(
                                data.sessionId,
                                Drag.Decision.Reject(
                                    Drag.Rejection.Application("A drag session with this identifier is already active.")
                                )
                            ).andThen(true)
                        else if current.size >= limits.maxSessions then
                            DragCommands.resolve(
                                data.sessionId,
                                Drag.Decision.Reject(Drag.Rejection.Application("Too many active drag sessions."))
                            ).andThen(true)
                        else
                            val domain = Drag.Event(
                                data.sessionId,
                                domainItems,
                                data.operation,
                                data.sourceKey,
                                Absent,
                                Present(data.point),
                                data.modifiers,
                                Absent
                            )
                            Clock.nowMonotonic.map { now =>
                                sessions.set(current +
                                    (data.sessionId -> DragSession(
                                        domain,
                                        terminalResolved = false,
                                        now + limits.lifetime,
                                        resolver
                                    )))
                                    .andThen(wakeExpiryScheduler(expiryWake))
                                    .andThen(DragCommands.current.let(Present(DragCommands.Dispatch(
                                        DragCommands.Payload.Event(domain),
                                        Absent
                                    ))) {
                                        safeDispatch(handle, path, event.wire)
                                    })
                            }
                    }
                }
            case DragProtocol.ValidatedEvent.End(wire @ UIEvent.DragEnd(_, data)) =>
                sessions.get.map(_.get(data.sessionId)).map {
                    case Some(session) =>
                        val finalEvent = session.event.copy(operation = data.operation)
                        sessions.getAndUpdate(_ - data.sessionId).unit
                            .andThen(DragCommands.current.let(Present(DragCommands.Dispatch(
                                DragCommands.Payload.End(Drag.End(finalEvent, canceled = data.cancelled)),
                                Absent
                            ))) {
                                safeDispatch(handle, path, wire)
                            })
                    case None => true
                }
            case DragProtocol.ValidatedEvent.Enter(wire) =>
                dispatchTarget(handle, path, wire, wire.event, sessions, terminal = false)
            case DragProtocol.ValidatedEvent.Leave(wire) =>
                dispatchTarget(handle, path, wire, wire.event, sessions, terminal = false)
            case DragProtocol.ValidatedEvent.Over(wire) =>
                dispatchTarget(handle, path, wire, wire.event, sessions, terminal = false)
            case DragProtocol.ValidatedEvent.Drop(wire) =>
                dispatchTarget(handle, path, wire, wire.event, sessions, terminal = true)
            case DragProtocol.ValidatedEvent.SortMove(wire @ UIEvent.SortMove(_, sessionId, move)) =>
                sessions.get.map(_.get(sessionId)).map {
                    case Some(session) if !session.terminalResolved =>
                        for
                            decisions <- AtomicRef.init[DragCommands.DecisionState](DragCommands.DecisionState.None)
                            _         <- DragCommands.current.let(Present(DragCommands.Dispatch(
                                DragCommands.Payload.Move(move),
                                Present(decisions)
                            ))) {
                                safeDispatch(handle, path, wire)
                            }
                            decision <- finalDecision(decisions, "No sort handler accepted the move.")
                            _        <- sessions.getAndUpdate(_ + (sessionId -> session.copy(terminalResolved = true)))
                            _        <- DragCommands.resolve(sessionId, decision)
                        yield true
                    case None =>
                        DragCommands.resolve(
                            sessionId,
                            Drag.Decision.Reject(Drag.Rejection.Application("No sort handler accepted the move."))
                        ).andThen(true)
                    case _ => true
                }
            case _: DragProtocol.ValidatedEvent.Click | _: DragProtocol.ValidatedEvent.ClickSelf |
                _: DragProtocol.ValidatedEvent.Input | _: DragProtocol.ValidatedEvent.Change |
                _: DragProtocol.ValidatedEvent.ChangeChecked | _: DragProtocol.ValidatedEvent.ChangeNumeric |
                _: DragProtocol.ValidatedEvent.Submit | _: DragProtocol.ValidatedEvent.KeyDown |
                _: DragProtocol.ValidatedEvent.KeyUp | _: DragProtocol.ValidatedEvent.Focus |
                _: DragProtocol.ValidatedEvent.Blur | _: DragProtocol.ValidatedEvent.Scroll |
                _: DragProtocol.ValidatedEvent.Hover | _: DragProtocol.ValidatedEvent.Unhover =>
                safeDispatch(handle, path, event.wire)

    private def wakeExpiryScheduler(expiryWake: Channel[Unit])(using Frame): Unit < Sync =
        Abort.runPartial[Closed](expiryWake.offer(())).unit

    private def expiryScheduler(
        sessions: AtomicRef[Map[String, DragSession]],
        mutex: Meter,
        expiryWake: Channel[Unit]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Loop.foreach {
            mutex.run(expireAndArm(sessions)).map { timer =>
                val next: Unit < (Async & Abort[Closed]) = timer match
                    case Present(timer) => Async.race(timer.get, expiryWake.take.unit)
                    case Absent         => expiryWake.take
                Abort.runPartial[Closed](next).map {
                    case Result.Success(_) => Loop.continue
                    case Result.Failure(_) => Loop.done
                }
            }
        }

    // The clock reading, the expiry pass, and the arming of the next timer form one section under the drag mutex, which
    // also serializes every session admission. A clock moved while holding the same mutex never lands between the reading
    // and the arming, so the armed deadline is exactly the earliest remaining expiry.
    private def expireAndArm(sessions: AtomicRef[Map[String, DragSession]])(using Frame): Maybe[Fiber[Unit, Any]] < Async =
        Clock.nowMonotonic.map { now =>
            expireSessions(now, sessions).andThen(sessions.get.map { current =>
                current.valuesIterator.map(_.expiresAt).reduceOption(_.min(_)) match
                    case None            => Maybe.empty
                    case Some(expiresAt) => Clock.sleep(expiresAt.minusOrZero(now)).map(Maybe(_))
            })
        }

    private def expireSessions(now: Duration, sessions: AtomicRef[Map[String, DragSession]])(using Frame): Unit < Async =
        sessions.get.map { current =>
            val (expired, retained) = current.partition((_, session) => session.expiresAt <= now)
            sessions.set(retained).andThen(Kyo.foreach(expired.toSeq) { case (sessionId, session) =>
                if session.terminalResolved then ()
                else
                    session.resolver.fold((): Unit < Async)(_(
                        sessionId,
                        Drag.Decision.Reject(Drag.Rejection.Application("The drag session expired."))
                    ))
            }.unit)
        }

    private def dispatchTarget(
        handle: Handler,
        path: Seq[String],
        wire: UIEvent,
        data: DragProtocol.TargetData,
        sessions: AtomicRef[Map[String, DragSession]],
        terminal: Boolean
    )(using Frame): Boolean < Async =
        sessions.get.map(_.get(data.sessionId)).map {
            case Some(session) if !session.terminalResolved =>
                val domain = session.event.copy(
                    operation = data.operation,
                    targetKey = data.targetKey,
                    point = Present(data.point),
                    modifiers = data.modifiers,
                    position = data.position
                )
                if terminal then
                    for
                        decisions <- AtomicRef.init[DragCommands.DecisionState](DragCommands.DecisionState.None)
                        _         <- sessions.getAndUpdate(_ + (data.sessionId -> session.copy(event = domain)))
                        _         <- DragCommands.current.let(Present(DragCommands.Dispatch(
                            DragCommands.Payload.Event(domain),
                            Present(decisions)
                        ))) {
                            safeDispatch(handle, path, wire)
                        }
                        decision <- finalDecision(decisions, "No drop handler accepted the operation.")
                        _        <- sessions.getAndUpdate(_ + (data.sessionId -> session.copy(event = domain, terminalResolved = true)))
                        _        <- DragCommands.resolve(data.sessionId, decision)
                    yield true
                else
                    sessions.getAndUpdate(_ + (data.sessionId -> session.copy(event = domain))).unit
                        .andThen(DragCommands.current.let(Present(DragCommands.Dispatch(DragCommands.Payload.Event(domain), Absent))) {
                            safeDispatch(handle, path, wire)
                        })
                end if
            case None if terminal =>
                DragCommands.resolve(
                    data.sessionId,
                    Drag.Decision.Reject(Drag.Rejection.Application("No drop handler accepted the operation."))
                ).andThen(true)
            case _ => true
        }

    private def finalDecision(ref: AtomicRef[DragCommands.DecisionState], emptyReason: String)(using Frame): Drag.Decision < Sync =
        ref.get.map {
            case DragCommands.DecisionState.None            => Drag.Decision.Reject(Drag.Rejection.Application(emptyReason))
            case DragCommands.DecisionState.Accepted        => Drag.Decision.Accept
            case DragCommands.DecisionState.Rejected(value) => value
            case DragCommands.DecisionState.Failed(value)   => value
        }

    /** The tail of a walked value: its kids subscribed against `table`, then the end of the table's pass. A region and a mount share it. */
    private[internal] def subscribeWalked(
        kids: Seq[ReactiveUI],
        keys: RegionKeys,
        table: MountTableRef,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        dispatch: MountDispatch
    )(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(kids)(subscribeScoped(_, exchange, signalChangeTime, table, dispatch, keys)).andThen(table.endWalk)

    // Each reactive region starts an observer fiber as one scoped resource. Its finalizer interrupts the observer and
    // awaits the fiber's terminal result. Signal.observe runs each value in a fresh Scope; each value renders, re-walks,
    // and starts its children in that Scope, so the next value or an interrupt closes them transitively. A const node
    // has no signal: it subscribes its children in the enclosing scope. `mounts` is the mount table of the enclosing
    // region; keyed instances live in that tree of tables, outside every subscription's scopes.
    private[internal] def subscribeScoped(
        rui: ReactiveUI,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        mounts: MountTableRef,
        dispatch: MountDispatch,
        pass: RegionKeys
    )(using Frame): Unit < (Async & Scope) =
        if rui.mounted.nonEmpty then
            subscribeMounted(rui, rui.mounted.get, exchange, signalChangeTime, mounts, dispatch)
        else if rui.isConst then
            val children = RegionKeys(rui.children)
            Kyo.foreachDiscard(rui.children)(subscribeScoped(_, exchange, signalChangeTime, mounts, dispatch, children))
        else
            // The tree this region last rendered, so the exchange can send only what moved instead of the
            // whole region.
            // Unsafe: region-scoped, single-writer state created before the observe loop is forked, where no
            // effect context exists to supply AllowUnsafe; only this region's own loop reads or writes it.
            val rendered = AtomicRef.Unsafe.init(Maybe.empty[UI])(using AllowUnsafe.embrace.danger)
            // Unsafe: as for `rendered`, the region's reference is made before its loop is forked, where no proof is at hand.
            val regionMounts = mounts.child(pass, rui)(using AllowUnsafe.embrace.danger)
            // Per-value setup, run inside each value's fresh Scope: render the region and fork its children into
            // that scope, so the next value (or an interrupt) tears them down by cascade.
            def renderValue(current: UI): Unit < (Async & Scope) =
                for
                    now      <- Clock.now
                    _        <- signalChangeTime.set(now)
                    previous <- Sync.Unsafe.defer(rendered.getAndSet(Present(current)))
                    _        <- exchange.onChange(rui.region, rui.path, rui.contentContext, rui.parentContext, previous, current)
                    svgContext = rui.region match
                        case _: ReactiveRegion.HtmlRange  => false
                        case _: ReactiveRegion.SvgElement => true
                    contentParentContext = nestedParentContext(rui.parentContext, current)
                    (newKids, _) <- walkStatic(
                        current,
                        rui.path,
                        svgContext,
                        rui.contentContext,
                        contentParentContext,
                        dispatch,
                        rows = 0L,
                        deepRows = Chunk.empty,
                        discoverRootBound = rui.discoverContentRootBound
                    )
                    keys = RegionKeys(newKids)
                    _ <- regionMounts.beginWalk(newKids, keys)
                    _ <- subscribeWalked(newKids, keys, regionMounts, exchange, signalChangeTime, dispatch)
                yield ()

            startOwnedFiber {
                Abort.run[Throwable] {
                    rui.signal.observe(renderValue)
                }.map { result =>
                    result.fold(
                        _ => (),
                        err => Log.error(s"Reactive subscription fiber failed at path=${rui.path.mkString(".")}", err),
                        panic =>
                            if panic.isInstanceOf[Interrupted] then ()
                            else Log.error(s"Reactive subscription fiber failed at path=${rui.path.mkString(".")}", panic)
                    )
                }
            }
        end if
    end subscribeScoped

    // ---- Shared UI tree utilities (used by both backends) ----

    /** Resolve all reactive signals in a UI tree, returning a static snapshot. */
    def resolveReactives(ui: UI)(using Frame): UI < Sync =
        ui match
            case r: Reactive[?] =>
                for
                    current  <- r.signal.current(using r.frame)
                    resolved <- resolveReactives(current)
                yield resolved
            case fe: Foreach[?, ?] @unchecked =>
                fe.applyTyped {
                    [T] => (signal, keyFn, renderFn) =>
                        for
                            items <- signal.current(using fe.frame)
                            children = items.toSeq.zipWithIndex.map { (item, i) =>
                                val key = keyFn match
                                    case Present(f) => f(item)
                                    case Absent     => i.toString
                                KeyedChild[UI](key, renderFn(i, item))
                            }
                            resolved <- Kyo.foreach(children)(resolveReactives)
                        yield Fragment[UI](Chunk.from(resolved))
                        end for
                }
            case elem: Element =>
                Kyo.foreach(elem.children.toSeq)(resolveReactives).map { resolved =>
                    val resolvedChunk = Chunk.from(resolved)
                    if resolvedChunk == elem.children then ui
                    else rebuildElement(elem, resolvedChunk)
                }
            case Fragment(children) =>
                Kyo.foreach(children.toSeq)(resolveReactives).map(r => Fragment[UI](Chunk.from(r)))
            case KeyedChild(key, child) =>
                resolveReactives(child).map(r => KeyedChild[UI](key, r))
            case _ => ui

    private[kyo] def rebuildElement(elem: Element, newChildren: Chunk[UI]): UI =
        given Frame = elem.frame
        elem match
            case e: Div            => e.copy(children = newChildren)
            case e: SpanElement    => e.copy(children = newChildren)
            case e: P              => e.copy(children = newChildren)
            case e: Section        => e.copy(children = newChildren)
            case e: Main           => e.copy(children = newChildren)
            case e: Header         => e.copy(children = newChildren)
            case e: Footer         => e.copy(children = newChildren)
            case e: Pre            => e.copy(children = newChildren)
            case e: Code           => e.copy(children = newChildren)
            case e: Ul             => e.copy(children = newChildren)
            case e: Ol             => e.copy(children = newChildren)
            case e: Table          => e.copy(children = newChildren)
            case e: H1             => e.copy(children = newChildren)
            case e: H2             => e.copy(children = newChildren)
            case e: H3             => e.copy(children = newChildren)
            case e: H4             => e.copy(children = newChildren)
            case e: H5             => e.copy(children = newChildren)
            case e: H6             => e.copy(children = newChildren)
            case e: Nav            => e.copy(children = newChildren)
            case e: Li             => e.copy(children = newChildren)
            case e: Tr             => e.copy(children = newChildren)
            case e: Form           => e.copy(children = newChildren)
            case e: Label          => e.copy(children = newChildren)
            case e: Button         => e.copy(children = newChildren)
            case e: Td             => e.copy(children = newChildren)
            case e: Th             => e.copy(children = newChildren)
            case e: Select         => e.copy(children = newChildren)
            case e: Opt            => e.copy(children = newChildren)
            case e: Anchor         => e.copy(children = newChildren)
            case e: Svg.SvgElement => rebuildSvgElement(e, newChildren)
            case _                 => elem
        end match
    end rebuildElement

    /** Exhaustive match over all SvgElement concrete classes. NO case _ fallback: a missing arm is
      * a compile error (the kyo-ui build escalates the non-exhaustive-match warning to an error for
      * this file; see build.sbt).
      */
    private def rebuildSvgElement(elem: Svg.SvgElement, newChildren: Chunk[UI]): UI =
        given Frame = elem.frame
        elem match
            case e: Svg.Root           => e.copy(children = newChildren)
            case e: Svg.G              => e.copy(children = newChildren)
            case e: Svg.Defs           => e.copy(children = newChildren)
            case e: Svg.Symbol         => e.copy(children = newChildren)
            case e: Svg.Switch         => e.copy(children = newChildren)
            case e: Svg.SvgAnchor      => e.copy(children = newChildren)
            case e: Svg.Use            => e.copy(children = newChildren)
            case e: Svg.Rect           => e.copy(children = newChildren)
            case e: Svg.Circle         => e.copy(children = newChildren)
            case e: Svg.Ellipse        => e.copy(children = newChildren)
            case e: Svg.Line           => e.copy(children = newChildren)
            case e: Svg.Polyline       => e.copy(children = newChildren)
            case e: Svg.Polygon        => e.copy(children = newChildren)
            case e: Svg.Path           => e.copy(children = newChildren)
            case e: Svg.Text           => e.copy(children = newChildren)
            case e: Svg.TSpan          => e.copy(children = newChildren)
            case e: Svg.TextPath       => e.copy(children = newChildren)
            case e: Svg.LinearGradient => e.copy(children = newChildren)
            case e: Svg.RadialGradient => e.copy(children = newChildren)
            case e: Svg.Stop           => e.copy(children = newChildren)
            case e: Svg.Pattern        => e.copy(children = newChildren)
            case e: Svg.ClipPath       => e.copy(children = newChildren)
            case e: Svg.Mask           => e.copy(children = newChildren)
            case e: Svg.Image          => e.copy(children = newChildren)
            case e: Svg.ForeignObject  => e.copy(children = newChildren)
            case e: Svg.Marker         => e.copy(children = newChildren)
            case e: Svg.Title          => e.copy(children = newChildren)
            case e: Svg.Desc           => e.copy(children = newChildren)
            case e: Svg.Metadata       => e.copy(children = newChildren)
            // filter family: only Filter and FeMerge carry reactive children; the rest are leaves.
            case e: Svg.Filter            => e.copy(children = newChildren)
            case e: Svg.FeGaussianBlur    => e
            case e: Svg.FeOffset          => e
            case e: Svg.FeBlend           => e
            case e: Svg.FeColorMatrix     => e
            case e: Svg.FeFlood           => e
            case e: Svg.FeComposite       => e
            case e: Svg.FeMerge           => e.copy(children = newChildren)
            case e: Svg.FeMergeNode       => e
            case e: Svg.FeImage           => e
            case e: Svg.FeTile            => e
            case e: Svg.FeMorphology      => e
            case e: Svg.FeTurbulence      => e
            case e: Svg.FeDisplacementMap => e
            // SMIL family: all leaves.
            case e: Svg.Animate          => e
            case e: Svg.AnimateTransform => e
            case e: Svg.AnimateMotion    => e
            case e: Svg.SetAnim          => e
        end match
    end rebuildSvgElement

    /** Find the path to an element by ID in a resolved UI tree. Returns the dot-separated path string. */
    def findPathById(ui: UI, id: String, currentPath: String = ""): Maybe[String] =
        def searchChildren(children: Chunk[UI]): Maybe[String] =
            @scala.annotation.tailrec
            def loop(i: Int): Maybe[String] =
                if i >= children.size then Absent
                else
                    val childPath = if currentPath.isEmpty then i.toString else s"$currentPath.$i"
                    val child     = children(i) match
                        case kc: KeyedChild[?] => kc.child
                        case c                 => c
                    findPathById(child, id, childPath) match
                        case Absent  => loop(i + 1)
                        case present => present
            loop(0)
        end searchChildren
        ui match
            case elem: Element =>
                if elem.attrs.identifier == Present(id) then Present(currentPath)
                else searchChildren(elem.children)
            case Fragment(children) => searchChildren(children)
            case _                  => Absent
        end match
    end findPathById

    /** Find all focusable element IDs in document order from a resolved UI tree. Skips disabled and hidden elements.
      */
    def findAllFocusableIds(ui: UI): Seq[String] =
        val builder              = Seq.newBuilder[String]
        def walk(node: UI): Unit = node match
            case elem: Element =>
                val disabled = elem match
                    case hd: HasDisabled => hd.disabled.getOrElse(false)
                    case _               => false
                val hidden = elem.attrs.hidden.getOrElse(false)
                if !disabled && !hidden then
                    val focusable = elem.isInstanceOf[Focusable] || elem.attrs.tabIndex.nonEmpty
                    if focusable then
                        elem.attrs.identifier.foreach(id => builder += id)
                end if
                elem.children.foreach(walk)
            case Fragment(children)   => children.foreach(walk)
            case KeyedChild(_, child) => walk(child)
            case _                    => ()
        walk(ui)
        builder.result()
    end findAllFocusableIds

    /** Find element by ID in a resolved UI tree. */
    def findElementById(ui: UI, id: String): Maybe[Element] =
        def searchChildren(children: Chunk[UI]): Maybe[Element] =
            @scala.annotation.tailrec
            def loop(i: Int): Maybe[Element] =
                if i >= children.size then Absent
                else
                    findElementById(children(i), id) match
                        case Absent  => loop(i + 1)
                        case present => present
            loop(0)
        end searchChildren
        ui match
            case elem: Element =>
                if elem.attrs.identifier == Present(id) then Present(elem)
                else searchChildren(elem.children)
            case Fragment(children)   => searchChildren(children)
            case KeyedChild(_, child) => findElementById(child, id)
            case _                    => Absent
        end match
    end findElementById

    /** Find element at a dot-separated path in the UI tree. */
    @scala.annotation.tailrec
    def findAtPath(ui: UI, path: String): UI =
        if path.isEmpty then ui
        else
            val dotIdx  = path.indexOf('.')
            val segment = if dotIdx < 0 then path else path.substring(0, dotIdx)
            val rest    = if dotIdx < 0 then "" else path.substring(dotIdx + 1)
            childAt(ui, segment) match
                case Present(child) => findAtPath(child, rest)
                case Absent         => ui

    private def childAt(ui: UI, segment: String): Maybe[UI] =
        ui match
            case elem: Element =>
                Maybe.fromOption(segment.toIntOption) match
                    case Present(i) if i >= 0 && i < elem.children.size =>
                        elem.children(i) match
                            case kc: KeyedChild[?] => Present(kc.child)
                            case child             => Present(child)
                    case _ => Absent
            case Fragment(children) =>
                val keyIdx = Maybe.fromOption(children.toSeq.zipWithIndex.collectFirst {
                    case (kc: KeyedChild[?], _) if kc.key == segment => kc.child
                })
                keyIdx match
                    case Present(child) => Present(child)
                    case Absent         =>
                        Maybe.fromOption(segment.toIntOption) match
                            case Present(i) if i >= 0 && i < children.size =>
                                children(i) match
                                    case kc: KeyedChild[?] => Present(kc.child)
                                    case child             => Present(child)
                            case _ => Absent
                end match
            case _ => Absent

end ReactiveUI
