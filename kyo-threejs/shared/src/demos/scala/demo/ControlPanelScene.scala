package demo

import kyo.*
import kyo.Style.*

/** 3D as a reactive kyo-ui backend: form controls drive a live scene, with no redraw code anywhere.
  *
  * This is the shortest complete statement of what kyo-threejs is for. A colour picker, a slider and a
  * checkbox each own a `SignalRef`. Those same refs are handed straight to the 3D props. That is the whole
  * wiring: there is no listener, no update method, no "re-render the scene" call. When you drag the slider,
  * kyo-ui writes the ref, and the cube's scale is already reading it.
  *
  * The scene is built ONCE. Nothing here rebuilds it. `Material.color(Signal[Color])` and
  * `Mesh.scale(Signal[Vec3])` bind a prop to a signal, so a change becomes a targeted mutation of the one
  * live three.js object that owns that prop.
  *
  * The `spin` checkbox shows the same idea for behaviour rather than appearance: `onFrame` runs every
  * frame regardless, and simply reads the signal to decide whether to advance the angle. Turning the
  * checkbox off does not detach anything; the frame closure just stops accumulating.
  *
  * Read next: `SceneEditorScene` (click a 3D object to drive the panel, the other direction) and
  * `FlagshipScene` (all of it at once, plus the live mount handle).
  */
object ControlPanelScene:

    // Dark, because the canvas beside these controls clears to near-black: a light page would read as a
    // hole punched in a white sheet.
    private val text: Color   = Color.rgb(221, 227, 234)
    private val muted: Color  = Color.rgb(147, 161, 177)
    private val raised: Color = Color.rgb(27, 31, 39)

    private val pageStyle: Style =
        Style.column.gap(12.px).padding(16.px).fontFamily(FontFamily.SansSerif).color(text)

    private val panelStyle: Style =
        Style.row.gap(20.px).flexWrap(FlexWrap.wrap).align(Alignment.center)
            .padding(12.px, 16.px).bg(raised).rounded(8.px)

    private val headingStyle: Style    = Style.fontSize(20.px).fontWeight(FontWeight.bold).color(text)
    private val fieldStyle: Style      = Style.column.gap(4.px)
    private val fieldLabelStyle: Style = Style.fontSize(13.px).color(muted)
    private val readoutStyle: Style    = Style.fontFamily(FontFamily.Monospace).fontSize(13.px).color(text)

    /** The scene, plus the three refs its props read.
      *
      * Returning the refs is what makes the panel possible: `ui` binds these very refs to the form
      * controls, so the control and the 3D prop are two views of one value, never two copies kept in sync.
      */
    def scene(using Frame): (Three.Ast.Scene, SignalRef[String], SignalRef[Double], SignalRef[Boolean]) < Sync =
        for
            colorHex <- Signal.initRef("#33aaff")
            size     <- Signal.initRef(1.0)
            spinning <- Signal.initRef(true)
            angle    <- Signal.initRef(0.0)
            cube = Three.mesh(
                Three.Geometry.box(),
                // The colour prop reads the picker's ref, mapped from the "#rrggbb" the DOM gives us into
                // the Color the material wants. `map` on a signal is a derived signal, not a computation
                // anyone has to remember to re-run.
                Three.Material.standard(roughness = Three.Normal(0.4))
                    .color(colorHex.map(hexToColor))
            )
                .scale(size.map(s => Three.Vec3(s, s, s)))
                .rotation(angle.map(a => Three.Vec3(a * 0.6, a, 0)))
                // Every frame, ask the checkbox whether to advance. The closure is always attached; only
                // its effect is conditional.
                .onFrame { tick =>
                    spinning.current.map { on =>
                        if on then angle.updateAndGet(_ + tick.delta.toMillis * 0.0015).unit
                        else Kyo.unit
                    }
                }
        yield (
            Three.scene(
                // A directional key light plus an ambient fill. Directional light does not fall off with
                // distance, so the cube is lit the same wherever the camera sits. A point light would
                // decay as the inverse square of its distance, and at this range its default intensity
                // arrives as almost nothing.
                Three.Light.ambient(intensity = 0.6),
                Three.Light.directional(intensity = 1.2, position = Three.Vec3(4, 8, 6)),
                cube
            ),
            colorHex,
            size,
            spinning
        )

    /** The camera: far enough out that the cube stays framed at its largest size. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Three.Radians.deg(55),
            position = Three.Vec3(0, 2, 6),
            lookAt = Three.Vec3.zero
        )

    /** The page: a kyo-ui panel and an embedded 3D canvas, sharing the scene's refs.
      *
      * `Three.embed` makes the canvas an ordinary child of the kyo-ui tree, so the panel and the scene are
      * one document. Each control below binds the SAME ref the corresponding 3D prop reads, which is why
      * no code connects them.
      */
    def ui(using Frame): UI < Sync =
        scene.map { case (built, colorHex, size, spinning) =>
            val panel = UI.div(
                UI.h2("Controls").style(headingStyle),
                UI.div(
                    UI.label("Colour").style(fieldLabelStyle),
                    UI.colorInput.value(colorHex).id("cube-color")
                ).style(fieldStyle),
                UI.div(
                    UI.label("Size").style(fieldLabelStyle),
                    UI.rangeInput.min(0.5).max(2.5).step(0.05).value(size).id("cube-size"),
                    // A read-only view of the same ref, so the panel can show the value it is driving.
                    UI.span(size.map(s => f"$s%.2f")).id("cube-size-value").style(readoutStyle)
                ).style(fieldStyle),
                UI.div(
                    UI.label("Spin").style(fieldLabelStyle),
                    UI.checkbox.checked(spinning).id("cube-spin")
                ).style(fieldStyle)
            ).id("panel").style(panelStyle)

            UI.div(
                panel,
                Three.embed(built, camera).id("stage")
            ).id("control-panel-demo").style(pageStyle)
        }

    /** Turns the `#rrggbb` a colour input produces into a [[Three.Color]].
      *
      * Total by construction: the digits are checked before they are parsed, so a half-typed value falls
      * back to white instead of throwing. A scene prop is read on the render path, and a throw there would
      * take down the frame loop.
      */
    private def hexToColor(hex: String): Three.Color =
        val digits = (if hex.startsWith("#") then hex.drop(1) else hex).toLowerCase
        val valid  = digits.length == 6 && digits.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
        if valid then Three.Color(java.lang.Integer.parseInt(digits, 16)) else Three.Color.white
    end hexToColor

end ControlPanelScene
