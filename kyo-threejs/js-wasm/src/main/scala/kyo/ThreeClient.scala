package kyo

import org.scalajs.dom
import scala.scalajs.js

/** The client-only (js/wasm) `Three` surface that cannot ride the `ThreeRunnerOps` companion mixin: the
  * custom-geometry / custom-material escape hatches (methods on the nested `Geometry` / `Material`
  * objects) and the live-mount GL accessors (`mount.canvas`, `renderer.unsafe`). Each binds live WebGL,
  * so none exist on jvm/native. (The `Three.runMount` / `loadGltf` / `toImage` / `testDriver` / `texture`
  * / `custom` runners are companion members via `ThreeRunnerOps`, not extensions, so they never clash
  * with kyo-ui's top-level `UI.runMount`.)
  */
extension (geometry: Three.Geometry.type)
    /** The raw-three.js escape hatch for a custom geometry. */
    def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Three.Ast.Geometry.Custom[In] =
        Three.Ast.Geometry.Custom(build, input)
end extension

extension (material: Three.Material.type)
    /** The raw-three.js escape hatch for a custom material. */
    def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Three.Ast.Material.Custom[In] =
        Three.Ast.Material.Custom(build, input)
end extension

/** The js/wasm mixin that carries a mount's live `<canvas>`. The live `ThreeMount.MountImpl` extends it,
  * and a test that hand-rolls a `Three.Mount` double mixes it in too, so the `mount.canvas` accessor
  * downcasts to THIS (never to `MountImpl`) and works for both.
  */
private[kyo] trait MountCanvas:
    def canvas: dom.HTMLCanvasElement

extension (mount: Three.Mount)
    /** The resolved live canvas element, narrowed once at mount (client-only). */
    def canvas: dom.HTMLCanvasElement = mount.asInstanceOf[MountCanvas].canvas

extension (renderer: Three.Renderer)
    /** The raw renderer `js.Dynamic` for advanced three.js interop, the sanctioned client-only escape
      * mirroring `Three.custom`; every use is `// Unsafe:`-marked at the call site.
      */
    def unsafe: js.Dynamic = renderer.asInstanceOf[ThreeMount.RendererImpl].unsafe
end extension
