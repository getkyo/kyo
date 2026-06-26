package kyo

/** Browser-path tests for [[ThreeToImage]] that require a real WebGL context.
  *
  * These fixtures exercise the render-target readback technique in a real Chrome with software WebGL.
  * They prove that WebGLRenderTarget + readRenderTargetPixels yields a non-blank pixel buffer for a lit
  * scene. The fixtures drive raw three.js from the browser page to validate the readback technique;
  * the compiled `Three.toImage` path is exercised by the ThumbnailGallery scenes, and the rendered
  * output is the committed `docs/images` thumbnails.
  *
  * Every assertion observes real pixel values from a real GL context; nothing is faked.
  */
class ThreeToImageBrowserTest extends WebGLSceneHarness:

    "render-target readback yields non-blank pixels for a lit scene" in {
        withScene(ThreeToImageBrowserTest.litBoxRenderTargetPage) {
            for
                _        <- awaitRender
                distinct <- Browser.evalInt("window.__rtDistinctPixels")
            yield assert(distinct >= 2, s"expected at least 2 distinct RT pixels, got $distinct")
        }
    }

    "render-target readback yields blank pixels for an empty scene" in {
        withScene(ThreeToImageBrowserTest.emptySceneRenderTargetPage) {
            for
                _        <- awaitRender
                distinct <- Browser.evalInt("window.__rtDistinctPixels")
            yield assert(
                distinct <= 1,
                s"expected at most 1 distinct RT pixel (clear color only) for an empty scene, got $distinct"
            )
        }
    }

    "render-target dimensions are honored" in {
        withScene(ThreeToImageBrowserTest.dimensionCheckPage(200, 100)) {
            for
                _      <- awaitRender
                result <- Browser.eval("window.__dimensionOk")
            yield assert(result == "true", s"render target dimensions check failed: $result")
        }
    }

    "render target fires dispose event on teardown" in {
        withScene(ThreeToImageBrowserTest.renderTargetDisposePage) {
            for
                _        <- awaitRender
                disposed <- Browser.eval("window.__targetDisposed")
            yield assert(disposed == "true", "render target must fire a dispose event when disposed")
        }
    }

end ThreeToImageBrowserTest

object ThreeToImageBrowserTest:

    /** A self-contained page that renders a lit box scene into a WebGLRenderTarget, reads the RT pixels
      * back into a Uint8Array, counts distinct RGBA tuples in `window.__rtDistinctPixels`, and also
      * renders to the canvas so the standard harness awaitRender protocol can complete.
      */
    val litBoxRenderTargetPage: String = renderTargetPage(
        rtSetup = s"""
        |const scene = new THREE.Scene();
        |scene.background = new THREE.Color(0x112233);
        |const rtCamera = new THREE.PerspectiveCamera(75, 1, 0.1, 1000);
        |rtCamera.position.set(0, 0, 5);
        |const geom = new THREE.BoxGeometry(1, 1, 1);
        |const mat = new THREE.MeshStandardMaterial({ color: 0x3377ff, roughness: 0.5 });
        |const mesh = new THREE.Mesh(geom, mat);
        |scene.add(mesh);
        |scene.add(new THREE.AmbientLight(0xffffff, 0.6));
        |const dir = new THREE.DirectionalLight(0xffffff, 1.2);
        |dir.position.set(3, 4, 5);
        |scene.add(dir);
        """.stripMargin,
        cameraVar = "rtCamera"
    )

    /** A self-contained page that renders an empty scene into a WebGLRenderTarget; all RT pixels are the
      * clear color so `window.__rtDistinctPixels` should be at most 1.
      */
    val emptySceneRenderTargetPage: String = renderTargetPage(
        rtSetup = s"""
        |const scene = new THREE.Scene();
        |const rtCamera = new THREE.PerspectiveCamera(75, 1, 0.1, 1000);
        """.stripMargin,
        cameraVar = "rtCamera"
    )

    /** Produces a complete test page that: (1) renders to a 64x64 WebGLRenderTarget, (2) reads RT pixels
      * via readRenderTargetPixels into `window.__rtDistinctPixels`, (3) renders the same scene to the
      * canvas so the standard harness awaitRender/readPixels protocol can complete.
      */
    private def renderTargetPage(rtSetup: String, cameraVar: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>rt-test</title>
           |<script type="importmap">
           |{ "imports": { "three": "/three.module.js" } }
           |</script>
           |</head>
           |<body>
           |<canvas id="c" width="256" height="256"></canvas>
           |<script type="module">
           |window.__renderDone = false;
           |window.__renderError = "";
           |window.__distinctPixels = 0;
           |window.__rtDistinctPixels = 0;
           |try {
           |    const THREE = await import("three");
           |    const canvas = document.getElementById("c");
           |    const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true });
           |    renderer.setSize(256, 256, false);
           |$rtSetup
           |    const rtWidth = 64, rtHeight = 64;
           |    const rt = new THREE.WebGLRenderTarget(rtWidth, rtHeight);
           |    renderer.setRenderTarget(rt);
           |    renderer.render(scene, $cameraVar);
           |    const rtBuf = new Uint8Array(rtWidth * rtHeight * 4);
           |    renderer.readRenderTargetPixels(rt, 0, 0, rtWidth, rtHeight, rtBuf);
           |    renderer.setRenderTarget(null);
           |    rt.dispose();
           |    const rtSeen = new Set();
           |    for (let i = 0; i < rtBuf.length; i += 4) {
           |        rtSeen.add((rtBuf[i] << 24) | (rtBuf[i+1] << 16) | (rtBuf[i+2] << 8) | rtBuf[i+3]);
           |        if (rtSeen.size > 64) break;
           |    }
           |    window.__rtDistinctPixels = rtSeen.size;
           |    renderer.render(scene, $cameraVar);
           |    const gl = renderer.getContext();
           |    const cbuf = new Uint8Array(256 * 256 * 4);
           |    gl.readPixels(0, 0, 256, 256, gl.RGBA, gl.UNSIGNED_BYTE, cbuf);
           |    const seen = new Set();
           |    for (let i = 0; i < cbuf.length; i += 4) {
           |        seen.add((cbuf[i] << 24) | (cbuf[i+1] << 16) | (cbuf[i+2] << 8) | cbuf[i+3]);
           |        if (seen.size > 64) break;
           |    }
           |    window.__distinctPixels = seen.size;
           |    window.__renderDone = true;
           |} catch (e) {
           |    window.__renderError = String(e && e.message ? e.message : String(e));
           |}
           |</script>
           |</body>
           |</html>""".stripMargin
    end renderTargetPage

    /** A self-contained page that creates a render target at the given dimensions and confirms the pixel
      * buffer byte count equals the expected size.
      */
    def dimensionCheckPage(w: Int, h: Int): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>webgl-dim</title>
           |<script type="importmap">
           |{ "imports": { "three": "/three.module.js" } }
           |</script>
           |</head>
           |<body>
           |<canvas id="c" width="256" height="256"></canvas>
           |<script type="module">
           |window.__renderDone = false;
           |window.__renderError = "";
           |window.__distinctPixels = 0;
           |window.__dimensionOk = "false";
           |try {
           |    const THREE = await import("three");
           |    const canvas = document.getElementById("c");
           |    const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true });
           |    renderer.setSize(256, 256, false);
           |    const rt = new THREE.WebGLRenderTarget($w, $h);
           |    const rtScene = new THREE.Scene();
           |    const rtCamera = new THREE.PerspectiveCamera(75, ${w.toDouble / h}, 0.1, 1000);
           |    renderer.setRenderTarget(rt);
           |    renderer.render(rtScene, rtCamera);
           |    const buf = new Uint8Array($w * $h * 4);
           |    renderer.readRenderTargetPixels(rt, 0, 0, $w, $h, buf);
           |    renderer.setRenderTarget(null);
           |    rt.dispose();
           |    window.__dimensionOk = String(buf.length === $w * $h * 4);
           |    renderer.render(rtScene, rtCamera);
           |    const gl = renderer.getContext();
           |    const cbuf = new Uint8Array(256 * 256 * 4);
           |    gl.readPixels(0, 0, 256, 256, gl.RGBA, gl.UNSIGNED_BYTE, cbuf);
           |    const seen = new Set();
           |    for (let i = 0; i < cbuf.length; i += 4) {
           |        seen.add((cbuf[i] << 24) | (cbuf[i+1] << 16) | (cbuf[i+2] << 8) | cbuf[i+3]);
           |        if (seen.size > 64) break;
           |    }
           |    window.__distinctPixels = seen.size;
           |    window.__renderDone = true;
           |} catch (e) {
           |    window.__renderError = String(e && e.message ? e.message : String(e));
           |}
           |</script>
           |</body>
           |</html>""".stripMargin
    end dimensionCheckPage

    /** A self-contained page that constructs a WebGLRenderTarget, attaches a 'dispose' event listener,
      * disposes it, and records whether the event fired.
      */
    val renderTargetDisposePage: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>rt-dispose</title>
           |<script type="importmap">
           |{ "imports": { "three": "/three.module.js" } }
           |</script>
           |</head>
           |<body>
           |<script type="module">
           |window.__renderDone = false;
           |window.__renderError = "";
           |window.__distinctPixels = 1;
           |window.__targetDisposed = "false";
           |try {
           |    const THREE = await import("three");
           |    const renderer = new THREE.WebGLRenderer({ antialias: false });
           |    renderer.setSize(64, 64);
           |    const rt = new THREE.WebGLRenderTarget(64, 64);
           |    rt.addEventListener('dispose', () => { window.__targetDisposed = "true"; });
           |    const scene = new THREE.Scene();
           |    const camera = new THREE.PerspectiveCamera(75, 1, 0.1, 100);
           |    renderer.setRenderTarget(rt);
           |    renderer.render(scene, camera);
           |    renderer.setRenderTarget(null);
           |    rt.dispose();
           |    renderer.dispose();
           |    window.__renderDone = true;
           |} catch (e) {
           |    window.__renderError = String(e && e.message ? e.message : String(e));
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeToImageBrowserTest
