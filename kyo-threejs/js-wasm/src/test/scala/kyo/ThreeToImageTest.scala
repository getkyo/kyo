package kyo

import kyo.internal.Image
import kyo.internal.Reconciler

/** Tests for [[ThreeToImage]]: the effect-row shape (no Browser in the row) and the headless
  * capture-fill path against live three.js materials. The pure PNG encoder is covered cross-platform by
  * [[PngTest]].
  */
class ThreeToImageTest extends ThreeTest:

    private val scene  = Three.scene()
    private val camera = Three.Camera.perspective()

    // ---- compile-level effect-row fixtures: these never execute at runtime ----

    "effect row compiles as Image < (Async & Scope & Abort[ThreeException])" in {
        val _: Image < (Async & Scope & Abort[ThreeException]) = Three.toImage(scene, camera)
        succeed
    }

    "row carries no Browser effect" in {
        // The type ascription below must type-check with no Browser in scope.
        val _: Image < (Async & Scope & Abort[ThreeException]) = Three.toImage(scene, camera)
        succeed
    }

    // ---- prop-level signal current-value fill (the headless capture analog of subscribeRegions) ----

    "fillBoundRefsOnce applies a signal material color's current value to the live material, not the seed" in {
        // A mesh whose material color is set via .color(signal) with the signal currently emitting RED.
        // The materialize seed for a signal-bound color is white; the toImage capture path must apply
        // the signal's current value so the captured frame shows RED. Asserts on the live three.js
        // material color directly (no renderer needed).
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    colorRef <- Signal.initRef(Three.Color.red)
                    mesh = Three.mesh(
                        Three.Geometry.box(),
                        Three.Material.standard().color(colorRef)
                    )
                    scene = Three.scene(mesh)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    seededHex <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh))
                            .map(_.obj.material.color.getHex().asInstanceOf[Int])
                            .getOrElse(-1)
                    }
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    _ <- ThreeMount.fillBoundRefsOnce(mounted)
                    filledHex <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh))
                            .map(_.obj.material.color.getHex().asInstanceOf[Int])
                            .getOrElse(-1)
                    }
                yield
                    assert(
                        seededHex == Three.Color.white.packed,
                        s"materialize seed must be white (0xffffff), got 0x${seededHex.toHexString}"
                    )
                    assert(
                        filledHex == Three.Color.red.packed,
                        s"fillBoundRefsOnce must apply the signal's current value (RED 0x${Three.Color.red.packed.toHexString}), " +
                            s"got 0x${filledHex.toHexString}"
                    )
                end for
            }
        }
    }

    "fillBoundRefsOnce applies a signal mesh position's current value to the live object, not the default" in {
        // A mesh whose transform position is set via .position(signal); the materialize path skips
        // signal-bound transforms, so the live object stays at the three.js default origin. The
        // capture fill must move it to the signal's current position.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Three.Vec3(3, 4, 5))
                    mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
                        .position(posRef)
                    scene = Three.scene(mesh)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    seeded <- liveXyz(mounted, mesh)
                    _      <- Reconciler.fillReactiveRegionsOnce(mounted)
                    _      <- ThreeMount.fillBoundRefsOnce(mounted)
                    filled <- liveXyz(mounted, mesh)
                yield
                    assert(
                        seeded == (0.0, 0.0, 0.0),
                        s"materialize must leave a signal-bound position at the origin (default), got $seeded"
                    )
                    assert(filled == (3.0, 4.0, 5.0), s"fillBoundRefsOnce must apply the current position (3,4,5), got $filled")
                end for
            }
        }
    }

    /** Reads the live three.js object's `(position.x, position.y, position.z)` for the AST node. */
    private def liveXyz(mounted: Reconciler.Mounted, node: Three)(using Frame): (Double, Double, Double) < Sync =
        Sync.Unsafe.defer {
            mounted.live.get(new Reconciler.IdentityKey(node))
                .map { live =>
                    val p = live.obj.position
                    (p.x.asInstanceOf[Double], p.y.asInstanceOf[Double], p.z.asInstanceOf[Double])
                }
                .getOrElse((Double.NaN, Double.NaN, Double.NaN))
        }

end ThreeToImageTest
