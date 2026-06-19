package kyo

/** The acceptance gate proving three.js `WebGLRenderer` renders through a real browser GL context.
  *
  * The Node test environment has no WebGL (`new THREE.WebGLRenderer()` throws "document is not defined"
  * on bare Node), so the renderer is exercised in a real Chrome driven over CDP by kyo-browser. The page
  * loads the installed three.js build, renders a lit box into a canvas, and the test reads the pixel
  * buffer back. kyo-browser launches Chrome with `--disable-gpu` unconditionally, so the gate adds
  * `--enable-unsafe-swiftshader` to obtain a software WebGL context Chrome accepts without GPU hardware.
  *
  * Every assertion observes a real pixel buffer captured from a real GL context; nothing is faked.
  */
class WebGLAcceptanceTest extends WebGLSceneHarness:

    import WebGLAcceptanceTest.*

    "non-blank-capture present" in {
        withScene(litBoxPage) {
            for
                _    <- awaitRender
                shot <- Browser.screenshot()
            yield assert(shot.binary.nonEmpty)
        }
    }

    "blank-baseline absence" in {
        withScene(clearOnlyPage) {
            for
                _        <- awaitRender
                distinct <- distinctPixelCount
            yield assert(distinct == 1)
        }
    }

    "launch-option selection" in {
        selectLaunchOption.map { option =>
            assert(option == swiftshaderOption || option == headedSwiftshaderOption)
        }
    }

    "capture-buffer-not-uniform" in {
        withScene(litBoxPage) {
            for
                _        <- awaitRender
                distinct <- distinctPixelCount
            yield assert(distinct >= 2)
        }
    }

    /** Discovers which launch option produces a non-blank render: the headless software path first,
      * the headed software path as a fallback.
      */
    private def selectLaunchOption(using
        Frame
    ): String < (Async & Scope & Abort[BrowserException]) =
        cancelOnUnsupportedPlatform {
            servedScene(litBoxPage) { url =>
                renderDistinct(swiftshaderLaunch, url).map { headlessDistinct =>
                    if headlessDistinct >= 2 then swiftshaderOption
                    else
                        renderDistinct(headedSwiftshaderLaunch, url).map { headedDistinct =>
                            if headedDistinct >= 2 then headedSwiftshaderOption
                            else Abort.fail(BrowserScriptErrorException("no launch option produced a non-blank render"))
                        }
                }
            }
        }

    private def renderDistinct(
        launch: Browser.LaunchConfig < (Async & Abort[BrowserSetupException]),
        url: String
    )(using Frame): Int < (Async & Abort[BrowserException]) =
        launch.map { cfg =>
            Browser.run(cfg) {
                Browser.goto(url).andThen(awaitRender).andThen(distinctPixelCount)
            }
        }

end WebGLAcceptanceTest

object WebGLAcceptanceTest:

    private val swiftshaderOption       = "--enable-unsafe-swiftshader"
    private val headedSwiftshaderOption = "headless(false)+--enable-unsafe-swiftshader"

    /** A minimal lit three.js scene: a standard-material box lit by a directional + ambient light,
      * rendered once, then read back into `window.__distinctPixels`.
      */
    private[kyo] val litBoxPage: String = WebGLSceneHarness.page(
        """
        |const scene = new THREE.Scene();
        |scene.background = new THREE.Color(0x101018);
        |const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 100);
        |camera.position.set(2.4, 1.8, 3.2);
        |camera.lookAt(0, 0, 0);
        |const box = new THREE.Mesh(
        |    new THREE.BoxGeometry(1.4, 1.4, 1.4),
        |    new THREE.MeshStandardMaterial({ color: 0xff5533, roughness: 0.4, metalness: 0.1 })
        |);
        |box.rotation.set(0.6, 0.8, 0.0);
        |scene.add(box);
        |scene.add(new THREE.AmbientLight(0x404050, 1.0));
        |const key = new THREE.DirectionalLight(0xffffff, 2.0);
        |key.position.set(3, 4, 5);
        |scene.add(key);
        |renderer.render(scene, camera);
        """.stripMargin
    )

    /** A control page: the renderer clears to a single background color and draws nothing. */
    private val clearOnlyPage: String = WebGLSceneHarness.page(
        """
        |const scene = new THREE.Scene();
        |scene.background = new THREE.Color(0x101018);
        |const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 100);
        |renderer.render(scene, camera);
        """.stripMargin
    )

end WebGLAcceptanceTest
