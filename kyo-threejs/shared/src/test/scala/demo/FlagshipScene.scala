package demo

import kyo.*

/** The Option-Y FLAGSHIP consolidated demo (design 02-design-r2 G5): ONE three.js scene that
  * simultaneously shows ALL FOUR halves of Y on the same cube, each motion visually distinct so a
  * screencast can attribute every behavior:
  *
  *   1. CLIENT animation: the cube spins via a client `onFrame`/RAF loop advancing a local spin signal
  *      bound to the cube `Group`'s rotation. Continuous and fast; the server never touches it.
  *   2. SERVER-driven reactivity: the cube material's color binds to a server-fed mirror `SignalRef[Int]`
  *      ([[colorId]]). The server cycles a fixed palette on its own ~1s schedule, so the color steps in
  *      DISCRETE jumps, distinct from the smooth spin.
  *   3. CLIENT `onClick` -> `emit` -> SERVER reflect -> feed back: the cube's `onClick` runs LOCALLY
  *      (the client raycasts its own live scene) and calls `Three.Feed.emit(eventId, Bump(1))`; the
  *      server's `onAppEvent` handler advances a server-fed scale mirror ([[scaleId]]) and feeds it back,
  *      so each click STEPS the cube larger (a discrete size jump caused by the click).
  *   4. ORBIT controls: a `Three.controls(autoRotate = true)` node makes the island bind a live
  *      `OrbitControls`, so the CAMERA orbits the cube slowly (a different rate from the cube's own
  *      spin), and a drag/scroll lets a viewer orbit and zoom by hand.
  *
  * Two server-owned fed mirrors meet two client behaviors: the auto color cycle proves a server-pushed
  * `serverSignal`, and the click-driven scale proves the full `emit` hook-and-feed loop, on the SAME cube
  * the client spins while the camera orbits it. This is the demonstrable Option-Y end state.
  */
object FlagshipScene:

    /** The fed color signal id the server's palette cycler updates and the client color mirror binds. */
    val colorId: String = "flagship-color"

    /** The fed scale signal id the server's app-event handler advances on each click and the client
      * scale mirror binds.
      */
    val scaleId: String = "flagship-scale"

    /** The app-event routing id the client `emit` and the server `onAppEvent` agree on for a click. */
    val eventId: String = "flagship-bump"

    /** The fixed server palette (packed `0xRRGGBB`) the color cycler steps through once per ~1s: red,
      * green, blue, yellow, magenta. Saturated primaries so each step is unmistakable in a screencast.
      * The first value (red) is also the initial color-mirror value, so the cube starts red.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** The scale levels (a uniform per-axis factor times 1000, kept an `Int` so it crosses the wire as a
      * plain `Schema` scalar) the click-driven scale mirror steps through: each click advances one level,
      * wrapping at the end. The first value (1.0) is the initial scale-mirror value, so the cube starts at
      * its natural size and grows on each click.
      */
    val scaleLevels: Seq[Int] = Seq(1000, 1300, 1600, 1900, 2200)

    /** The typed app-event payload the client emits on click (a `Schema`-serializable bump). Carrying a
      * field keeps it a real typed event rather than a bare token.
      */
    final case class Bump(amount: Int) derives CanEqual, Schema

    /** Builds the scene and returns it alongside the two client mirrors the island connects to the feeds:
      * the color mirror (auto-cycled by the server) and the scale mirror (advanced by the server's
      * app-event handler on each click). The cube spins via `onFrame` on its `Group`; its material color
      * binds to `colorMirror`, its scale binds to `scaleMirror`, and its `onClick` emits `Bump(1)`. The
      * scene carries a `Three.controls(autoRotate = true)` node so the camera orbits. Both mirrors start at
      * their first value, so the cube renders red at its natural size before any feed or click.
      */
    def sceneWithMirrors(using Frame): (Three.Ast.Scene, SignalRef[Int], SignalRef[Int]) < Sync =
        for
            spin        <- Signal.initRef(0.0)
            colorMirror <- Signal.initRef(palette.head)
            scaleMirror <- Signal.initRef(scaleLevels.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                // A lit standard material so the rotating faces catch the directional light (proving the
                // spin); the color binds to the server-fed color mirror so the whole cube steps through the
                // palette on the server's schedule.
                Three.Material.standard(color = Color.red, roughness = Normal(0.5))
                    .color(colorMirror.map(rgb => Color(rgb)))
            ).onClick { _ =>
                // The onClick closure row is `< Async`; emit's typed FeedUnavailable Abort is discharged
                // here to a Log.warn (the no-channel-bound case cannot occur once the page WS is bound, but
                // the row is honored explicitly rather than widened).
                Abort.run[ThreeException](Three.Feed.emit[Bump](eventId, Bump(1))).map {
                    case Result.Success(_) => Kyo.unit
                    case Result.Failure(e) => Log.warn(s"FlagshipScene emit failed: ${e.getMessage}")
                    case Result.Panic(e)   => Log.error("FlagshipScene emit panicked", e)
                }
            }
            spinning = Three.group(cube)
                .rotation(spin.map(a => Vec3(a * 0.6, a, 0.0)))
                .scale(scaleMirror.map(level => Vec3.one * (level / 1000.0)))
                .onFrame(t => spin.updateAndGet(_ + t.delta.toMillis * 0.0015))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Vec3(4, 6, 8)),
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

    /** The viewing camera, pulled back to frame the spinning cube so a raycast click lands on it and the
      * orbit keeps the cube in view.
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Vec3(0, 0, 6),
            lookAt = Vec3(0, 0, 0)
        )

end FlagshipScene
