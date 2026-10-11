package kyo.internal

import EventDispatch.*
import MountRegions.*
import ReactiveUI.*
import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.kernel.ContextEffect

/** Where event dispatch finds a mounted node's live content.
  *
  * Dispatch re-derives handler chains from the current UI on every event, so the `Mounted` node it meets is a fresh value that cannot
  * carry the instance. The instance registers its content cell here by path while it is subscribed, and the dispatch-time handler of
  * whatever node occupies that path reads it. One table per normalized root.
  */
final private[kyo] class MountDispatch(cells: AtomicRef[Dict[Seq[String], Chunk[Signal[MountState]]]]):
    def register(path: Seq[String], cell: Signal[MountState])(using Frame): Unit < Sync =
        cells.getAndUpdate(m => m.update(path, m.get(path).getOrElse(Chunk.empty).append(cell))).unit

    /** Removes this registration only. Two subscriptions can hold one path for a moment, an old one still ending and its successor,
      * in either order; the other keeps its entry.
      */
    def unregister(path: Seq[String], cell: Signal[MountState])(using Frame): Unit < Sync =
        cells.getAndUpdate { m =>
            m.get(path) match
                case Absent        => m
                case Present(held) =>
                    val at   = held.lastIndexWhere(_ eq cell)
                    val left = if at < 0 then held else held.take(at).concat(held.drop(at + 1))
                    if left.isEmpty then m.remove(path) else m.update(path, left)
        }.unit

    /** The cell registered last at `path`. */
    def lookup(path: Seq[String])(using Frame): Maybe[Signal[MountState]] < Sync =
        cells.get.map(_.get(path).flatMap(_.lastMaybe))
end MountDispatch

private[kyo] object MountDispatch:
    def init(using Frame): MountDispatch < Sync =
        AtomicRef.init(Dict.empty[Seq[String], Chunk[Signal[MountState]]]).map(new MountDispatch(_))

/** A `Mounted` node as normalization found it: the node, its render path, and the keys of the keyed list rows between it and its
  * enclosing reactive region, innermost first.
  */
final private[kyo] case class MountedSpec(node: UI.Ast.Mounted, path: Seq[String], rows: Chunk[String]):
    def placeholder: UI = node.placeholderUI.getOrElse(UI.empty(using node.frame))

    def errorUI(error: UI.MountError): UI =
        node.errorRender match
            case Present(render) => render(error)
            case Absent          =>
                given Frame = node.frame
                UI.span(UI.Ast.Text(error.message)).cssClass("kyo-mount-error")

    def mountError(t: Throwable, source: UI.MountErrorSource): UI.MountError =
        UI.MountError(t, source, Maybe(t.getMessage).getOrElse(t.toString), node.frame, path, node.key)

    /** The instance identity of a keyed node; a keyless node has none, since every re-render replaces its instance. */
    def identity: Maybe[MountIdentity] =
        node.key.map(MountIdentity(rows, node.site, _))
end MountedSpec

/** What a mounted node's content cell holds.
  *
  *   - `Pending(Absent)`: the effect has not published, and the node shows its placeholder.
  *   - `Pending(Present(snapshot))`: the same, showing the inert snapshot of what the slot showed before. A snapshot is painted and
  *     nothing else: no region in it is subscribed and no event reaches it.
  *   - `Ready(ui)`: the published content.
  *   - `Failed(ui)`: the rendered failure. It is final: a later publish or failure of the same instance leaves it as it is.
  */
private[kyo] enum MountState derives CanEqual:
    case Pending(snapshot: Maybe[UI])
    case Ready(ui: UI)
    case Failed(ui: UI)

    def content(spec: MountedSpec): UI =
        this match
            case Pending(snapshot) => snapshot.getOrElse(spec.placeholder)
            case Ready(ui)         => ui
            case Failed(ui)        => ui
end MountState

/** The identity a keyed mount instance keeps across re-renders: the keys of the list rows it sits in, where its `.keyed` call is
  * written and its key. Positions among siblings are deliberately not part of it, so siblings that come and go leave it alone; they
  * only tell apart nodes of one render pass that share an identity (see [[MountTable]]). The hash is computed once.
  */
final private[kyo] case class MountIdentity(rows: Chunk[String], site: UI.MountSite, key: Any) derives CanEqual:
    override val hashCode: Int = (rows.hashCode * 31 + site.hashCode) * 31 + key.hashCode

/** Where a region's mount table sits in the table of the enclosing region: the keys of the list rows the region sits in, the call
  * site of the node it comes from, and its rank among the regions of one render pass that share those two. Like a mount's identity
  * it leaves out positions among siblings; only regions repeated from one call site, outside a keyed list, are told apart by order.
  * A region built by hand, with no call site, is placed by its path.
  */
final private[kyo] case class RegionKey(rows: Chunk[String], site: AnyRef, rank: Int) derives CanEqual:
    override val hashCode: Int =
        val siteCode = site match
            case path: Seq[?] => path.hashCode
            case other        => siteHash(other.asInstanceOf[Frame])
        (rows.hashCode * 31 + siteCode) * 31 + rank
    end hashCode
end RegionKey

/** The keys of the regions among one render pass's reactive children, computed on first use, which only a pass with mounts at or
  * below it ever makes.
  */
final private[kyo] class RegionKeys(kids: Seq[ReactiveUI]):
    private lazy val keys: java.util.IdentityHashMap[ReactiveUI, RegionKey] =
        val result = new java.util.IdentityHashMap[ReactiveUI, RegionKey]()
        // Keyed by the rank-0 key, whose hash is the cheap one.
        val ranks = new java.util.HashMap[RegionKey, Integer]()
        kids.foreach { kid =>
            if kid.mounted.isEmpty && !kid.isConst then
                val first = RegionKey(rowKeys(kid.path, kid.rows, kid.deepRows), kid.origin.fold[AnyRef](kid.path)(f => f), 0)
                val rank  = Maybe(ranks.get(first)).fold(0)(_.intValue + 1)
                discard(ranks.put(first, rank))
                discard(result.put(kid, if rank == 0 then first else first.copy(rank = rank)))
        }
        result
    end keys

    def of(kid: ReactiveUI): RegionKey =
        Maybe(keys.get(kid)).getOrElse(RegionKey(Chunk.empty, kid.path, 0))

    /** The keys of every region in the pass. */
    def all: Set[RegionKey] =
        val it  = keys.values.iterator
        var set = Set.empty[RegionKey]
        while it.hasNext do set += it.next()
        set
    end all
end RegionKeys

private[kyo] object RegionKeys:
    val empty: RegionKeys = new RegionKeys(Seq.empty)

    def apply(kids: Seq[ReactiveUI]): RegionKeys = new RegionKeys(kids)
end RegionKeys

/** A live mount: its content cell, the fiber running its effect, the `Scope` that owns what the effect acquired, and the table of
  * keyed mounts nested in its content.
  */
final private[kyo] class MountInstance(
    val cell: SignalRef[MountState],
    val children: MountTableRef,
    runner: Fiber[Unit, Any],
    scope: Scope.Finalizer
):
    /** What the slot keeps showing under `KeepLatest` once this instance is gone. */
    def snapshot(using Frame): Maybe[UI] < Sync =
        cell.current.map {
            case MountState.Pending(snapshot) => snapshot
            case MountState.Ready(ui)         => resolveReactives(ui).map(Present(_))
            case MountState.Failed(ui)        => resolveReactives(ui).map(Present(_))
        }

    /** Stops the effect if it still runs, then releases nested mounts and everything the effect acquired, waiting for all of it.
      * The wait is explicit because an interrupted `Scope` releases on a fiber of its own that nothing awaits.
      */
    def teardown(using Frame): Unit < Async =
        runner.interrupt
            .andThen(runner.getResult.unit)
            .andThen(children.evictAll)
            .andThen(scope.close(Absent))
            .andThen(scope.await)
end MountInstance

private[kyo] object MountInstance:
    def start(spec: MountedSpec, seed: Maybe[UI])(using Frame): MountInstance < Sync =
        for
            cell   <- Signal.initRef[MountState](MountState.Pending(seed))
            scope  <- Scope.Finalizer.init(1)
            runner <- Fiber.initUnscoped(runEffect(spec, cell, scope))
        yield new MountInstance(cell, MountTableRef.init(), runner, scope)

    /** Runs the effect with `scope` as its `Scope`, so what it acquires outlives this fiber, and publishes the outcome. */
    private def runEffect(spec: MountedSpec, cell: SignalRef[MountState], scope: Scope.Finalizer)(using Frame): Unit < Async =
        val supervision = MountSupervision(spec, cell)
        val effect      = MountSupervision.local.let(Present(supervision))(spec.node.effect)
        Abort.run[Throwable](ContextEffect.handleInheritable(Tag[Scope], scope)(effect)).map {
            case Result.Success(ui) => cell.getAndUpdate(MountSupervision.publish(ui)).unit
            case Result.Failure(t)  =>
                Log.error(s"Mounted effect failed (frame=${spec.node.frame.position.show})", t)
                    .andThen(supervision.fail(t, UI.MountErrorSource.Effect))
            case Result.Panic(t) =>
                if t.isInstanceOf[Interrupted] then Kyo.unit
                else
                    Log.error(s"Mounted effect panicked (frame=${spec.node.frame.position.show})", t)
                        .andThen(supervision.fail(t, UI.MountErrorSource.Panic))
        }
    end runEffect
end MountInstance

/** A fiber forked with [[kyo.UI.fork]] failed with an `Abort` value that is not a `Throwable`. */
final private[kyo] case class MountFiberFailure(value: Any)(using Frame)
    extends KyoException(s"Mounted background fiber failed: $value")

/** Routes the failures of one mount instance into its content cell. The first failure wins, and a publish that arrives after a
  * failure does not overwrite it.
  */
final private[kyo] class MountSupervision(spec: MountedSpec, cell: SignalRef[MountState]):

    /** Completion callback of a [[kyo.UI.fork]] fiber: a terminal failure that is not an interrupt fails the node. */
    def report[E, A](result: Result[E, A])(using Frame): Unit < Sync =
        result match
            case Result.Panic(_: Interrupted)   => Kyo.unit
            case Result.Failure(_: Interrupted) => Kyo.unit
            case Result.Panic(t)                => forked(t)
            case Result.Failure(t: Throwable)   => forked(t)
            case Result.Failure(other)          => forked(MountFiberFailure(other))
            case _                              => Kyo.unit

    private def forked(t: Throwable)(using Frame): Unit < Sync =
        Log.error(s"Mounted background fiber failed (frame=${spec.node.frame.position.show})", t)
            .andThen(fail(t, UI.MountErrorSource.Fork))

    def fail(t: Throwable, source: UI.MountErrorSource)(using Frame): Unit < Sync =
        val failed = MountState.Failed(spec.errorUI(spec.mountError(t, source)))
        cell.getAndUpdate {
            case done: MountState.Failed => done
            case _                       => failed
        }.unit
    end fail
end MountSupervision

private[kyo] object MountSupervision:
    def publish(ui: UI)(state: MountState): MountState =
        state match
            case done: MountState.Failed => done
            case _                       => MountState.Ready(ui)

    /** The mount instance whose effect is running, read by [[kyo.UI.fork]]. Inheritable, so a `UI.fork` inside a fiber the effect
      * forked still reports to the same node. Installed around the effect only: engine fibers never see it.
      */
    private[kyo] val local: Local[Maybe[MountSupervision]] = Local.init(Maybe.empty[MountSupervision])
end MountSupervision

/** The mount instances of one reactive region, created by the first mounted node at or below the region, so a region without one
  * allocates nothing beyond this reference and skips every step below.
  *
  * The table belongs to the table of the enclosing region, under this region's path, not to this region's subscription. When an
  * enclosing region re-renders and subscribes this one again at the same path, the new subscription gets the same table, and its
  * keyed instances carry on: their continuity does not end at the nearest region.
  *
  * The old subscription of that region stops only once the enclosing region's previous value has released, which can be after
  * the new one started. So a table follows the newest reference bound to it, and an older one that still runs a pass changes
  * nothing in it: it releases nothing, records nothing, and a node it subscribes shows the live instance of its identity, or one
  * of its own that its scope releases.
  */
final private[kyo] class MountTableRef private (
    slot: AtomicRef.Unsafe[Maybe[MountTable]],
    parent: Maybe[MountTableRef],
    keys: RegionKeys,
    kid: Maybe[ReactiveUI]
):

    /** Creation order: a later subscription of a region gets a larger serial, whichever binds the table first. */
    val serial: Long = MountTableRef.serials.incrementAndGet()

    /** The reference for the region `region` of the render pass `pass` inside this one, holding the table an earlier subscription
      * of that region created. The region's key is only computed when this table exists, or once the region forces its own.
      */
    def child(pass: RegionKeys, region: ReactiveUI)(using AllowUnsafe): MountTableRef =
        val existing = slot.get().flatMap(table => table.region(pass.of(region)))
        val ref      = new MountTableRef(AtomicRef.Unsafe.init(existing), Present(this), pass, Present(region))
        existing.foreach(_.bind(ref))
        ref
    end child

    private def force(using Frame): MountTable < Sync =
        Sync.Unsafe.defer(slot.get()).map {
            case Present(table) => Kyo.lift(table)
            case Absent         =>
                val table: MountTable < Sync = parent match
                    case Present(enclosing) =>
                        enclosing.force.map(t => Sync.Unsafe.defer(t.adoptRegion(keys.of(kid.get), new MountTable)))
                    case Absent => Sync.Unsafe.defer(new MountTable)
                table.map { fresh =>
                    Sync.Unsafe.defer {
                        discard(slot.compareAndSet(Absent, Present(fresh)))
                        val bound = slot.get().getOrElse(fresh)
                        bound.bind(this)
                        bound
                    }
                }
        }

    private def owned(using AllowUnsafe): Maybe[MountTable] = slot.get().filter(_.ownedBy(this))

    /** Opens a render pass of the region: tears down, and waits for, every keyed instance the new tree no longer shows, before any
      * new instance starts.
      */
    def beginWalk(kids: Seq[ReactiveUI], pass: RegionKeys)(using Frame): Unit < Async =
        Sync.Unsafe.defer(owned).map {
            case Absent         => Kyo.unit
            case Present(table) => table.evictExcept(kids, pass)
        }

    def endWalk(using Frame): Unit < Sync =
        Sync.Unsafe.defer(owned.foreach(_.endWalk()))

    def evictAll(using Frame): Unit < Async =
        Sync.Unsafe.defer(owned).map {
            case Absent         => Kyo.unit
            case Present(table) => table.evictAll
        }

    /** The instance for the keyed node `rui`: the one this pass gave it, or, for a reference the table no longer follows, the live
      * instance of its identity, or else one of its own that the caller's scope releases.
      */
    def claim(rui: ReactiveUI, identity: MountIdentity, spec: MountedSpec)(using Frame): MountInstance < (Async & Scope) =
        force.map { table =>
            Sync.Unsafe.defer {
                if table.ownedBy(this) then table.claim(rui, identity, spec)
                else
                    table.live(identity, spec.path) match
                        case Present(instance) => Kyo.lift(instance)
                        case Absent            => MountInstance.start(spec, Absent).map(i => Scope.ensure(i.teardown).andThen(i))
            }
        }

    /** A keyless instance for `spec`, released with the caller's scope; only the reference the table follows leaves its snapshot. */
    def startKeyless(spec: MountedSpec)(using Frame): MountInstance < (Async & Scope) =
        force.map { table =>
            table.start(spec).map { instance =>
                Scope.ensure(Sync.Unsafe.defer(table.ownedBy(this)).map(owns =>
                    if owns then table.retire(instance, spec.path) else instance.teardown
                )).andThen(instance)
            }
        }
end MountTableRef

private[kyo] object MountTableRef:
    private val serials = new java.util.concurrent.atomic.AtomicLong()

    /** A reference no enclosing table owns: the root of a session, or the content of a mount instance. */
    def init(): MountTableRef =
        new MountTableRef(
            AtomicRef.Unsafe.init(Maybe.empty[MountTable])(using AllowUnsafe.embrace.danger),
            Absent,
            RegionKeys.empty,
            Absent
        )
end MountTableRef

/** One keyed instance of a [[MountTable]] and the path it was last shown at. A slot a pass reserves for a node that had no instance
  * is filled by that node's claim.
  */
final private class MountSlot(@volatile var path: Seq[String]):
    @volatile var instance: Maybe[MountInstance] = Absent

/** Keyed instances by identity, the tables of the regions directly inside this one by [[RegionKey]], and the state of the current render
  * pass: which slot each keyed node of the pass takes, and the snapshots left by instances that ended, keyed by the path they were
  * shown at, for a `KeepLatest` successor at the same place.
  *
  * An identity usually belongs to one node. When a pass shows it several times (one helper, one key, several places), each node
  * gets a slot of its own: a node takes the slot last shown at its path, and the rest are handed out in order, so nodes that keep
  * their places keep their instances and one that goes away takes only its own.
  *
  * The instances and the pass state are written only from the region's own observe loop; the region tables also from the loops of
  * the regions inside it, and the snapshots from the finalizer of a keyless instance, which runs as a loop moves on.
  */
final private[kyo] class MountTable:

    // Unsafe: a table is made with `new` by the reference that creates it, which holds no proof to pass on to the constructor (a proof
    // there would reach every method). The fields are only read and written by methods that hold one.
    private val instances =
        AtomicRef.Unsafe.init(Dict.empty[MountIdentity, Chunk[MountSlot]])(using AllowUnsafe.embrace.danger)
    private val regions  = AtomicRef.Unsafe.init(Dict.empty[RegionKey, MountTable])(using AllowUnsafe.embrace.danger)
    private val assigned =
        AtomicRef.Unsafe.init(Maybe.empty[java.util.IdentityHashMap[ReactiveUI, MountSlot]])(using AllowUnsafe.embrace.danger)
    private val handoff = AtomicRef.Unsafe.init(Dict.empty[Seq[String], UI])(using AllowUnsafe.embrace.danger)
    private val owner   = AtomicRef.Unsafe.init(Maybe.empty[MountTableRef])(using AllowUnsafe.embrace.danger)

    /** Makes `ref` the reference this table follows, unless a newer one already is. */
    def bind(ref: MountTableRef)(using AllowUnsafe): Unit =
        discard(owner.getAndUpdate(current => if current.exists(_.serial > ref.serial) then current else Present(ref)))

    def ownedBy(ref: MountTableRef)(using AllowUnsafe): Boolean = owner.get().exists(_ eq ref)

    /** The running instance of `identity`, the one last shown at `path` when there are several. */
    def live(identity: MountIdentity, path: Seq[String])(using AllowUnsafe): Maybe[MountInstance] =
        instances.get().get(identity).flatMap { slots =>
            Maybe.fromOption(slots.find(slot => slot.instance.nonEmpty && slot.path == path).orElse(slots.find(_.instance.nonEmpty)))
                .flatMap(_.instance)
        }

    def region(key: RegionKey)(using AllowUnsafe): Maybe[MountTable] = regions.get().get(key)

    /** The table registered for the region `key`, which becomes `fresh` when there is none yet. */
    def adoptRegion(key: RegionKey, fresh: MountTable)(using AllowUnsafe): MountTable =
        regions.getAndUpdate(m => if m.contains(key) then m else m.update(key, fresh)).get(key).getOrElse(fresh)

    /** The instance of the slot this pass gave `rui`, started if the slot is empty. A node no pass has seen belongs to a table the
      * pass created, which holds nothing yet, so it starts a new instance.
      */
    def claim(rui: ReactiveUI, identity: MountIdentity, spec: MountedSpec)(using Frame): MountInstance < Sync =
        Sync.Unsafe.defer(assigned.get().flatMap(slots => Maybe(slots.get(rui)))).map {
            case Present(slot) =>
                slot.instance match
                    case Present(instance) => Kyo.lift(instance)
                    case Absent            => start(spec).map(instance => Sync.Unsafe.defer { slot.instance = Present(instance); instance })
            case Absent =>
                start(spec).map { instance =>
                    Sync.Unsafe.defer {
                        val slot = new MountSlot(spec.path)
                        slot.instance = Present(instance)
                        discard(instances.getAndUpdate(m => m.update(identity, m.get(identity).getOrElse(Chunk.empty).append(slot))))
                        instance
                    }
                }
        }

    /** A new instance, seeded with the snapshot the slot's previous instance left when the node keeps the latest content. */
    def start(spec: MountedSpec)(using Frame): MountInstance < Sync =
        Sync.Unsafe.defer {
            spec.node.pendingPolicy match
                case UI.PendingPolicy.Placeholder => Absent
                case UI.PendingPolicy.KeepLatest  => handoff.get().get(spec.path)
        }.map(MountInstance.start(spec, _))

    /** Tears down `instance` and leaves its snapshot at `path` for this pass's successor. */
    def retire(instance: MountInstance, path: Seq[String])(using Frame): Unit < Async =
        instance.snapshot.map { snapshot =>
            Sync.Unsafe.defer(snapshot.foreach(ui => discard(handoff.getAndUpdate(_.update(path, ui)))))
        }.andThen(instance.teardown)

    /** Opens a render pass over `kids`: releases every keyed instance and every region table the pass no longer shows, and waits
      * for all of it. The instances leave the table before they are released, so the release runs out of reach of an interrupt: an
      * outer re-render that interrupts this region's loop ends the wait, not the release.
      */
    def evictExcept(kids: Seq[ReactiveUI], pass: RegionKeys)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            // The pass's keyed nodes by identity, each group in walk order.
            val groups = new java.util.HashMap[MountIdentity, java.util.ArrayList[ReactiveUI]]()
            kids.foreach { kid =>
                kid.mounted.flatMap(_.identity).foreach { identity =>
                    discard(groups.computeIfAbsent(identity, _ => new java.util.ArrayList[ReactiveUI](1)).add(kid))
                }
            }
            val previous  = instances.get()
            val slots     = new java.util.IdentityHashMap[ReactiveUI, MountSlot]()
            var kept      = Dict.empty[MountIdentity, Chunk[MountSlot]]
            var goneSlots = Chunk.empty[MountSlot]
            groups.forEach { (identity, nodes) =>
                val old           = previous.get(identity).getOrElse(Chunk.empty)
                val (taken, left) =
                    if nodes.size == 1 && old.size <= 1 then
                        (if old.isEmpty then Chunk(new MountSlot(nodes.get(0).path)) else old, Chunk.empty)
                    else pair(nodes, old)
                var i = 0
                while i < nodes.size do
                    val slot = taken(i)
                    slot.path = nodes.get(i).path
                    discard(slots.put(nodes.get(i), slot))
                    i += 1
                end while
                kept = kept.update(identity, taken)
                goneSlots = goneSlots.concat(left)
            }
            previous.foreach { (identity, old) =>
                if !groups.containsKey(identity) then goneSlots = goneSlots.concat(old)
            }
            instances.set(kept)
            assigned.set(Present(slots))
            val keepRegions     = if regions.get().isEmpty then Set.empty[RegionKey] else pass.all
            val previousRegions = regions.getAndUpdate(_.filter((key, _) => keepRegions.contains(key)))
            val goneRegions     = previousRegions.foldLeft(Chunk.empty[MountTable]) { (acc, key, table) =>
                if keepRegions.contains(key) then acc else acc.append(table)
            }
            if goneRegions.isEmpty && goneSlots.isEmpty then Kyo.unit
            else
                Async.uninterruptible {
                    Kyo.foreachDiscard(goneRegions)(_.evictAll)
                        .andThen(Kyo.foreachDiscard(goneSlots)(slot => slot.instance.fold(Kyo.unit)(retire(_, slot.path))))
                }
            end if
        }
    end evictExcept

    /** The slots of `old` for the nodes of one identity, in the nodes' order: a slot last shown at a node's path goes to that node,
      * the others in order, and nodes left over get new slots. Also answers the slots no node took.
      */
    private def pair(nodes: java.util.ArrayList[ReactiveUI], old: Chunk[MountSlot]): (Chunk[MountSlot], Chunk[MountSlot]) =
        val free   = scala.collection.mutable.ArrayBuffer.from(old)
        val result = new Array[MountSlot](nodes.size)
        var i      = 0
        while i < nodes.size do
            val at = free.indexWhere(_.path == nodes.get(i).path)
            if at >= 0 then result(i) = free.remove(at)
            i += 1
        end while
        i = 0
        while i < nodes.size do
            if result(i) == null then
                result(i) = if free.nonEmpty then free.remove(0) else new MountSlot(nodes.get(i).path)
            i += 1
        end while
        (Chunk.from(result), Chunk.from(free))
    end pair

    def evictAll(using Frame): Unit < Async = evictExcept(Seq.empty, RegionKeys.empty)

    /** Ends a pass. The slots stay assigned until the next pass, so a node that subscribes late still finds its own. */
    def endWalk()(using AllowUnsafe): Unit =
        handoff.set(Dict.empty)
end MountTable

private[kyo] object MountRegions:

    /** A hash of a call site that reads at most its first 64 characters, where the file, line and column sit. A frame is a long string,
      * and Scala.js does not cache string hashes, so a full hash would cost its whole length on every use.
      */
    private[internal] def siteHash(frame: Frame): Int =
        val s   = frame.toString
        val end = math.min(s.length, 64)
        var h   = s.length
        var i   = 0
        while i < end do
            h = 31 * h + s.charAt(i)
            i += 1
        h
    end siteHash

    /** Subscribes a mounted node in the current scope. A keyed node adopts or creates its instance in the enclosing region's table; a
      * keyless node gets a new instance that ends with the current scope, which for a node inside a region is the region's current
      * value.
      */
    private[internal] def subscribeMounted(
        rui: ReactiveUI,
        spec: MountedSpec,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        mounts: MountTableRef,
        dispatch: MountDispatch
    )(using Frame): Unit < (Async & Scope) =
        val instance = spec.identity match
            case Present(identity) => mounts.claim(rui, identity, spec)
            case Absent            => mounts.startKeyless(spec)
        instance.map(subscribeMountContent(rui, spec, _, exchange, signalChangeTime, dispatch))
    end subscribeMounted

    /** Shows an instance's content cell in the node's range. The content as it is now is painted at once, so a kept instance replaces
      * the placeholder its enclosing region just rendered without a visible gap; then a region over the cell follows it. Live content
      * gets its regions and nested mounts subscribed; a snapshot is only painted.
      */
    private[internal] def subscribeMountContent(
        rui: ReactiveUI,
        spec: MountedSpec,
        instance: MountInstance,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        dispatch: MountDispatch
    )(using Frame): Unit < (Async & Scope) =
        val svgContext = rui.region match
            case _: ReactiveRegion.HtmlRange  => false
            case _: ReactiveRegion.SvgElement => true
        for
            _     <- dispatch.register(rui.path, instance.cell)
            _     <- Scope.ensure(dispatch.unregister(rui.path, instance.cell))
            first <- instance.cell.current
            _     <- first match
                case MountState.Pending(Absent) => Kyo.unit
                case state                      =>
                    exchange.onChange(rui.region, rui.path, rui.contentContext, rui.parentContext, Absent, state.content(spec))
            // Unsafe: single-writer state of this region's own loop, as in subscribeScoped.
            painted     = AtomicRef.Unsafe.init[MountState](first)(using AllowUnsafe.embrace.danger)
            renderState = (state: MountState) =>
                val content = state.content(spec)
                for
                    previous <- Sync.Unsafe.defer(painted.getAndSet(state))
                    _        <-
                        if previous eq state then Kyo.unit
                        else
                            Clock.now.map(signalChangeTime.set).andThen(
                                exchange.onChange(
                                    rui.region,
                                    rui.path,
                                    rui.contentContext,
                                    rui.parentContext,
                                    Present(previous.content(spec)),
                                    content
                                )
                            )
                    _ <- state match
                        case MountState.Pending(Present(_)) => Kyo.unit
                        case _                              =>
                            for
                                (kids, _) <- walkStatic(
                                    content,
                                    rui.path,
                                    svgContext,
                                    rui.contentContext,
                                    nestedParentContext(rui.parentContext, content),
                                    dispatch,
                                    rows = 0L,
                                    deepRows = Chunk.empty
                                )
                                keys = RegionKeys(kids)
                                _ <- instance.children.beginWalk(kids, keys)
                                _ <- subscribeWalked(kids, keys, instance.children, exchange, signalChangeTime, dispatch)
                            yield ()
                yield ()
                end for
            _ <- startOwnedFiber(
                Abort.run[Throwable](instance.cell.observe(renderState)).map { result =>
                    result.fold(
                        _ => (),
                        err => Log.error(s"Mounted content region failed at path=${rui.path.mkString(".")}", err),
                        panic =>
                            if panic.isInstanceOf[Interrupted] then ()
                            else Log.error(s"Mounted content region failed at path=${rui.path.mkString(".")}", panic)
                    )
                }
            )
        yield ()
        end for
    end subscribeMountContent

end MountRegions
