package kyo

/** Reusable browser-WebGL test helpers shared by [[WebGLAcceptanceTest]] and downstream GL-context tests.
  *
  * Provides the `withScene`/`servedScene`/`awaitRender`/`distinctPixelCount`/`swiftshaderLaunch`
  * helpers plus the `page()` HTML template. Every assertion observes a real pixel buffer from a real GL
  * context; nothing is faked. The build artifacts (the three.js install and the linked fixtures bundle)
  * are read off disk with the cross-platform [[kyo.Path]] API, so this harness and its suites run from
  * any platform kyo-browser can launch Chrome on, not just the JS Node process.
  */
trait WebGLSceneHarness extends ThreeTest:

    import WebGLSceneHarness.*

    /** Software-WebGL launch: chrome-headless-shell plus `--enable-unsafe-swiftshader`. */
    private[kyo] def swiftshaderLaunch(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig().map(_.extraArgs(Seq("--enable-unsafe-swiftshader")))

    /** Headed fallback: the full Chrome build with a visible window plus the software-WebGL flag. */
    private[kyo] def headedSwiftshaderLaunch(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig(Browser.ChromeForTestingBuild.Chrome)
            .map(_.headless(false).extraArgs(Seq("--enable-unsafe-swiftshader")))

    /** Translates no-downloadable-Chrome platforms into a ScalaTest cancel so they skip, not fail. */
    private[kyo] def cancelOnUnsupportedPlatform[A, S](
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
    private[kyo] def withScene[A](html: String)(
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
    private[kyo] def servedScene[A](html: String)(
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
    private[kyo] def awaitRender(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__renderError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else Browser.waitFor("window.__renderDone === true").unit
        }

    /** Reads back the distinct RGBA pixel count the page recorded after rendering. */
    private[kyo] def distinctPixelCount(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        Browser.evalInt("window.__distinctPixels")

end WebGLSceneHarness

object WebGLSceneHarness:

    private[kyo] val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    /** The `scala-<version>` build dir under `kyo-threejs/js/target` (where `installThree` and the
      * fixtures/three links write). Located cross-platform with [[kyo.Path]], the same way the demo
      * launchers find their link output; the working directory is the repo root under sbt.
      */
    private def jsTargetScalaDir(using Frame): Path < (Sync & Abort[BrowserException]) =
        val target = Path("kyo-threejs", "js", "target")
        toBrowserException(target.list).map { entries =>
            entries.filter(_.name.exists(_.startsWith("scala-"))).headMaybe match
                case Present(dir) => dir: Path
                case Absent =>
                    Abort.fail[BrowserException](BrowserScriptErrorException(
                        s"no scala-* build dir under kyo-threejs/js/target; was the JS link (installThree / " +
                            "fixtures fastLinkJS) run before this suite?"
                    ))
        }
    end jsTargetScalaDir

    /** Reads a file, surfacing a missing/unreadable path as a `BrowserException` so the suites' rows are
      * unchanged (a missing artifact is a build-wiring failure, the same class the old `sys.error` raised).
      */
    private def readFile(path: Path)(using Frame): String < (Sync & Abort[BrowserException]) =
        toBrowserException(path.read)

    private def toBrowserException[A](effect: A < (Sync & Abort[FileException]))(using Frame): A < (Sync & Abort[BrowserException]) =
        Abort.run[FileException](effect).map {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                Abort.fail[BrowserException](BrowserScriptErrorException(s"harness file read failed: ${e.getMessage}"))
            case Result.Panic(e) => Abort.panic(e)
        }

    /** Reads an installed three.js build file (e.g. `three.module.js`) from the JS target tree. */
    private[kyo] def readThreeSource(file: String)(using Frame): String < (Sync & Abort[BrowserException]) =
        jsTargetScalaDir.map(dir => readFile(dir / "node_modules" / "three" / "build" / file))

    /** Reads a jsm source file (e.g. `loaders/GLTFLoader.js`) from the three install. */
    private[kyo] def readThreeJsm(relative: String)(using Frame): String < (Sync & Abort[BrowserException]) =
        jsTargetScalaDir.map { dir =>
            val jsm = relative.split("/").toSeq.foldLeft(dir / "node_modules" / "three" / "examples" / "jsm")(_ / _)
            readFile(jsm)
        }

    /** Reads the linked ESModule fixtures bundle (`main.js`), the probe/hydrate `@JSExportTopLevel` link
      * output the harness pages import from.
      *
      * The bundle is the `fixtures` CONFIGURATION's link on kyo-threejsJS (`kyo-threejs/js/src/fixtures`),
      * not a separate project, and its being a build artifact on disk is what lets any platform serve it.
      * Reading a bundle off the filesystem cannot tell a fresh one from a stale one, so the build closes
      * that hole: the fixtures link is a prerequisite of the browser suites' `Test / compile`, so by the
      * time a leaf runs, the bundle on disk is the one this tree just produced.
      */
    private[kyo] def readFixtureBundle(using Frame): String < (Sync & Abort[BrowserException]) =
        jsTargetScalaDir.map(dir => readFile(dir / "kyo-threejs-fixtures-fastopt" / "main.js"))

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
