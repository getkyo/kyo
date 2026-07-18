package fixture

import kyo.*

/** A static cube cluster plus `Three.controls(autoRotate = true)`: the CAMERA orbits the cluster
  * automatically while the object itself never moves, so a sequence of sampled frames changing proves
  * the orbit-controls binding drives the camera (no node here carries an `onFrame`). Built for
  * `ThreeControlsBrowserTest`'s assertion that the mount succeeds and the sampled pixel changes across
  * commits.
  */
object ControlsInspectScene:

    /** A static cluster of differently-colored, differently-placed cubes, so the orbiting camera frames
      * a visibly different arrangement as it sweeps.
      */
    private def cube(color: Three.Color, pos: Three.Vec3, size: Double)(using Frame): Three =
        Three.mesh(
            Three.Geometry.box(size, size, size),
            Three.Material.standard(color = color, roughness = Three.Normal(0.4))
        ).position(pos)

    /** Builds the static scene with an orbiting camera: `Three.controls(autoRotate = true)` binds a live
      * `OrbitControls` over the mount camera and canvas, updated each frame by the mount's RAF loop.
      */
    def scene(using Frame): Three.Ast.Scene < Sync =
        Sync.defer {
            Three.scene(
                Three.Light.ambient(intensity = 0.8),
                Three.Light.directional(position = Three.Vec3(5, 8, 6)),
                cube(Three.Color.red, Three.Vec3(0, 0, 0), 1.4),
                cube(Three.Color.green, Three.Vec3(1.6, 0.4, 0.8), 0.9),
                cube(Three.Color.blue, Three.Vec3(-1.4, -0.3, -1.0), 1.0),
                cube(Three.Color(0xffd040), Three.Vec3(0.2, 1.4, -0.6), 0.7),
                Three.controls(autoRotate = true)
            )
        }

    /** The same static cluster, but with a REACTIVE `autoRotate` bound to a signal, plus the text ref
      * driving it (a `"true"`/`"false"` string so a `ThreeInspect` `signals` projection can flip it from the
      * page). The orbit starts OFF: the camera holds still until the ref flips to `"true"`, at which point
      * the live `OrbitControls` begins rotating with no scene rebuild. Built for the reactive-toggle
      * assertion in `ThreeControlsBrowserTest`.
      */
    def reactiveScene(using Frame): (Three.Ast.Scene, SignalRef[String]) < Sync =
        for autoRotateText <- Signal.initRef("false")
        yield
            val autoRotate = autoRotateText.map(_ == "true")
            val scene = Three.scene(
                Three.Light.ambient(intensity = 0.8),
                Three.Light.directional(position = Three.Vec3(5, 8, 6)),
                cube(Three.Color.red, Three.Vec3(0, 0, 0), 1.4),
                cube(Three.Color.green, Three.Vec3(1.6, 0.4, 0.8), 0.9),
                cube(Three.Color.blue, Three.Vec3(-1.4, -0.3, -1.0), 1.0),
                cube(Three.Color(0xffd040), Three.Vec3(0.2, 1.4, -0.6), 0.7),
                Three.controls().autoRotate(autoRotate)
            )
            (scene, autoRotateText)

    /** The viewing camera the controls orbit, offset so the initial frame shows the cluster from an
      * angle.
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 1.5, 6), lookAt = Three.Vec3(0, 0, 0))

end ControlsInspectScene
