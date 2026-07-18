package kyo

import kyo.Three.foreach
import kyo.Three.foreachKeyed
import kyo.Three.render
import kyo.internal.KeyedEntry
import kyo.internal.Reconciler
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Tests for the [[kyo.internal.Reconciler]]: materialize, keyed diff, dispose cascade, and the
  * identity-keyed live-object map. Every assertion observes a real three.js object property or event;
  * nothing is faked or mocked.
  */
class ReconcilerTest extends ThreeTest:

    "map has exactly one entry per node" in {
        val scene = Three.scene(
            Three.group(
                Three.mesh(Three.Geometry.box(), Three.Material.basic())
            )
        )
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                assert(mounted.live.size == 3)
            }
        }
    }

    "every node in the tree appears in the live map" in {
        val mesh  = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val group = Three.group(mesh)
        val scene = Three.scene(group)
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                val keys = mounted.live.keys.map(_.node).toSet
                assert(keys.contains(scene))
                assert(keys.contains(group))
                assert(keys.contains(mesh))
            }
        }
    }

    "all live obj values are distinct instances" in {
        val scene = Three.scene(
            Three.group(
                Three.mesh(Three.Geometry.box(), Three.Material.basic())
            )
        )
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                val objs = mounted.live.values.map(_.obj).toList
                val distinct = objs.foldLeft(List.empty[sjs.Dynamic]) { (acc, o) =>
                    if acc.exists(x => x eq o) then acc else o :: acc
                }
                assert(distinct.size == objs.size)
            }
        }
    }

    "geometry dispose event fires exactly once on scope close" in {
        var count = 0
        Scope.run {
            ThreeFacadeOps.makeGeometry(Three.Geometry.box()).map { geomObj =>
                Sync.Unsafe.defer {
                    geomObj.addEventListener("dispose", (_: sjs.Any) => count += 1)
                }
            }
        }.map { _ =>
            assert(count == 1)
        }
    }

    "material dispose event fires exactly once on scope close" in {
        var count = 0
        Scope.run {
            ThreeFacadeOps.makeMaterial(Three.Material.basic()).map { matObj =>
                Sync.Unsafe.defer {
                    matObj.addEventListener("dispose", (_: sjs.Any) => count += 1)
                }
            }
        }.map { _ =>
            assert(count == 1)
        }
    }

    "each GL resource dispose fires once and only once per scope" in {
        var geomCount = 0
        var matCount  = 0
        Scope.run {
            for
                geomObj <- ThreeFacadeOps.makeGeometry(Three.Geometry.sphere())
                matObj  <- ThreeFacadeOps.makeMaterial(Three.Material.standard())
                _ <- Sync.Unsafe.defer {
                    geomObj.addEventListener("dispose", (_: sjs.Any) => geomCount += 1)
                    matObj.addEventListener("dispose", (_: sjs.Any) => matCount += 1)
                }
            yield ()
        }.map { _ =>
            assert(geomCount == 1)
            assert(matCount == 1)
        }
    }

    "keyed diff reuses live nodes on reorder by reference identity" in {
        val nodeA = Three.mesh(Three.Geometry.box(1, 1, 1), Three.Material.basic())
        val nodeB = Three.mesh(Three.Geometry.box(2, 2, 2), Three.Material.basic())
        val nodeC = Three.mesh(Three.Geometry.box(3, 3, 3), Three.Material.basic())
        Scope.run {
            val mounted = new Reconciler.Mounted
            for
                first <- Reconciler.diffKeyed(
                    Chunk.empty,
                    Map.empty,
                    Chunk(
                        KeyedEntry("a", "a", nodeA),
                        KeyedEntry("b", "b", nodeB),
                        KeyedEntry("c", "c", nodeC)
                    ),
                    mounted
                )
                prevKeyed = Chunk.from(List(("a", first.lives(0)), ("b", first.lives(1)), ("c", first.lives(2))))
                prevItems = Map[String, Any]("a" -> "a", "b" -> "b", "c" -> "c")
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    prevItems,
                    Chunk(
                        KeyedEntry("c", "c", nodeC),
                        KeyedEntry("a", "a", nodeA),
                        KeyedEntry("b", "b", nodeB)
                    ),
                    mounted
                )
            yield
                assert(second.lives(0).obj eq first.lives(2).obj)
                assert(second.lives(1).obj eq first.lives(0).obj)
                assert(second.lives(2).obj eq first.lives(1).obj)
            end for
        }
    }

    "keyed diff rebuilds a surviving key whose item changed, and reuses one whose item did not" in {
        // A key surviving into the next emission is NOT on its own a reason to keep its live object: the
        // subtree was rendered from the ITEM, so an item that moved must re-render. Only an unchanged item
        // may reuse (that is what keeps GPU buffers alive across a reorder). Without this, a keyed foreach
        // driven by a feed that changes values but not identities (every server-pushed chart) renders its
        // first snapshot and then never moves again.
        val redA    = Three.mesh(Three.Geometry.box(1, 1, 1), Three.Material.basic().color(Three.Color(0xff0000)))
        val yellowA = Three.mesh(Three.Geometry.box(1, 1, 1), Three.Material.basic().color(Three.Color(0xffff00)))
        val nodeB   = Three.mesh(Three.Geometry.box(2, 2, 2), Three.Material.basic())
        Scope.run {
            val mounted = new Reconciler.Mounted
            for
                first <- Reconciler.diffKeyed(
                    Chunk.empty,
                    Map.empty,
                    Chunk(KeyedEntry("a", "red", redA), KeyedEntry("b", "steady", nodeB)),
                    mounted
                )
                prevKeyed = Chunk.from(List(("a", first.lives(0)), ("b", first.lives(1))))
                prevItems = Map[String, Any]("a" -> "red", "b" -> "steady")
                // Key "a" survives carrying a NEW item; key "b" survives carrying the SAME item.
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    prevItems,
                    Chunk(KeyedEntry("a", "yellow", yellowA), KeyedEntry("b", "steady", nodeB)),
                    mounted
                )
            yield
                assert(
                    !(second.lives(0).obj eq first.lives(0).obj),
                    "key 'a' carries a new item, so its live object must be rebuilt rather than carried over stale"
                )
                assert(
                    second.lives(0).node eq yellowA,
                    "the rebuilt live object for key 'a' must bind the node the NEW item rendered to"
                )
                assert(
                    second.lives(1).obj eq first.lives(1).obj,
                    "key 'b' carries an unchanged item, so its live object (and its GPU buffers) must be reused"
                )
            end for
        }
    }

    "keyed diff removes a key: removed entry absent from result" in {
        val nodeA = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val nodeB = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        Scope.run {
            val mounted = new Reconciler.Mounted
            for
                first <- Reconciler.diffKeyed(
                    Chunk.empty,
                    Map.empty,
                    Chunk(KeyedEntry("a", "a", nodeA), KeyedEntry("b", "b", nodeB)),
                    mounted
                )
                prevKeyed = Chunk.from(List(("a", first.lives(0)), ("b", first.lives(1))))
                prevItems = Map[String, Any]("a" -> "a", "b" -> "b")
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    prevItems,
                    Chunk(KeyedEntry("a", "a", nodeA)),
                    mounted
                )
            yield
                assert(second.lives.size == 1)
                assert(second.lives(0).obj eq first.lives(0).obj)
            end for
        }
    }

    "keyed diff with a duplicate key has defined behavior: both slots materialize, then the remembered item decides reuse" in {
        val nodeA = Three.mesh(Three.Geometry.box(1, 1, 1), Three.Material.basic())
        val nodeB = Three.mesh(Three.Geometry.box(2, 2, 2), Three.Material.basic())
        Scope.run {
            val mounted = new Reconciler.Mounted
            for
                // Two entries share the key "dup": the first diff materializes both as distinct live
                // objects (no silent collapse, no corrupt map), so the result is a stable 2-element list.
                first <- Reconciler.diffKeyed(
                    Chunk.empty,
                    Map.empty,
                    Chunk(KeyedEntry("dup", "first", nodeA), KeyedEntry("dup", "second", nodeB)),
                    mounted
                )
                // Feed the prior keyed list (two "dup" entries) back in. Both the prev-by-key live map and
                // the remembered item are last-wins, so the slot carrying the remembered item ("second")
                // reuses the last prior live object, while the slot carrying the other item ("first") reads
                // as a changed item and rebuilds. A duplicate key is a caller error; what matters here is
                // that the behavior is defined and total: both slots resolve, nothing throws, the map stays
                // stable, and no live object is dropped without disposal.
                prevKeyed = Chunk.from(List(("dup", first.lives(0)), ("dup", first.lives(1))))
                prevItems = Map[String, Any]("dup" -> "second")
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    prevItems,
                    Chunk(KeyedEntry("dup", "first", nodeA), KeyedEntry("dup", "second", nodeB)),
                    mounted
                )
            yield
                assert(first.lives.size == 2, s"a duplicate key must still materialize both slots, got ${first.lives.size}")
                assert(!(first.lives(0).obj eq first.lives(1).obj), "the two duplicate-key slots must be distinct live objects")
                assert(second.lives.size == 2, s"the re-diff must keep both slots, got ${second.lives.size}")
                assert(
                    second.lives(1).obj eq first.lives(1).obj,
                    "the slot carrying the remembered (last-wins) item must reuse the last prior live object"
                )
                assert(
                    !(second.lives(0).obj eq first.lives(0).obj) && !(second.lives(0).obj eq first.lives(1).obj),
                    "the slot whose item is not the remembered one reads as changed and must materialize fresh"
                )
                // The orphan. Only the LAST prior live object for "dup" is carried forward, so the FIRST one
                // is dropped from the holder by the relink. It must therefore be RETIRED, or it would be
                // detached and never disposed and never retired from the identity map: a leak that retiring
                // by key rather than by live identity would hide, because the key "dup" does survive.
                assert(
                    second.retired.exists(_ eq first.lives(0)),
                    "the duplicate's dropped live object must be retired for disposal, not spared because its KEY survived"
                )
                assert(
                    !second.retired.exists(_ eq first.lives(1)),
                    "the duplicate's carried-forward live object must NOT be retired"
                )
            end for
        }
    }

    "positional materialize of identical empty groups yields distinct live objects" in {
        val groupA = Three.empty
        val groupB = Three.empty
        val scene  = Three.scene(groupA, groupB)
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                val liveA = mounted.live.get(new Reconciler.IdentityKey(groupA))
                val liveB = mounted.live.get(new Reconciler.IdentityKey(groupB))
                assert(liveA.isDefined)
                assert(liveB.isDefined)
                assert(!(liveA.get.obj eq liveB.get.obj))
            }
        }
    }

    "custom node materializes using the user build function" in {
        Scope.run {
            for
                myObj <- Sync.Unsafe.defer {
                    import scala.scalajs.js.Dynamic.literal
                    literal(customMark = true): sjs.Dynamic
                }
                customNode = Three.custom[Unit](_ => myObj)(())
                result <- Reconciler.mount(customNode)
                (live, _) = result
                isEq <- Sync.Unsafe.defer(live.obj eq myObj)
            yield assert(isEq)
        }
    }

    "sibling distinct identity: two Three.empty siblings map to distinct live entries" in {
        val a     = Three.empty
        val b     = Three.empty
        val scene = Three.scene(a, b)
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                val liveA = mounted.live.get(new Reconciler.IdentityKey(a))
                val liveB = mounted.live.get(new Reconciler.IdentityKey(b))
                assert(liveA.isDefined)
                assert(liveB.isDefined)
                assert(!(liveA.get.obj eq liveB.get.obj))
            }
        }
    }

    "reactive node materializes as a holder Group in the live map" in {
        val sig      = Signal.initConst(Three.empty: Three)
        val reactive = Three.reactive(sig)
        val scene    = Three.scene(reactive)
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                val liveR = mounted.live.get(new Reconciler.IdentityKey(reactive))
                assert(liveR.isDefined)
            }
        }
    }

    "a raw Three.reactive(Signal.initConst(...)) region does not churn: runReactiveRegion materializes it exactly once" in {
        // Signal.initConst.current returns the value; its next parks (a constant has no next value), so
        // watchDistinct materializes once on its first read and then parks on the dedup branch's next.
        // This region therefore materializes exactly once no matter how many scheduler turns the watcher
        // fiber gets; a bare Loop.foreach over signal.next would instead re-fire step on every wakeup.
        val leaf  = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val node  = Three.reactive(Signal.initConst(leaf: Three))
        val scene = Three.scene(node)
        for
            matCount     <- AtomicInt.init
            materialized <- Channel.initUnscoped[Unit](1)
            fiber <- Fiber.initUnscoped(
                Scope.run {
                    for
                        mountResult <- Reconciler.mount(scene)
                        (_, mounted) = mountResult
                        _ = mounted.subscribeElement = _ =>
                            matCount.incrementAndGet.andThen(Abort.run[Closed](materialized.offer(())).unit)
                        region = Reconciler.reactiveRegions(mounted).head
                        _ <- Reconciler.runReactiveRegion(region, mounted)
                    yield ()
                }
            )
            // The first materialize always happens (a reactive region renders its initial value): wait for it.
            _          <- Abort.run[Closed](materialized.take)
            afterFirst <- matCount.get
            // Bounded scheduler-yield sweep (mirrors ThreeMountTest's "interrupt cascades" leaf): give the
            // still-running watcher 50 real scheduler turns BEFORE interrupting it. A deduped watcher never
            // materializes again no matter how many turns it gets; an un-deduped watcher consumes every one
            // of them to re-dispose/re-materialize, so the count keeps climbing.
            grew <- Loop.indexed { i =>
                if i >= 50 then Loop.done(false)
                else
                    Fiber.initUnscoped(Kyo.unit).map(_.get).andThen {
                        matCount.get.map(n => if n > afterFirst then Loop.done(true) else Loop.continue)
                    }
            }
            finalCount <- matCount.get
            _          <- fiber.interrupt
        yield
            assert(afterFirst == 1, s"the raw const-driven region must materialize exactly once, got $afterFirst")
            assert(!grew, "the raw const-driven region must not keep re-materializing across further scheduler turns")
            assert(finalCount == 1, s"a const-driven raw reactive must never churn past its first materialize, got $finalCount")
        end for
    }

    "a const-driven raw reactive parks its watcher after the first reconcile (no next-spin)" in {
        // The churn leaf above counts materializes, which fire once whether the watcher parks or spins, so
        // it cannot see a spin. This leaf makes the parking observable: it wraps a real initConst in a
        // counting delegate whose currentWith/nextWith increment counters before delegating, so the const's
        // parked next is the parking under test. A parked watcher calls next exactly once and reads exactly
        // three times: the fill reads the driving value it records, the fill reads the subtree it renders,
        // and the watcher reads once, finds the value it was seeded with, and parks. A spinning watcher
        // would drive both counters up on every scheduler turn.
        val leaf = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        for
            reads        <- AtomicInt.init
            nexts        <- AtomicInt.init
            materialized <- Channel.initUnscoped[Unit](1)
            counting =
                val inner = Signal.initConst(leaf: Three)
                Signal.initRaw[Three](
                    currentWith = [B, S] => f => reads.incrementAndGet.andThen(inner.currentWith(f)),
                    nextWith = [B, S] => f => nexts.incrementAndGet.andThen(inner.nextWith(f))
                )
            scene = Three.scene(Three.reactive(counting))
            fiber <- Fiber.initUnscoped(
                Scope.run {
                    for
                        mountResult <- Reconciler.mount(scene)
                        (_, mounted) = mountResult
                        _            = mounted.subscribeElement = _ => Abort.run[Closed](materialized.offer(())).unit
                        _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                        region = Reconciler.reactiveRegions(mounted).head
                        _ <- Reconciler.runReactiveRegion(region, mounted)
                    yield ()
                }
            )
            // Wait for the first materialize, then give the watcher scheduler turns until its single dedup-
            // branch next registers (proof it reached its park point). Both waits are bounded, no sleep.
            _ <- Abort.run[Closed](materialized.take)
            _ <- Loop.indexed { i =>
                if i >= 50 then Loop.done(())
                else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(nexts.get.map(n => if n >= 1 then Loop.done(()) else Loop.continue))
            }
            // 50 more scheduler turns: a parked watcher's counters stay frozen; a spinning one climbs.
            grew <- Loop.indexed { i =>
                if i >= 50 then Loop.done(false)
                else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(nexts.get.map(n => if n > 1 then Loop.done(true) else Loop.continue))
            }
            finalNexts <- nexts.get
            finalReads <- reads.get
            _          <- fiber.interrupt
        yield
            assert(!grew, "a const-driven watcher must park on next, never spin across further scheduler turns")
            assert(finalNexts == 1, s"the parked watcher calls next exactly once, got $finalNexts")
            assert(
                finalReads == 3,
                s"reads freeze at 3 (the fill's driver read, the fill's render read, the watcher's one read), got $finalReads"
            )
        end for
    }

    "nested group map: all nodes appear at every depth" in {
        val mesh  = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val inner = Three.group(mesh)
        val outer = Three.group(inner)
        val scene = Three.scene(outer)
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                assert(mounted.live.size == 4)
                assert(mounted.live.get(new Reconciler.IdentityKey(scene)).isDefined)
                assert(mounted.live.get(new Reconciler.IdentityKey(outer)).isDefined)
                assert(mounted.live.get(new Reconciler.IdentityKey(inner)).isDefined)
                assert(mounted.live.get(new Reconciler.IdentityKey(mesh)).isDefined)
            }
        }
    }

    // ---- Wire-driven seams: regionFor / holderPath / foreachReplace / reactiveReplace ----
    // ThreeBackend's buildPathIndex (kyo-threejs/shared/src/main/scala/kyo/internal/ThreeBackend.scala)
    // owns the ACTUAL byPath/byLive maps and stamps ReactiveRegion.holderPath; these leaves cover what
    // Reconciler itself owns: the holderPath field + regionFor lookup, and the reindexRegion hook's
    // before/after-overwrite contract foreachReplace/reactiveReplace drive it through.

    "regionFor finds the region stamped with a given holderPath, and Absent for an unstamped path" in {
        val sig   = Signal.initConst(Chunk("a", "b"))
        val fe    = sig.foreachKeyed(identity)(k => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val scene = Three.scene(fe)
        Scope.run {
            for
                (_, mounted) <- Reconciler.mount(scene)
                _            <- Reconciler.fillReactiveRegionsOnce(mounted)
            yield
                val region = Reconciler.reactiveRegions(mounted).head
                // Simulates ThreeBackend.buildPathIndex stamping the holder's own data-kyo-path (a
                // parent's path :+ its own child index); Reconciler itself never writes holderPath.
                region.holderPath = Seq("0")
                assert(Reconciler.regionFor(mounted, Seq("0")).exists(_ eq region))
                assert(Reconciler.regionFor(mounted, Seq("does-not-exist")).isEmpty)
        }
    }

    "foreachReplace calls reindexRegion with the OLD keyed set (still intact) before overwriting prevKeyed with the NEW one" in {
        val sig                    = Signal.initConst(Chunk("a", "b"))
        val fe                     = sig.foreachKeyed(identity)(k => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val scene                  = Three.scene(fe)
        var hookOld: Chunk[String] = Chunk.empty
        var hookNew: Chunk[String] = Chunk.empty
        Scope.run {
            for
                (_, mounted) <- Reconciler.mount(scene)
                _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                region = Reconciler.reactiveRegions(mounted).head
                _ = mounted.reindexRegion = (r, keyed) =>
                    hookOld = r.prevKeyed.map(_._1); hookNew = keyed.map(_._1)
                nextMesh: Three = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                _ <- Reconciler.foreachReplace(region, Chunk(KeyedEntry("a", "a", nextMesh)), mounted)
            yield
                assert(hookOld == Chunk("a", "b")) // the prior keyed set, read from region.prevKeyed BEFORE this call overwrites it
                assert(hookNew == Chunk("a"))      // "b" spliced out, "a" carried forward
                assert(region.prevKeyed.map(_._1) == Chunk("a")) // overwritten AFTER the hook ran
        }
    }

    "foreachReplace RELINKS the holder before it disposes anything, so no frame can render a disposed-but-attached object" in {
        // The order is the guard, because the race itself cannot be caught deterministically. A retired
        // object stays attached to the holder until the relink detaches it, and the reconcile does not own
        // the event loop: it runs on the mount's drain fiber, the frame loop runs on another, a dispose
        // awaits its element scope's finalizers, and kyo's JS scheduler resumes tasks through a macrotask,
        // which is exactly where the browser runs animation frames. So disposing before the relink lets a
        // frame render an object whose GPU resources are gone. Nothing looks broken when it does, which is
        // why only the ORDER can be asserted: three.js keeps the CPU-side attribute arrays, so the frame
        // silently re-uploads them and orphans the new buffers against a closed scope.
        val sig                = Signal.initConst(Chunk("a"))
        val fe                 = sig.foreachKeyed(identity)(k => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val scene              = Three.scene(fe)
        var log: Chunk[String] = Chunk.empty
        Scope.run {
            for
                (_, mounted) <- Reconciler.mount(scene)
                _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                region = Reconciler.reactiveRegions(mounted).head
                // The live object about to be retired: key "a" survives, but its ITEM changes, so it is
                // rebuilt. Listen for its geometry's dispose, and record the relink through the hook the
                // reconciler fires from inside replaceHolderChildren.
                retiring = region.prevKeyed(0)._2
                _ = retiring.obj.geometry.asInstanceOf[sjs.Dynamic]
                    .addEventListener("dispose", (_: sjs.Any) => log = log.append("dispose"))
                _               = mounted.reindexRegion = (_, _) => log = log.append("relink")
                nextMesh: Three = Three.mesh(Three.Geometry.box(2, 2, 2), Three.Material.basic())
                // Same key, NEW item: the surviving key is rebuilt, so its prior live object is retired.
                _ <- Reconciler.foreachReplace(region, Chunk(KeyedEntry("a", "moved", nextMesh)), mounted)
            yield assert(
                log == Chunk("relink", "dispose"),
                s"the holder must be relinked BEFORE the retired object is disposed, so no frame can render it " +
                    s"detached from its GPU resources; got $log"
            )
        }
    }

    "foreachReplace disposes a duplicate key's orphaned live object exactly once" in {
        // The duplicate's FIRST live object is not carried forward (the reuse lookup is last-wins), so the
        // relink detaches it. Retiring by KEY would spare it, because the key "dup" does survive, and it
        // would then be detached, never disposed, and never retired from the identity map. Retiring by LIVE
        // IDENTITY disposes exactly the objects that are not carried.
        val sig       = Signal.initConst(Chunk("first", "second"))
        val fe        = sig.foreachKeyed(_ => "dup")(item => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val scene     = Three.scene(fe)
        var disposals = 0
        Scope.run {
            for
                (_, mounted) <- Reconciler.mount(scene)
                _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                region = Reconciler.reactiveRegions(mounted).head
                // Both slots materialized under the one key; the FIRST is the orphan the reuse lookup drops.
                orphan   = region.prevKeyed(0)._2
                survivor = region.prevKeyed(1)._2
                _ = orphan.obj.geometry.asInstanceOf[sjs.Dynamic]
                    .addEventListener("dispose", (_: sjs.Any) => disposals += 1)
                // Re-feed the SAME items, so the remembered (last-wins) item still reuses the survivor.
                nextMesh: Three = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                _ <- Reconciler.foreachReplace(region, Chunk(KeyedEntry("dup", "second", nextMesh)), mounted)
            yield
                assert(
                    region.prevKeyed.map(_._2).exists(_ eq survivor),
                    "the carried-forward (last-wins) live object must survive the reconcile"
                )
                assert(
                    disposals == 1,
                    s"the duplicate's orphaned live object must be disposed EXACTLY once, not leaked because its key " +
                        s"survived; got $disposals disposal(s)"
                )
        }
    }

    "reactiveReplace's resulting prevKeyed is always the literal single \"reactive\" key: render is path-transparent, never holderPath-derived" in {
        val sig   = Signal.initConst(Three.empty: Three)
        val node  = Three.reactive(sig)
        val scene = Three.scene(node)
        Scope.run {
            for
                (_, mounted) <- Reconciler.mount(scene)
                _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                region          = Reconciler.reactiveRegions(mounted).head
                _               = region.holderPath = Seq("0") // an arbitrary stamped path; must not leak into the key
                nextNode: Three = Three.group(Three.empty)
                _ <- Reconciler.reactiveReplace(region, nextNode, mounted)
            yield
                assert(region.prevKeyed.map(_._1) == Chunk("reactive"))
                assert(region.prevKeyed.size == 1)
        }
    }

    // The count of live objects hanging off a holder, read straight from the three.js object.
    private def childCount(obj: sjs.Dynamic)(using AllowUnsafe): Int =
        obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length

    "a foreach NESTED inside a when renders: a region materialized inside a region is still a region" in {
        // The composition is the most natural thing in the API (show a list when a flag is on), and it used
        // to render NOTHING, forever, with no error and no log. Regions were discovered once, from the live
        // map, at the moment the first fill began; a Reactive/Foreach inside a region's CONTENT materializes
        // DURING that fill, so it landed in the live map after the region list was already frozen. Nothing
        // watched it, nothing filled it, and its holder stayed an empty Group.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    visible <- Signal.initRef(true)
                    items   <- Signal.initRef(Chunk(1, 2, 3))
                    inner = items.foreach(_ => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
                    outer = Three.when(visible)(inner)
                    (root, mounted) <- Reconciler.mount(Three.scene(outer))
                    _               <- Reconciler.fillReactiveRegionsOnce(mounted)
                    counts <- Sync.Unsafe.defer {
                        val outerHolder = root.children(0).obj
                        val innerHolder = outerHolder.children.asInstanceOf[sjs.Array[sjs.Dynamic]](0)
                        (childCount(outerHolder), childCount(innerHolder))
                    }
                yield
                    assert(counts._1 == 1, "the when's holder carries the foreach's holder")
                    assert(
                        counts._2 == 3,
                        "the nested foreach must render its three items; an empty holder means the region rendered nothing at all"
                    )
                end for
            }
        }
    }

    "a nested region keeps WATCHING: a change to the inner signal re-renders it after the outer region filled it" in {
        // Filling the nested region once is not enough. It must also be subscribed, or it renders the first
        // value and then freezes while the signal moves on, which is the same invisible failure one step later.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    visible <- Signal.initRef(true)
                    items   <- Signal.initRef(Chunk(1, 2))
                    inner = items.foreach(_ => Three.mesh(Three.Geometry.box(), Three.Material.basic()))
                    outer = Three.when(visible)(inner)
                    (root, mounted) <- Reconciler.mount(Three.scene(outer))
                    _               <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // The nested region is discoverable from the mount, which is what makes it watchable.
                    innerRegion <- Sync.defer(Maybe.fromOption(Reconciler.reactiveRegions(mounted).find(_.node eq inner)))
                    _           <- items.set(Chunk(1, 2, 3, 4))
                    // Drive the region the way the mount's watcher fiber does, from the signal's new value.
                    _ <- innerRegion match
                        case Present(r) => Reconciler.fillRegionOnce(r, mounted)
                        case Absent     => Kyo.unit
                    after <- Sync.Unsafe.defer {
                        val outerHolder = root.children(0).obj
                        childCount(outerHolder.children.asInstanceOf[sjs.Array[sjs.Dynamic]](0))
                    }
                yield
                    assert(innerRegion.nonEmpty, "the nested foreach must BE a region, or nothing can ever watch it")
                    assert(after == 4, "the nested region must re-render on its own signal's change")
                end for
            }
        }
    }

    "a reactive NESTED inside a foreach item renders, and the reverse nesting works too" in {
        // The mirror of the leaf above: the outer region is the Foreach and the inner one is a Reactive. Both
        // orders go through the same registration seam, so both are covered or neither is.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    on    <- Signal.initRef(true)
                    items <- Signal.initRef(Chunk("a", "b"))
                    outer = items.foreachKeyed(identity) { _ =>
                        Three.group(Three.when(on)(Three.mesh(Three.Geometry.box(), Three.Material.basic())))
                    }
                    (root, mounted) <- Reconciler.mount(Three.scene(outer))
                    _               <- Reconciler.fillReactiveRegionsOnce(mounted)
                    counts <- Sync.Unsafe.defer {
                        val foreachHolder = root.children(0).obj
                        val groups        = foreachHolder.children.asInstanceOf[sjs.Array[sjs.Dynamic]]
                        // Each item renders a group whose single child is the when's holder; that holder must
                        // carry the mesh the `when` shows.
                        val perItem = groups.toArray.map { g =>
                            val whenHolder = g.children.asInstanceOf[sjs.Array[sjs.Dynamic]](0)
                            childCount(whenHolder)
                        }
                        (groups.length, Chunk.from(perItem))
                    }
                yield
                    assert(counts._1 == 2, "the foreach renders one group per item")
                    assert(
                        counts._2 == Chunk(1, 1),
                        "each item's nested `when` must render its mesh; an empty holder means that item shows nothing"
                    )
                end for
            }
        }
    }

    "the startup fill and the region's watcher do not BOTH reconcile the same value" in {
        // A region's watcher must start from the value its fill already reconciled. A watcher that starts
        // from no value reconciles that same value again on its first iteration, so every Reactive region
        // materializes its whole subtree twice at startup and throws one away. With a region nested inside
        // another region that doubling COMPOUNDS, because each redundant re-materialize rebuilds and
        // re-fills everything underneath it, once per level. Seeded, the watcher parks until the driving
        // value genuinely changes.
        val leaf  = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val node  = Three.reactive(Signal.initConst(leaf: Three))
        val scene = Three.scene(node)
        for
            matCount <- AtomicInt.init
            fiber <- Fiber.initUnscoped(
                Scope.run {
                    for
                        mountResult <- Reconciler.mount(scene)
                        (_, mounted) = mountResult
                        _            = mounted.subscribeElement = _ => matCount.incrementAndGet.unit
                        _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                        region = Reconciler.reactiveRegions(mounted).head
                        _ <- Reconciler.runReactiveRegion(region, mounted)
                    yield ()
                }
            )
            // Give the watcher 50 real scheduler turns to take its first iteration. A seeded watcher parks
            // on the dedup branch; an unseeded one re-materializes on the very first turn.
            grew <- Loop.indexed { i =>
                if i >= 50 then Loop.done(false)
                else
                    Fiber.initUnscoped(Kyo.unit).map(_.get).andThen {
                        matCount.get.map(n => if n > 1 then Loop.done(true) else Loop.continue)
                    }
            }
            count <- matCount.get
            _     <- fiber.interrupt
        yield
            assert(!grew, "the watcher must not re-reconcile the value the fill already reconciled")
            assert(count == 1, s"the region must materialize exactly once at startup, not once per starter, got $count")
        end for
    }

    "disposing an element RETIRES the region nested inside it, and its content leaves the live map" in {
        // A region holder's real children live in `region.prevKeyed`, not in `Live.children`, so a dispose
        // walk that only followed `Live.children` would stop at the holder and leave everything under it in
        // the live map forever. That map is what `ThreeMount.onFrameClosures` and
        // `Raycasting.interactiveTargets` enumerate, so the leak is not inert: a disposed mesh's `onFrame`
        // would keep running every frame and a click could still hit an object that left the scene.
        // Retiring the region matters just as much: `regionFor` matches on `holderPath`, and a dead region
        // would shadow the fresh one that takes the same path.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    on    <- Signal.initRef(true)
                    items <- Signal.initRef(Chunk("a"))
                    nestedMesh = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                    inner      = Three.when(on)(nestedMesh)
                    outer      = items.foreachKeyed(identity)(_ => Three.group(inner))
                    (_, mounted) <- Reconciler.mount(Three.scene(outer))
                    _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                    before <- Sync.Unsafe.defer {
                        (
                            mounted.live.contains(new Reconciler.IdentityKey(nestedMesh)),
                            Reconciler.reactiveRegions(mounted).exists(_.node eq inner)
                        )
                    }
                    // Drop the only item: the group holding the nested region is disposed.
                    _ <- items.set(Chunk.empty[String])
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    after <- Sync.Unsafe.defer {
                        (
                            mounted.live.contains(new Reconciler.IdentityKey(nestedMesh)),
                            Reconciler.reactiveRegions(mounted).exists(_.node eq inner)
                        )
                    }
                yield
                    assert(before == (true, true), "before the removal the nested mesh is live and its region is registered")
                    assert(
                        after == (false, false),
                        "disposing the element must take the nested region's content out of the live map AND retire the region"
                    )
                end for
            }
        }
    }

    "a node value rendered into two consecutive emissions keeps the live entry of the object actually on screen" in {
        // The live map is keyed by AST node IDENTITY. A node hoisted into a `val` and rendered into two
        // consecutive emissions is materialized twice under ONE key, and the reconcile relinks the fresh
        // object BEFORE it disposes the prior one (it must: disposing first would leave the scene holding an
        // object whose GPU buffers are gone). So at dispose time the key already points at the object that
        // is on screen, and a dispose that removed it by key alone would evict the LIVE object's entry: the
        // mount would stop patching its props, stop running its onFrame, and stop raycasting it, while it
        // sits there in plain sight.
        val shared = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val extra  = Three.mesh(Three.Geometry.sphere(), Three.Material.basic())
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    n <- Signal.initRef(1)
                    region = n.render(v => if v == 1 then Three.group(shared) else Three.group(shared, extra))
                    (root, mounted) <- Reconciler.mount(Three.scene(region))
                    _               <- Reconciler.fillReactiveRegionsOnce(mounted)
                    _               <- n.set(2)
                    _               <- Reconciler.fillReactiveRegionsOnce(mounted)
                    state <- Sync.Unsafe.defer {
                        val entry          = Maybe.fromOption(mounted.live.get(new Reconciler.IdentityKey(shared)))
                        val holder         = root.children(0).obj
                        val group          = holder.children.asInstanceOf[sjs.Array[sjs.Dynamic]](0)
                        val onScreen       = group.children.asInstanceOf[sjs.Array[sjs.Dynamic]]
                        val liveIsOnScreen = entry.exists(l => onScreen.exists(_ eq l.obj))
                        (entry.nonEmpty, liveIsOnScreen)
                    }
                yield
                    assert(state._1, "the re-rendered node must still have a live entry after the prior one is disposed")
                    assert(state._2, "and that entry must be the object currently attached to the scene, not the disposed one")
                end for
            }
        }
    }

    "a RETIRED region's watcher does not reconcile: it stops instead of resurrecting the element it belonged to" in {
        // The twin of the fill-pass skip, on the path that races instead of the one that iterates. A nested
        // region's watcher is forked under its element's scope, so disposing the element interrupts it, but
        // interruption is ASYNCHRONOUS: if the region's signal emits in the window between the disposal and
        // the interrupt landing, the watcher wakes and reconciles a region that is no longer in the scene. It
        // would materialize into a detached holder, put the dead objects back into `mounted.live` (where
        // `onFrameClosures` runs a disposed mesh every frame and `Raycasting.interactiveTargets` can still hit
        // it), and register the fresh GL resources for release on an element scope that is already closed,
        // which is a no-op: those buffers would never be freed. This drives that window directly by retiring
        // the region, changing its driver, and then running the watcher.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    on    <- Signal.initRef(true)
                    items <- Signal.initRef(Chunk("a"))
                    inner = Three.when(on)(Three.mesh(Three.Geometry.box(), Three.Material.basic()))
                    outer = items.foreachKeyed(identity)(_ => Three.group(inner))
                    (_, mounted) <- Reconciler.mount(Three.scene(outer))
                    _            <- Reconciler.fillReactiveRegionsOnce(mounted)
                    innerRegion  <- Sync.defer(Maybe.fromOption(Reconciler.reactiveRegions(mounted).find(_.node eq inner)))
                    // Drop the item: the element holding the nested region is disposed, so the region retires.
                    _       <- items.set(Chunk.empty[String])
                    _       <- Reconciler.fillReactiveRegionsOnce(mounted)
                    retired <- Sync.defer(Reconciler.reactiveRegions(mounted).exists(_.node eq inner))
                    before  <- Sync.Unsafe.defer(mounted.live.size)
                    // The retired region's DRIVER changes, which is exactly what would wake its watcher.
                    _ <- on.set(false)
                    // Run the watcher the way the mount forks it. With the guard it observes the retirement and
                    // ends; without it, it reconciles and puts fresh objects back into the live map.
                    fiber <- Fiber.initUnscoped(
                        innerRegion match
                            case Present(r) => Abort.run[ThreeException](Reconciler.runReactiveRegion(r, mounted)).unit
                            case Absent     => Kyo.unit
                    )
                    // 50 real scheduler turns: enough for a watcher that intends to step to have stepped.
                    _ <- Loop.indexed { i =>
                        if i >= 50 then Loop.done(())
                        else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(Loop.continue)
                    }
                    after <- Sync.Unsafe.defer(mounted.live.size)
                    _     <- fiber.interrupt
                yield
                    assert(innerRegion.nonEmpty, "the nested region must exist before the element is dropped")
                    assert(!retired, "dropping the item must retire the region nested inside it")
                    assert(
                        after == before,
                        s"a retired region's watcher must not materialize anything: live map went $before -> $after"
                    )
                end for
            }
        }
    }

    "materializes a controls node into the live map" in {
        val controls = Three.controls(autoRotate = true)
        val scene = Three.scene(
            Three.mesh(Three.Geometry.box(), Three.Material.basic()),
            controls
        )
        Scope.run {
            Reconciler.mount(scene).map { case (_, mounted) =>
                // scene + mesh + controls = 3 live entries; the controls node is recorded (as a holder)
                // so the mount pipeline can find it to bind a live OrbitControls.
                assert(mounted.live.size == 3)
                val nodes = mounted.live.keys.map(_.node).toSet
                assert(nodes.contains(controls))
            }
        }
    }

end ReconcilerTest
