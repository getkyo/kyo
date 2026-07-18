package demo

import kyo.*
import kyo.Style.*

/** The mount itself is a value you can observe: ordinary app code reacts to a live `Three.Mount` the same
  * way it reacts to any other signal.
  *
  * `Three.embed(scene, camera).mounted` is a `Signal[Maybe[Three.Mount]]`: `Absent` before the canvas
  * attaches, `Present(mount)` for the rest of the page's life. `ui` observes it CURRENT-FIRST (so a mount
  * that already happened before the observer attached is never missed) and, once present, forks a second
  * observer on `mount.renders`, a `Signal[Long]` that increments by exactly one on every committed frame,
  * feeding a HUD label with a live frame count. The CAPTURE button calls `mount.readPixels` on the SAME
  * handle: a real read of the live framebuffer at the next commit, returning a typed `Abort` on failure
  * rather than a throw, so the click handler pattern-matches a `Result` instead of catching an exception.
  *
  * The natural guess is that a live frame count needs polling, checking `mount.renders` on a timer. It
  * does not: `observe` is a push subscription, so the label updates exactly once per commit with no timer
  * anywhere in this file, the same shape every reactive prop in this module already uses.
  *
  * This is also the flagship consolidated demo: ONE three.js scene that simultaneously shows ALL FOUR
  * base behaviors on the same cube, each motion visually distinct so a screencast can attribute every
  * behavior:
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
  * while the camera orbits it. In a client-local mount (`Three.runMount`, which has no embed and so no
  * `mounted` signal to observe) the SAME `onClick` closure runs locally, stepping the scale exactly the
  * same way; only `colorMirror` (driven externally by a server launcher) stays at its initial value.
  *
  * This is the last stop in the reading order: every idea from the five demos before it, form controls
  * driving props, click-driven selection, keyboard-driven state, server-pushed data, async asset loading,
  * meets the one idea unique to this demo, the mount as a value.
  */
object FlagshipScene:

    // Dark, because the canvas beside this HUD clears to near-black: a light page would read as a hole
    // punched in a white sheet.
    private val text: Color   = Color.rgb(221, 227, 234)
    private val raised: Color = Color.rgb(27, 31, 39)

    private val pageStyle: Style =
        Style.column.gap(12.px).padding(16.px).fontFamily(FontFamily.SansSerif).color(text)

    private val panelStyle: Style =
        Style.row.gap(20.px).flexWrap(FlexWrap.wrap).align(Alignment.center)
            .padding(12.px, 16.px).bg(raised).rounded(8.px)

    private val buttonStyle: Style =
        Style.padding(8.px, 14.px).fontSize(15.px).rounded(6.px)
            .border(1.px, Color.rgb(58, 68, 83)).bg(Color.rgb(35, 42, 53)).color(text)
            .hover(_.bg(Color.rgb(46, 55, 69)))

    private val statusStyle: Style  = Style.fontSize(15.px).color(text)
    private val readoutStyle: Style = Style.fontFamily(FontFamily.Monospace).fontSize(13.px).color(text)

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

    /** The HUD: a live frame-count label and a capture button, both bound to refs the caller owns. Pure
      * construction (no forking), so the SAME shape renders on the server (`page`, where `mounted` never
      * leaves `Absent`) and on the client (`ui`, which additionally forks the observer that advances
      * `framesRef`); `data-kyo-path` matches between the two by construction.
      *
      * It is marked `clientOwned` because everything it shows comes from the live mount, which exists only
      * in the browser: the frame count reads `mount.renders` and the button calls `mount.readPixels`. On a
      * server-driven page the browser therefore subscribes these regions and runs this click, while the
      * cube beside it stays server-driven (its color steps from the server, its clicks resolve there).
      */
    private def hud(embedded: Three.Embedded, framesRef: SignalRef[String], captureRef: SignalRef[String])(using Frame): UI =
        UI.div(
            UI.p(framesRef.map(n => s"Frames rendered: $n")).id("frame-count").style(readoutStyle),
            UI.button("Capture").id("capture").onClick(capture(embedded, captureRef)).style(buttonStyle),
            UI.p(captureRef).id("capture-result").style(statusStyle)
        ).id("mount-hud").style(panelStyle).clientOwned

    /** The full tree: the HUD beside the embedded cube, plus the `Three.Embedded` handle the caller needs
      * to fork a live observer against (only the client-side `ui` does).
      */
    private def pageTree(built: Three.Ast.Scene, framesRef: SignalRef[String], captureRef: SignalRef[String])(
        using Frame
    ): (UI, Three.Embedded) =
        val embedded = Three.embed(built, camera).id("stage")
        (UI.div(hud(embedded, framesRef, captureRef), embedded).id("flagship-demo").style(pageStyle), embedded)
    end pageTree

    /** The server/hydrate-shared page builder: fresh HUD refs and the tree, with no live-mount observer.
      * `UI.runHandlers`'s builder row is `< Async` (no `Scope`), and a live observer only makes sense
      * where a `Three.Mount` can actually attach, which server-side rendering never does; the client
      * hydrate entry that mounts onto this SAME markup calls `ui` instead, which forks the observer.
      */
    def page(scene: Three.Ast.Scene)(using Frame): UI < Sync =
        for
            framesRef  <- Signal.initRef("0")
            captureRef <- Signal.initRef("Not captured yet.")
        yield pageTree(scene, framesRef, captureRef)._1

    /** The client mount builder: the SAME tree `page` renders, PLUS the live frame-count observer, forked
      * once the embed actually mounts, reached entirely through `Three.Embedded.mounted`.
      */
    def ui(using Frame): UI < (Async & Scope) =
        sceneWithMirrors.map { case (built, _, _) =>
            for
                framesRef  <- Signal.initRef("0")
                captureRef <- Signal.initRef("Not captured yet.")
                (tree, embedded) = pageTree(built, framesRef, captureRef)
                _ <- Fiber.init(
                    embedded.mounted.observe {
                        case Present(m) => Fiber.init(m.renders.observe(n => framesRef.set(n.toString))).unit
                        case Absent     => Kyo.unit
                    }
                )
            yield tree
        }

    /** Reads the live mount's CURRENT value (never blocking on a future mount: a button click only ever
      * fires after the canvas is already on the page) and, when present, reads its pixels and reports a
      * short summary; `Abort[ThreeException]` is handled here as a typed `Result`, never an exception.
      */
    private def capture(embedded: Three.Embedded, captureRef: SignalRef[String])(using Frame): Unit < Async =
        embedded.mounted.current.map {
            case Absent => captureRef.set("Not mounted yet.")
            case Present(m) =>
                val width  = m.width
                val height = m.height
                Abort.run[ThreeException](m.readPixels(0, 0, width, height)).map {
                    case Result.Success(pixels) => captureRef.set(describeCapture(pixels, width, height))
                    case Result.Failure(e)      => captureRef.set(s"Capture failed: ${e.getMessage}")
                    case Result.Panic(e)        => captureRef.set(s"Capture failed: ${e.getMessage}")
                }
        }

    /** A short, honest summary of a `readPixels` result: the pixel count every capture carries, plus the
      * centre pixel's colour when the buffer is large enough to contain one (total: a buffer too small to
      * hold a centre pixel is reported without it, never indexed out of bounds).
      */
    private def describeCapture(pixels: Span[Byte], width: Int, height: Int): String =
        val pixelCount   = width * height
        val centerOffset = ((height / 2) * width + (width / 2)) * 4
        if pixels.size >= centerOffset + 3 then
            val r = pixels(centerOffset).toInt & 0xff
            val g = pixels(centerOffset + 1).toInt & 0xff
            val b = pixels(centerOffset + 2).toInt & 0xff
            s"Captured $pixelCount pixels; centre colour rgb($r, $g, $b)."
        else
            s"Captured $pixelCount pixels."
        end if
    end describeCapture

end FlagshipScene
