package demo

import kyo.*

/** The Option-Y APP-EVENT prove-the-mechanism scene (design 02-design-r2 DY-04): ONE cube whose CLIENT
  * `onClick` posts a typed app event over the back-channel, and whose color is driven by a server-fed
  * mirror the server's app-event handler updates.
  *
  *   1. CLIENT click -> emit: the cube's `onClick` runs LOCALLY (the client raycasts its own live scene)
  *      and calls `Three.Feed.emit(eventId, Bump(1))`, posting the typed event over the same WebSocket.
  *   2. SERVER reflect -> feed back: the server's `Three.Feed.onAppEvent(eventId)` handler advances a
  *      server-owned fed color and feeds it back as a `HostPayload.SignalUpdate(colorId, encoded)`; the
  *      client writes the mirror and the existing `forkBoundRef` patch steps the cube's color.
  *
  * So a click VISIBLY changes the cube's color, proving the full client->server->client hook-and-feed
  * loop. The cube does not spin: the color step is the only motion, so the test asserts a color change
  * caused by the click rather than confounding it with animation.
  */
object FeedEmitScene:

    /** The app-event routing id the client `emit` and the server `onAppEvent` agree on. */
    val eventId: String = "bump"

    /** The fed color signal id the server handler updates and the client mirror binds. */
    val colorId: String = "emit-color"

    /** The fixed server palette (packed `0xRRGGBB`) the server steps through on each click: red, green,
      * blue, yellow, magenta. The cube starts red; each click advances one step.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** The typed app-event payload the client emits on click (a `Schema`-serializable bump). Carrying a
      * field keeps it a real typed event rather than a bare token.
      */
    final case class Bump(amount: Int) derives CanEqual, Schema

    /** Builds the scene and returns it alongside the color mirror `SignalRef[Int]` the island connects to
      * the fed color. The cube's `onClick` emits `Bump(1)`; its material color binds to `colorMirror`
      * mapped into a `Color`. The mirror starts at the palette's first value so the cube renders red
      * before any click.
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Int]) < Sync =
        for
            colorMirror <- Signal.initRef(palette.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                Three.Material.standard(color = Color.red, roughness = Normal(0.5))
                    .color(colorMirror.map(rgb => Color(rgb)))
            ).onClick { _ =>
                // The onClick closure row is `< Async`; emit's typed FeedUnavailable Abort is discharged
                // here to a Log.warn (the no-channel-bound case cannot occur once the page WS is bound, but
                // the row is honored explicitly rather than widened).
                Abort.run[ThreeException](Three.Feed.emit[Bump](eventId, Bump(1))).map {
                    case Result.Success(_) => Kyo.unit
                    case Result.Failure(e) => Log.warn(s"FeedEmitScene emit failed: ${e.getMessage}")
                    case Result.Panic(e)   => Log.error("FeedEmitScene emit panicked", e)
                }
            }
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Vec3(4, 6, 8)),
                cube
            ),
            colorMirror
        )
        end for
    end sceneWithMirror

    /** The viewing camera, pulled back to frame the cube so a raycast click lands on it. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Vec3(0, 0, 6),
            lookAt = Vec3(0, 0, 0)
        )

end FeedEmitScene
