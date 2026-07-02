package demo

import kyo.*

/** The flagship consolidated demo: ONE three.js scene that
  * simultaneously shows ALL FOUR behaviors on the same cube, each motion visually distinct so a
  * screencast can attribute every behavior:
  *
  *   1. CLIENT animation: the cube spins via a client `onFrame`/RAF loop advancing a local spin signal
  *      bound to the cube `Group`'s rotation. Continuous and fast; the server never touches it.
  *   2. SERVER-driven reactivity: the cube material's color binds to `colorMirror`, a `SignalRef[Int]` a
  *      server launcher cycles through a fixed palette every ~1s, so the color steps in DISCRETE jumps,
  *      distinct from the smooth spin.
  *   3. CLIENT raycast -> SERVER resolve: the cube's `onClick` closure (captured server-side, closing
  *      over `scaleMirror`) advances the scale to the next level. A click is raycast LOCALLY and posted to
  *      the server addressed by path; the server resolves and runs the SAME closure, so each click STEPS
  *      the cube larger via the ordinary bound-prop patch path.
  *   4. ORBIT controls: a `Three.controls(autoRotate = true)` node makes the mount bind a live
  *      `OrbitControls`, so the CAMERA orbits the cube slowly (a different rate from the cube's own
  *      spin), and a drag/scroll lets a viewer orbit and zoom by hand.
  *
  * Two signals meet two behaviors: the auto color cycle proves server-driven reactivity, and the
  * click-driven scale proves the server-side `onClick` resolution, on the SAME cube the client spins
  * while the camera orbits it. This is the demonstrable end state. In a client-local mount
  * (`Three.runMount`) the SAME `onClick` closure runs locally, stepping the scale exactly the same way;
  * only `colorMirror` (driven externally by a server launcher) stays at its initial value.
  */
object FlagshipScene:

    /** The fixed palette (packed `0xRRGGBB`) a server launcher cycles `colorMirror` through once per ~1s:
      * red, green, blue, yellow, magenta. Saturated primaries so each step is unmistakable in a
      * screencast. The first value (red) is also the initial signal value, so the cube starts red.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** The scale levels (a uniform per-axis factor times 1000, kept an `Int` so it crosses the wire as a
      * plain `Schema` scalar) each click advances `scaleMirror` through, wrapping at the end. The first
      * value (1.0) is the initial signal value, so the cube starts at its natural size and grows on each
      * click.
      */
    val scaleLevels: Seq[Int] = Seq(1000, 1300, 1600, 1900, 2200)

    /** Builds the scene and returns it alongside the two `SignalRef[Int]`s: the color signal (auto-cycled
      * externally by a server launcher) and the scale signal (advanced by the cube's own `onClick`). The
      * cube spins via `onFrame` on its `Group`; its material color binds to `colorMirror`, its scale binds
      * to `scaleMirror`, and its `onClick` steps `scaleMirror` to the next level. The scene carries a
      * `Three.controls(autoRotate = true)` node so the camera orbits. Both signals start at their first
      * value, so the cube renders red at its natural size before any drive or click.
      */
    def sceneWithMirrors(using Frame): (Three.Ast.Scene, SignalRef[Int], SignalRef[Int]) < Sync =
        for
            spin        <- Signal.initRef(0.0)
            colorMirror <- Signal.initRef(palette.head)
            scaleMirror <- Signal.initRef(scaleLevels.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                // A lit standard material so the rotating faces catch the directional light (proving the
                // spin); the color binds to the server-driven signal so the whole cube steps through the
                // palette on the server's schedule.
                Three.Material.standard(color = Three.Color.red, roughness = Three.Normal(0.5))
                    .color(colorMirror.map(rgb => Three.Color(rgb)))
            ).onClick { _ =>
                scaleMirror.updateAndGet(advanceScale).unit
            }
            spinning = Three.group(cube)
                .rotation(spin.map(a => Three.Vec3(a * 0.6, a, 0.0)))
                .scale(scaleMirror.map(level => Three.Vec3.one * (level / 1000.0)))
                .onFrame(t => spin.updateAndGet(_ + t.delta.toMillis * 0.0015))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Three.Vec3(4, 6, 8)),
                spinning,
                // autoRotate: the camera orbits the cube automatically, a distinct rate from the cube's own
                // onFrame spin, proving the OrbitControls binding drives the camera each frame.
                Three.controls(autoRotate = true)
            ),
            colorMirror,
            scaleMirror
        )
        end for
    end sceneWithMirrors

    /** Advances the scale level one step in [[scaleLevels]], wrapping at the end, so each click grows the
      * cube one notch then resets to its natural size.
      */
    private def advanceScale(current: Int): Int =
        val idx  = scaleLevels.indexOf(current)
        val next = if idx < 0 then 0 else (idx + 1) % scaleLevels.size
        scaleLevels(next)
    end advanceScale

    /** The viewing camera, pulled back to frame the spinning cube so a raycast click lands on it and the
      * orbit keeps the cube in view.
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 6),
            lookAt = Three.Vec3(0, 0, 0)
        )

end FlagshipScene
