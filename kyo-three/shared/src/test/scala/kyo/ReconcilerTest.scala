package kyo

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

end ReconcilerTest
