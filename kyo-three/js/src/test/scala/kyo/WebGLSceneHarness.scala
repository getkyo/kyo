package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Reusable browser-WebGL test helpers shared by [[WebGLAcceptanceTest]] and downstream GL-context tests.
  *
  * Provides the `withScene`/`servedScene`/`awaitRender`/`distinctPixelCount`/`swiftshaderLaunch`
  * helpers plus the Node FS/Path/Process facades and the `page()` HTML template. Every assertion
  * observes a real pixel buffer from a real GL context; nothing is faked.
  */
trait WebGLSceneHarness extends ThreeTest:

    import WebGLSceneHarness.*

    /** Software-WebGL launch: chrome-headless-shell plus `--enable-unsafe-swiftshader`. */
    protected def swiftshaderLaunch(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig().map(_.extraArgs(Seq("--enable-unsafe-swiftshader")))

    /** Headed fallback: the full Chrome build with a visible window plus the software-WebGL flag. */
    protected def headedSwiftshaderLaunch(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig(Browser.ChromeForTestingBuild.Chrome)
            .map(_.headless(false).extraArgs(Seq("--enable-unsafe-swiftshader")))

    /** Translates no-downloadable-Chrome platforms into a ScalaTest cancel so they skip, not fail. */
    protected def cancelOnUnsupportedPlatform[A, S](
        f: A < (Async & Scope & Abort[BrowserSetupException] & S)
    )(using Frame): A < (Async & Scope & Abort[BrowserSetupException] & S) =
        Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
            val msg = ex.getMessage
            if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
            else Abort.fail[BrowserSetupException](ex)
        } { f }

    /** Serves the page with three.js in an import map, launches a software-WebGL Chrome, navigates,
      * and runs `body` against the rendered canvas.
      */
    protected def withScene[A](html: String)(
        body: A < (Browser & Abort[BrowserReadException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        cancelOnUnsupportedPlatform {
            servedScene(html) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        Browser.goto(url).andThen(body)
                    }
                }
            }
        }

    /** Reads the installed three.js build, serves it over HTTP alongside the page, and hands the URL
      * to `f`.
      */
    protected def servedScene[A](html: String)(
        f: String => A < (Async & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            module <- readThreeSource("three.module.js")
            core   <- readThreeSource("three.core.js")
            server <- HttpServer.init(0, "localhost")(
                htmlHandler(html),
                jsHandler("three.module.js", module),
                jsHandler("three.core.js", core)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

    /** Waits until the page reports the render finished or surfaces the page-side render error. */
    protected def awaitRender(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__renderError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else Browser.waitFor("window.__renderDone === true").unit
        }

    /** Reads back the distinct RGBA pixel count the page recorded after rendering. */
    protected def distinctPixelCount(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        Browser.evalInt("window.__distinctPixels")

end WebGLSceneHarness

object WebGLSceneHarness:

    private[kyo] val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    /** Reads an installed three.js build file (e.g. `three.module.js`) from the JS target tree. */
    private[kyo] def readThreeSource(file: String)(using Frame): String < Sync =
        Sync.defer {
            // Node fs bridge: Reads the build file from the located three/build directory.
            NodeFs.readFileSync(NodePath.join(threeBuildDir, file), "utf8")
        }

    /** The absolute `three/build` directory under the JS target tree. */
    private[kyo] lazy val threeBuildDir: String =
        // Node fs bridge: Finds the scala-version dir installThree populated.
        val targetRoot = NodePath.join(NodeProcess.cwd(), "kyo-three", "js", "target")
        val located = NodeFs.readdirSync(targetRoot).toSeq.collectFirst {
            case d
                if d.startsWith("scala-") &&
                    NodeFs.existsSync(NodePath.join(targetRoot, d, "node_modules", "three", "build", "three.module.js")) =>
                NodePath.join(targetRoot, d, "node_modules", "three", "build")
        }
        located.getOrElse(sys.error(s"three.js build dir not found under $targetRoot; was installThree run?"))
    end threeBuildDir

    /** The absolute `three/examples/jsm` directory holding the GLTFLoader and its utility siblings. */
    private[kyo] lazy val threeJsmDir: String =
        // Node fs bridge: The jsm modules sit alongside the located build directory.
        NodePath.join(NodePath.join(threeBuildDir, ".."), "examples", "jsm")

    /** Reads a jsm source file (e.g. `loaders/GLTFLoader.js`) from the three install. */
    private[kyo] def readThreeJsm(relative: String)(using Frame): String < Sync =
        Sync.defer {
            // Node fs bridge: Reads a jsm module the GLTFLoader bundle needs.
            NodeFs.readFileSync(NodePath.join(threeJsmDir, relative), "utf8")
        }

    /** The linked ESModule demo bundle (`main.js`) the harness page imports `mountDemo` from. */
    private[kyo] lazy val demoBundlePath: String =
        // Node fs bridge: Locates the kyo-three-demos fastopt main.js under its target tree.
        val demosTarget = NodePath.join(NodeProcess.cwd(), "kyo-three", "demos", "target")
        val located = NodeFs.readdirSync(demosTarget).toSeq.collectFirst {
            case d
                if d.startsWith("scala-") &&
                    NodeFs.existsSync(NodePath.join(demosTarget, d, "kyo-three-demos-fastopt", "main.js")) =>
                NodePath.join(demosTarget, d, "kyo-three-demos-fastopt", "main.js")
        }
        located.getOrElse(sys.error(
            s"demo bundle main.js not found under $demosTarget; run 'kyo-three-demos/fastLinkJS' first"
        ))
    end demoBundlePath

    /** Reads the linked demo bundle source. */
    private[kyo] def readDemoBundle(using Frame): String < Sync =
        Sync.defer {
            // Node fs bridge: Reads the linked ESModule demo bundle.
            NodeFs.readFileSync(demoBundlePath, "utf8")
        }

    @js.native
    @JSImport("node:path", JSImport.Namespace)
    private[kyo] object NodePath extends js.Object:
        def join(parts: String*): String = js.native
    end NodePath

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private[kyo] object NodeFs extends js.Object:
        def readFileSync(path: String, encoding: String): String = js.native
        def readdirSync(path: String): js.Array[String]          = js.native
        def existsSync(path: String): Boolean                    = js.native
    end NodeFs

    @js.native
    @JSImport("node:process", JSImport.Namespace)
    private[kyo] object NodeProcess extends js.Object:
        def cwd(): String = js.native
    end NodeProcess

    private[kyo] def staticHandler(path: String, contentType: String, source: String)(using
        Frame
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(path).response(_.bodyText).handler { _ =>
            HttpResponse.ok(source).setHeader("Content-Type", contentType)
        }

    private[kyo] def jsHandler(path: String, source: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        staticHandler(path, "text/javascript; charset=utf-8", source)

    private[kyo] def htmlHandler(html: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        staticHandler("", "text/html; charset=utf-8", html)

    /** Wraps a render snippet in a full HTML page with an import map, a canvas, the render call, and
      * pixel read-back that records distinct-pixel count and done/error flags.
      */
    def page(renderBody: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>webgl-gate</title>
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
           |try {
           |    const THREE = await import("three");
           |    const canvas = document.getElementById("c");
           |    const gl = canvas.getContext("webgl2") || canvas.getContext("webgl");
           |    if (!gl) { throw new Error("no WebGL context"); }
           |    const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true });
           |    renderer.setSize(256, 256, false);
           |$renderBody
           |    const w = canvas.width, h = canvas.height;
           |    const buf = new Uint8Array(w * h * 4);
           |    gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, buf);
           |    const seen = new Set();
           |    for (let i = 0; i < buf.length; i += 4) {
           |        seen.add((buf[i] << 24) | (buf[i + 1] << 16) | (buf[i + 2] << 8) | buf[i + 3]);
           |        if (seen.size > 64) break;
           |    }
           |    window.__distinctPixels = seen.size;
           |    window.__renderDone = true;
           |} catch (e) {
           |    window.__renderError = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end WebGLSceneHarness
