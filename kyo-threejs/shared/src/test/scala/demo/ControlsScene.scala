package demo

import kyo.*

/** The orbit-controls scene: a STATIC
  * (non-spinning) object plus a `Three.controls(autoRotate = true)` node, so the CAMERA orbits the scene
  * automatically while the object itself never moves.
  *
  * The object is an asymmetric cluster of differently-colored, differently-placed cubes, so as the camera
  * orbits it the rendered view changes markedly (the near/far cubes and the lit faces shift). Because no
  * node carries an `onFrame`, the only motion is the camera orbit driven by `OrbitControls.update()` each
  * RAF frame (the mount calls it), which proves the controls binding works: a static object whose RENDERED
  * view nonetheless changes can only be the camera moving.
  */
object ControlsScene:

    /** A static cluster of cubes: a central large cube flanked by smaller offset cubes of distinct colors
      * at distinct depths, so the orbiting camera frames a visibly different arrangement as it sweeps.
      */
    private def cube(color: Three.Color, pos: Three.Vec3, size: Double)(using Frame): Three =
        Three.mesh(
            Three.Geometry.box(size, size, size),
            Three.Material.standard(color = color, roughness = Three.Normal(0.4))
        ).position(pos)

    /** Builds the static scene with an orbiting camera. The `Three.controls(autoRotate = true)` node makes
      * the island bind an `OrbitControls` over the camera and canvas; the mount's RAF loop calls
      * `controls.update()` each frame, so the camera rotates around the target. Nothing in the scene has an
      * `onFrame`, so the object is static and only the camera moves.
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
                // autoRotate: the camera orbits the cluster automatically (no object onFrame), proving the
                // OrbitControls binding drives the camera each frame.
                Three.controls(autoRotate = true)
            )
        }

    /** The viewing camera the controls orbit, offset so the initial frame shows the cluster from an angle. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 1.5, 6),
            lookAt = Three.Vec3(0, 0, 0)
        )

end ControlsScene
