package kyo

import kyo.Three.foreachKeyed
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
                    Chunk.from(List(("a", nodeA: Three), ("b", nodeB: Three), ("c", nodeC: Three))),
                    mounted
                )
                prevKeyed = Chunk.from(List(("a", first(0)), ("b", first(1)), ("c", first(2))))
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    Chunk.from(List(("c", nodeC: Three), ("a", nodeA: Three), ("b", nodeB: Three))),
                    mounted
                )
            yield
                assert(second(0).obj eq first(2).obj)
                assert(second(1).obj eq first(0).obj)
                assert(second(2).obj eq first(1).obj)
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
                    Chunk.from(List(("a", nodeA: Three), ("b", nodeB: Three))),
                    mounted
                )
                prevKeyed = Chunk.from(List(("a", first(0)), ("b", first(1))))
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    Chunk.from(List(("a", nodeA: Three))),
                    mounted
                )
            yield
                assert(second.size == 1)
                assert(second(0).obj eq first(0).obj)
            end for
        }
    }

    "keyed diff with a duplicate key has defined behavior: both slots materialize, then last-wins on reuse" in {
        val nodeA = Three.mesh(Three.Geometry.box(1, 1, 1), Three.Material.basic())
        val nodeB = Three.mesh(Three.Geometry.box(2, 2, 2), Three.Material.basic())
        Scope.run {
            val mounted = new Reconciler.Mounted
            for
                // Two entries share the key "dup": the first diff materializes both as distinct live
                // objects (no silent collapse, no corrupt map), so the result is a stable 2-element list.
                first <- Reconciler.diffKeyed(
                    Chunk.empty,
                    Chunk.from(List(("dup", nodeA: Three), ("dup", nodeB: Three))),
                    mounted
                )
                // Feed the prior keyed list (two "dup" entries) back in. The prev-by-key map is last-wins,
                // so both next slots reuse the LAST prior live object; nothing throws and the map stays stable.
                prevKeyed = Chunk.from(List(("dup", first(0)), ("dup", first(1))))
                second <- Reconciler.diffKeyed(
                    prevKeyed,
                    Chunk.from(List(("dup", nodeA: Three), ("dup", nodeB: Three))),
                    mounted
                )
            yield
                assert(first.size == 2, s"a duplicate key must still materialize both slots, got ${first.size}")
                assert(!(first(0).obj eq first(1).obj), "the two duplicate-key slots must be distinct live objects")
                assert(second.size == 2, s"the re-diff must keep both slots, got ${second.size}")
                // Last-wins: both reused slots resolve to the LAST prior live object for the key.
                assert(second(0).obj eq first(1).obj, "the first slot must reuse the last prior live object (last-wins)")
                assert(second(1).obj eq first(1).obj, "the second slot must reuse the last prior live object (last-wins)")
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
        // three times (the fill read, the first loop read, the dedup re-read); a spinning watcher would
        // drive both counters up on every scheduler turn.
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
            assert(finalReads == 3, s"reads freeze at 3 (fill + first loop read + dedup re-read), got $finalReads")
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
                _ <- Reconciler.foreachReplace(region, Chunk(("a", nextMesh)), mounted)
            yield
                assert(hookOld == Chunk("a", "b")) // the prior keyed set, read from region.prevKeyed BEFORE this call overwrites it
                assert(hookNew == Chunk("a"))      // "b" spliced out, "a" carried forward
                assert(region.prevKeyed.map(_._1) == Chunk("a")) // overwritten AFTER the hook ran
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

end ReconcilerTest
