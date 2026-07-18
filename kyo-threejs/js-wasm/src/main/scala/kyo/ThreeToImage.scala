package kyo

import kyo.internal.Png
import kyo.internal.Reconciler
import kyo.internal.ThreeFacade
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Headless scene-to-PNG capture internals: renders a [[Three]] scene one frame into an offscreen
  * `WebGLRenderTarget`, reads the RGBA pixel buffer back via `readRenderTargetPixels`, PNG-encodes
  * it, and returns a kyo-browser [[Image]] built from the encoded bytes.
  *
  * The entry point is `Three.toImage` on `object Three` (reachable via `import kyo.*`). Effect
  * row: `Image < (Async & Scope & Abort[ThreeException])`. No
  * `Browser` effect is in the row because the `Image` value is constructed from raw bytes via
  * `Image.fromBinary`, which runs on Node and in a headless browser alike.
  *
  * The `WebGLRenderer` and `WebGLRenderTarget` are each acquired under `Scope.acquireRelease` and
  * dispose on scope close, releasing GPU buffers regardless of how the scope exits. Typical use
  * cases include server-side 3D thumbnails, visual regression snapshots, and offline scene
  * previews.
  */
private[kyo] object ThreeToImage:

    /** A headless `WebGLRenderer` sized to the capture, acquired under Scope. */
    def makeRenderer(width: Int, height: Int)(using Frame): js.Dynamic < (Scope & Sync & Abort[ThreeException]) =
        Scope.acquireRelease(
            // Unsafe: a headless WebGLRenderer for the offscreen capture.
            Sync.Unsafe.defer {
                val r = js.Dynamic.newInstance(ThreeFacade.WebGLRenderer)(js.Dynamic.literal(antialias = true))
                val _ = r.setSize(width, height)
                r
            }
        ) { r =>
            // Unsafe: dispose the renderer on scope close.
            Sync.Unsafe.defer(r.dispose())
        }.map { r =>
            // Unsafe: a null GL context surfaces as the typed WebGLUnavailable leaf.
            Sync.Unsafe.defer(Maybe(r.getContext())).map {
                case Present(_) => r: js.Dynamic < (Scope & Sync & Abort[ThreeException])
                case Absent     => Abort.fail(ThreeException.WebGLUnavailable("no offscreen WebGL context"))
            }
        }

    /** An offscreen `WebGLRenderTarget` acquired under Scope, disposed on scope close. */
    def makeRenderTarget(width: Int, height: Int)(using Frame): js.Dynamic < (Scope & Sync) =
        Scope.acquireRelease(
            // Unsafe: the offscreen render target the frame renders into.
            Sync.Unsafe.defer(js.Dynamic.newInstance(ThreeFacade.WebGLRenderTarget)(width, height))
        ) { t =>
            // Unsafe: dispose the render target's GPU buffer on scope close.
            Sync.Unsafe.defer(t.dispose())
        }

    /** Renders one frame into the target and reads the RGBA pixel buffer back; `RenderFailure` on a
      * throw.
      */
    def renderToPixels(
        renderer: js.Dynamic,
        target: js.Dynamic,
        root: Reconciler.Live,
        camera: js.Dynamic,
        width: Int,
        height: Int
    )(using Frame): Uint8Array < (Sync & Abort[ThreeException]) =
        // Unsafe: the GL submit + pixel readback; a render throw maps to the typed RenderFailure leaf.
        Sync.Unsafe.defer {
            val _      = renderer.setRenderTarget(target)
            val _      = renderer.render(root.obj, camera)
            val buffer = new Uint8Array(width * height * 4)
            val _      = renderer.readRenderTargetPixels(target, 0, 0, width, height, buffer)
            val _      = renderer.setRenderTarget(null)
            buffer
        }.handle(Abort.recover[Throwable] { e =>
            Abort.fail(ThreeException.RenderFailure("toImage render failed", e))
        })

    /** PNG-encodes an RGBA pixel buffer into the byte array `Image.fromBinary` consumes. Copies the JS
      * `Uint8Array` to a plain `Array[Byte]` at this WebGL boundary so the encoder itself
      * ([[kyo.internal.Png]]) stays a cross-platform, FFI-free byte transform.
      */
    def encodePng(pixels: Uint8Array, width: Int, height: Int)(using Frame): Array[Byte] < Sync =
        // Unsafe: read the WebGL Uint8Array into a byte array, then encode (a pure byte transform),
        // deferred for effect placement.
        Sync.Unsafe.defer {
            val bytes = new Array[Byte](pixels.length)
            var i     = 0
            // Uint8Array elements are unsigned 0-255; toShort.toByte preserves the bit pattern.
            while i < bytes.length do
                bytes(i) = pixels(i).toShort.toByte
                i += 1
            Png.encode(bytes, width, height)
        }

end ThreeToImage
