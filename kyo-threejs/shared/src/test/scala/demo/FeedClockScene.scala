package demo

import kyo.*

/** The feed-driven scene (kept minimal): ONE
  * three.js scene that simultaneously shows client animation and server-fed reactivity on the SAME cube.
  *
  *   1. CLIENT-side animation: a `SignalRef[Double]` spin angle that the cube's `onFrame` advances every
  *      RAF tick, bound to the cube `Group`'s rotation. The motion is continuous and driven entirely by
  *      the client frame loop; the server never touches it.
  *   2. SERVER-driven reactivity: the cube material's color is bound to a server-fed mirror
  *      `SignalRef[Int]` ([[FeedClockScene.colorId]]). On the island the mount calls
  *      `Three.Feed.connect(colorId, colorMirror)`, which writes inbound `HostPayload.SignalUpdate`
  *      feeds into the mirror; the existing `forkBoundRef`/`patchProp` path the scene already forked for
  *      the bound color then patches exactly the cube's material color. The server cycles the fed value
  *      through a fixed palette on its own ~1s schedule, so the color changes in DISCRETE steps that are
  *      visually distinguishable from the smooth spin.
  *
  * The smooth spin proves the local loop runs; the discrete color steps prove the server feed reaches
  * the scene. Combining client animation with server-fed reactivity on one cube is the capability the
  * scene demonstrates.
  */
object FeedClockScene:

    /** The string signal id the two halves agree on: the server feeds its color value addressed by this
      * id, and the island binds a mirror `SignalRef[Int]` under the same id.
      */
    val colorId: String = "feed-color"

    /** The fixed server palette (packed `0xRRGGBB` ints) the server cycles the fed color through, one
      * step per ~1s. Saturated primaries so each step is unmistakable in a screencast: red, green, blue,
      * yellow, magenta. The first value (red) is also the initial mirror value, so the cube starts red
      * before the first feed and steps from there.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** Builds the scene and returns it alongside the color mirror `SignalRef[Int]` the island connects to
      * the feed. The cube spins via `onFrame` on its `Group`; its material color binds to `colorMirror`
      * mapped into a `Color`. The mirror starts at the palette's first value so the cube renders red
      * before any feed arrives.
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Int]) < Sync =
        for
            spin        <- Signal.initRef(0.0)
            colorMirror <- Signal.initRef(palette.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                // A lit standard material so the rotating faces catch the directional light and the
                // shading shifts frame to frame (proving the spin); the color binds to the server-fed
                // mirror so the whole cube steps through the palette on the server's schedule.
                Three.Material.standard(color = Color.red, roughness = Normal(0.5)).color(colorMirror.map(rgb => Color(rgb)))
            )
            spinning = Three.group(cube)
                .rotation(spin.map(a => Vec3(a * 0.6, a, 0.0)))
                .onFrame(t => spin.updateAndGet(_ + t.delta.toMillis * 0.0015))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Vec3(4, 6, 8)),
                spinning
            ),
            colorMirror
        )
        end for
    end sceneWithMirror

    /** The viewing camera, pulled back to frame the spinning cube. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Vec3(0, 0, 6),
            lookAt = Vec3(0, 0, 0)
        )

end FeedClockScene
