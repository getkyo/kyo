package kyo

import kyo.internal.Reconciler

/** Tests for the orbit-controls AST surface: the `Three.controls`
  * factory produces the locked immutable `Three.Ast.Controls` node, and the reconciler materializes it
  * into the live map (the mount pipeline reads the node to bind a live OrbitControls; that GL binding is
  * exercised by the browser test, not here).
  */
class ThreeControlsTest extends ThreeTest:

    "controls defaults: zoom/pan/rotate on, autoRotate off, target at origin" in {
        val c = Three.controls()
        assert(c.isInstanceOf[Three.Ast.Controls])
        assert(c.enableZoom)
        assert(c.enablePan)
        assert(c.enableRotate)
        assert(!c.autoRotate)
        assert(c.target == Bound.Const(Three.Vec3.zero))
    }

    "controls carries each explicit flag and target" in {
        val c = Three.controls(
            target = Three.Vec3(1.0, 2.0, 3.0),
            enableZoom = false,
            enablePan = false,
            enableRotate = false,
            autoRotate = true
        )
        assert(!c.enableZoom)
        assert(!c.enablePan)
        assert(!c.enableRotate)
        assert(c.autoRotate)
        assert(c.target == Bound.Const(Three.Vec3(1.0, 2.0, 3.0)))
    }

    "a controls node is a childless Node" in {
        val c = Three.controls()
        assert(c.children == Chunk.empty)
        val _: Three.Ast.Node = c
        assert(c.isInstanceOf[Three.Ast.Node])
    }

    "the reconciler materializes a controls node into the live map" in {
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

end ThreeControlsTest
