package kyo

/** Tests for the orbit-controls AST surface: the `Three.controls` factory produces the locked
  * immutable `Three.Ast.Controls` node. The reconciler materialization and the live GL binding are
  * exercised in the js-wasm test tree (ReconcilerTest, ThreeControlsBrowserTest), not here, so these
  * pure AST tests run on every platform.
  */
class ThreeControlsTest extends ThreeTest:

    "controls defaults: zoom/pan/rotate on, autoRotate off, target at origin" in {
        val c = Three.controls()
        assert(c.isInstanceOf[Three.Ast.Controls])
        assert(c.enableZoom)
        assert(c.enablePan)
        assert(c.enableRotate)
        assert(c.autoRotate == Bound.Const(false))
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
        assert(c.autoRotate == Bound.Const(true))
        assert(c.target == Bound.Const(Three.Vec3(1.0, 2.0, 3.0)))
    }

    "the autoRotate setter binds a constant or a signal" in {
        val const = Three.controls().autoRotate(true)
        assert(const.autoRotate == Bound.Const(true))
        Signal.initRef(false).map { ref =>
            val reactive = Three.controls().autoRotate(ref)
            assert(reactive.autoRotate == Bound.Ref(ref))
        }
    }

    "a controls node is a childless Node" in {
        val c = Three.controls()
        assert(c.children == Chunk.empty)
        val _: Three.Ast.Node = c
        assert(c.isInstanceOf[Three.Ast.Node])
    }

    // The reconciler-materialization of a controls node (Reconciler.mount, js/wasm only) is covered by
    // ReconcilerTest in the js-wasm test tree.

end ThreeControlsTest
