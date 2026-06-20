package kyo

import demo.FeedProveScene
import kyo.Browser.ScreenshotFrame

/** Browser proof of the PUBLIC Option-Y serve path `Three.Feed.run` (design 02-design-r2 DY-05/G5),
  * end to end over a real WebSocket: the page, the WS feed route, and the per-id feed observers all come
  * from `Three.Feed.run`, not a hand-rolled harness. The same cube as [[ThreeFeedProveBrowserTest]]
  * proves BOTH halves of Y on one cube, but every server-side wire is the locked public surface:
  *
  *   1. SERVE PATH: `Three.Feed.run("", head)(ui)` returns the SSR page handler (linking the
  *      self-contained FeedProve island bundle through `head.moduleScript`, carrying the inline kyo-ui
  *      client that routes each inbound `HostUpdate` into `window.__kyoHostChannels`) and the WebSocket
  *      route. The `ui` builder declares the fed color signal via `Three.Feed.serverSignal(colorId, ...)`
  *      and forks a server-side palette cycler; running it inside the WS session registers a feed observer
  *      that pushes each emission as `HostUpdate(SignalUpdate(colorId, encoded))` over the WS.
  *   2. CLIENT-side animation: the cube spins via the island's client `onFrame`/RAF loop. The server does
  *      not drive the spin; the motion is local and continuous.
  *   3. SERVER-driven reactivity: the cube material's color steps through the server palette in DISCRETE
  *      ~1s jumps as the cycler advances the fed signal and the run observer feeds each step.
  *
  * Both halves are observed from real Chrome pixels: a per-frame sampler (injected post-load via CDP, so
  * the page itself stays the unmodified public-path page) reads the rendered canvas center pixel into
  * `window.__colorLog`, and the screencast frames are saved under `runs/visual-review/y-feed-run/`. The
  * test asserts the frames change consecutively (the cube spins) AND the sampled color steps through the
  * server palette in order (red, green, blue, yellow, magenta), proving the public feed path drove the
  * color change.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeFeedRunBrowserTest extends WebGLSceneHarness:

    import ThreeFeedRunBrowserTest.*

    override def timeout = 180.seconds

    "Three.Feed.run: one cube animates client-side AND steps color from the public server feed over the WS" in {
        cancelOnUnsupportedPlatform {
            servedRun { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            // The public-path page links the self-running island (no page-side mount flag);
                            // wait until the island has created and rendered the #app canvas (a non-trivial
                            // distinct-pixel count proves the GL scene painted), then install the per-frame
                            // sampler over the live page.
                            _ <- Browser.waitFor(
                                "(function(){var c=document.getElementById('app');return !!(c&&c.width>0&&c.getContext);})()"
                            )
                            _        <- Async.sleep(800.millis)
                            _        <- installSampler
                            frames   <- recordFrames
                            _        <- saveFrames(frames)
                            colorLog <- readColorLog
                        yield
                            // ---- ANIMATION: consecutive screencast frames differ (the cube spins) ----
                            assert(frames.size >= 3, s"expected at least 3 recorded frames, got ${frames.size}")
                            val changedPairs = countChangedPairs(frames)
                            assert(
                                changedPairs >= 2,
                                s"canvas did not animate: only $changedPairs of ${frames.size - 1} consecutive frame pairs " +
                                    s"changed. Frames saved under runs/visual-review/y-feed-run/"
                            )

                            // ---- REACTIVITY: the sampled cube color stepped through the server palette ----
                            val steps = distinctColorSteps(colorLog)
                            assert(
                                steps.size >= 3,
                                s"the cube color did not step on the public server feed: observed only ${steps.size} " +
                                    s"distinct color(s) (${steps.mkString(", ")}). colorLog size=${colorLog.size}"
                            )
                            assert(
                                isPaletteCycleSlice(steps),
                                s"the observed color steps ${steps.mkString(", ")} are not an in-order slice of the server " +
                                    s"palette cycle ${paletteChannels.mkString(", ")}; the color change does not match the feed"
                            )
                    }
                }
            }
        }
    }

    /** Records a screencast across [[recordWindow]] (~5s), spanning several server color steps. */
    private def recordFrames(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 12000L, maxFrames = 2000) {
            Abort.run[BrowserReadException](Browser.waitFor("window.__never === true", Present(recordWindow))).unit
        }.map { case (frames, _) => frames }

    /** Reads the page's per-frame color samples (`window.__colorLog`). */
    private def readColorLog(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        Browser.eval("JSON.stringify(window.__colorLog || [])").map { json =>
            Json.decode[Chunk[String]](json) match
                case Result.Success(log) => log
                case _                   => Chunk.empty
        }

    /** Installs the per-frame canvas sampler over the live public-path page (post-load via CDP), so the
      * page served by `Three.Feed.run` stays unmodified and the sampler reads the REAL rendered scene.
      * Copies the `#app` WebGL canvas into a 2D canvas inside requestAnimationFrame and classifies the
      * center pixel to a dominant-channel color name, exactly as `ThreeFeedProveBrowserTest`'s baked-in
      * sampler does.
      */
    private def installSampler(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(samplerScript).unit

    private def countChangedPairs(frames: Chunk[ScreenshotFrame]): Int =
        frames.toSeq.sliding(2).count {
            case Seq(a, b) => framesDiffer(a.image.binary, b.image.binary)
            case _         => false
        }

    private def framesDiffer(a: Span[Byte], b: Span[Byte]): Boolean =
        if math.abs(a.size - b.size) > 16 then true
        else
            val n     = math.min(a.size, b.size)
            var diffs = 0
            var i     = 0
            while i < n do
                if a(i) != b(i) then diffs += 1
                i += 1
            diffs > n / 100
    end framesDiffer

    /** Writes each recorded frame as a JPEG under `runs/visual-review/y-feed-run/frame-NNN.jpg`. */
    private def saveFrames(frames: Chunk[ScreenshotFrame])(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val dir = "runs/visual-review/y-feed-run"
        Sync.defer(mkdirp(dir)).andThen {
            Kyo.foreachIndexed(frames) { (i, frame) =>
                val idx  = f"$i%03d"
                val path = s"$dir/frame-$idx.jpg"
                Abort.run[FileWriteException](frame.image.writeFileBinary(path)).unit
            }.unit
        }
    end saveFrames

    /** Serves the PUBLIC `Three.Feed.run` handlers plus the self-contained FeedProve island bundle, then
      * hands the page URL to `f`. The page, the WS route, and the per-id feed observers are all produced
      * by `Three.Feed.run`; the only extra handler is the static one serving the island bundle bytes the
      * page links through `head.moduleScript`.
      */
    private def servedRun[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            island   <- Sync.defer(readIslandBundle)
            handlers <- Three.Feed.run("", head)(ui)
            server   <- HttpServer.init(0, "localhost")((handlers :+ WebGLSceneHarness.jsHandler(islandRoute, island))*)
            result   <- f(s"http://localhost:${server.port}/")
        yield result

    /** The page head linking the self-contained FeedProve island bundle (three inlined; no import map). */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs Three.Feed.run", moduleScript = Present(islandRoute))

    /** The page body the island mounts into, plus the server-owned fed color signal and its cycler. The
      * `serverSignal` registration inside the `run` WS session is what wires the feed; a server-side
      * background fiber advances the palette index every ~1s and sets the signal.
      */
    private def ui(using Frame): UI < Async =
        for
            color <- Three.Feed.serverSignal[Int](FeedProveScene.colorId, FeedProveScene.palette.head)
            _     <- Fiber.initUnscoped(cyclePalette(color))
        yield UI.host("canvas").id("app")

    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FeedProveScene.palette(i % FeedProveScene.palette.size))
                .andThen(Async.sleep(serverStepMs.millis))
                .andThen(Loop.continue(i + 1))
        }

    private def distinctColorSteps(log: Chunk[String]): Seq[String] =
        val paletteSet = paletteChannels.toSet
        log.toSeq.filter(paletteSet.contains).foldLeft(Vector.empty[String]) { (acc, c) =>
            if acc.lastOption.contains(c) then acc else acc :+ c
        }
    end distinctColorSteps

    private def isPaletteCycleSlice(steps: Seq[String]): Boolean =
        if steps.isEmpty then false
        else
            val cycle = paletteChannels
            cycle.indices.exists { start =>
                steps.indices.forall { k =>
                    steps(k) == cycle((start + k) % cycle.size)
                }
            }
    end isPaletteCycleSlice

end ThreeFeedRunBrowserTest

object ThreeFeedRunBrowserTest:

    /** The server color-step interval (ms): the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The served route of the FeedProve island bundle the page links through `head.moduleScript`. */
    private val islandRoute: String = "/_kyo/feedprove-island.js"

    /** The recorder window: ~5s, spanning several ~1s server color steps. */
    private val recordWindow: Schedule = Schedule.fixed(50.millis).take(100)

    /** The dominant-channel names in the server's cycle order (red, green, blue, yellow, magenta). */
    private val paletteChannels: Seq[String] = Seq("red", "green", "blue", "yellow", "magenta")

    /** Creates `dir` and any missing parents, mirroring `mkdir -p`. */
    private def mkdirp(dir: String): Unit =
        NodeFsMk.mkdirSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true))
        ()
    end mkdirp

    /** Reads the bundled FeedProve island ESM (`main.js`, three inlined) from the feedprove-island esbuild
      * output tree. The output lands under
      * `kyo-threejs/feedprove-island/target/scala-<ver>/esbuild/main/out/main.js`.
      */
    private def readIslandBundle: String =
        val target = NodePathJ.join(NodeProcessJ.cwd(), "kyo-threejs", "feedprove-island", "target")
        val located = NodeFsMk.readdirSync(target).toSeq.collectFirst {
            case d
                if d.startsWith("scala-") &&
                    NodeFsMk.existsSync(NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")) =>
                NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")
        }
        NodeFsMk.readFileSync(
            located.getOrElse(sys.error(
                s"FeedProve island bundle main.js not found under $target; run 'sbt feedProveIslandBundle' first"
            )),
            "utf8"
        )
    end readIslandBundle

    /** The per-frame canvas sampler installed over the live public-path page: copies the `#app` WebGL
      * canvas into a 2D canvas inside requestAnimationFrame and reads its center pixel into
      * `window.__colorLog` as a dominant-channel color name. Mirrors the baked-in sampler of
      * `ThreeFeedProveBrowserTest.provePage`.
      */
    private val samplerScript: String =
        """(function(){
          |  if (window.__samplerInstalled) return;
          |  window.__samplerInstalled = true;
          |  window.__colorLog = window.__colorLog || [];
          |  var src = document.getElementById("app");
          |  var cap = document.createElement("canvas");
          |  cap.width = 64; cap.height = 64;
          |  var ctx = cap.getContext("2d");
          |  function channelName(r, g, b) {
          |    if (r < 40 && g < 40 && b < 40) return "none";
          |    var hi = Math.max(r, g, b);
          |    var rOn = r > hi * 0.55, gOn = g > hi * 0.55, bOn = b > hi * 0.55;
          |    if (rOn && gOn && !bOn) return "yellow";
          |    if (rOn && bOn && !gOn) return "magenta";
          |    if (gOn && bOn && !rOn) return "cyan";
          |    if (rOn && !gOn && !bOn) return "red";
          |    if (gOn && !rOn && !bOn) return "green";
          |    if (bOn && !rOn && !gOn) return "blue";
          |    return "mixed";
          |  }
          |  function sample() {
          |    try {
          |      ctx.drawImage(src, 0, 0, cap.width, cap.height);
          |      var d = ctx.getImageData(cap.width / 2, cap.height / 2, 1, 1).data;
          |      window.__colorLog.push(channelName(d[0], d[1], d[2]));
          |    } catch (e) {}
          |    requestAnimationFrame(sample);
          |  }
          |  requestAnimationFrame(sample);
          |})()""".stripMargin

    import scala.scalajs.js
    import scala.scalajs.js.annotation.JSImport

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private object NodeFsMk extends js.Object:
        def mkdirSync(path: String, options: js.Object): Unit    = js.native
        def readFileSync(path: String, encoding: String): String = js.native
        def readdirSync(path: String): js.Array[String]          = js.native
        def existsSync(path: String): Boolean                    = js.native
    end NodeFsMk

    @js.native
    @JSImport("node:path", JSImport.Namespace)
    private object NodePathJ extends js.Object:
        def join(parts: String*): String = js.native
    end NodePathJ

    @js.native
    @JSImport("node:process", JSImport.Namespace)
    private object NodeProcessJ extends js.Object:
        def cwd(): String = js.native
    end NodeProcessJ

end ThreeFeedRunBrowserTest
