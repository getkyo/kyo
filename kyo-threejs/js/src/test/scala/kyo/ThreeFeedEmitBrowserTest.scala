package kyo

import demo.FeedEmitScene
import kyo.Browser.ScreenshotFrame

/** Browser proof of the app-event back-channel `Three.Feed.emit`,
  * end to end over a real WebSocket through the PUBLIC serve path. Clicking the cube on the live scene
  * runs its `onClick` LOCALLY, which calls `Three.Feed.emit` to post a typed app event; the server's
  * `Three.Feed.onAppEvent` handler (registered inside the `Three.Feed.run` `ui` builder) advances a
  * server-owned fed color and feeds it back, and the cube's color VISIBLY changes, observed from real
  * Chrome pixels.
  *
  *   1. SERVE PATH: `Three.Feed.run("", head)(ui)` serves the page (linking the emit island bundle) and
  *      the WS route. The `ui` declares a fed color signal via `serverSignal(colorId)` AND registers an
  *      app-event handler via `onAppEvent(eventId)` that advances the palette color and sets the fed
  *      signal, so an inbound `AppEvent` steps the color back to the client.
  *   2. CLICK -> emit: a real pointerdown is dispatched at the cube center via CDP; the client raycasts its
  *      own live scene, runs the `onClick` closure, and `emit`s `Bump(1)` over the WS.
  *   3. REFLECT: the server handler bumps the fed color; the client mirror write steps the cube's color.
  *
  * The proof samples the rendered canvas center pixel before and after each click into `window.__colorLog`
  * and asserts the cube color stepped through the server palette in order on the clicks (red -> green ->
  * blue ...), proving the click drove the server-reflected color change. Frames are saved under
  * `runs/visual-review/feed-emit/` with the before/after-click frames.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeFeedEmitBrowserTest extends WebGLSceneHarness:

    import ThreeFeedEmitBrowserTest.*

    override def timeout = 180.seconds

    "Three.Feed.emit: a client click posts an app event the server reflects into a visible color step" in {
        cancelOnUnsupportedPlatform {
            servedRun { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            // Wait until the island created and rendered the #app canvas, then install the
                            // per-frame color sampler over the live page.
                            _ <- Browser.waitFor(
                                "(function(){var c=document.getElementById('app');return !!(c&&c.width>0&&c.getContext);})()"
                            )
                            // The Three.Feed.run page carries the inline kyo-ui clientJs which opens the WS
                            // and installs window.__kyoPostAppEvent; give it a moment to connect and the
                            // island to mount before sampling and clicking.
                            _ <- Async.sleep(1200.millis)
                            _ <- installSampler
                            // Dispatch a real click per palette step at the cube center, each gated on the
                            // prior step's fed color being observed; record the screencast across them.
                            frames   <- recordWithClicks
                            _        <- saveFrames(frames)
                            colorLog <- readColorLog
                        yield
                            assert(frames.size >= 3, s"expected at least 3 recorded frames, got ${frames.size}")
                            // ---- BACK-CHANNEL: the cube color stepped through the server palette on the clicks ----
                            val steps = distinctColorSteps(colorLog)
                            assert(
                                steps.size >= 2,
                                s"the cube color never stepped on a click: observed only ${steps.size} distinct color(s) " +
                                    s"(${steps.mkString(", ")}). Each click should emit an app event the server reflects into " +
                                    s"a fed color step. colorLog size=${colorLog.size}. Frames under runs/visual-review/feed-emit/"
                            )
                            assert(
                                isPaletteCycleSlice(steps),
                                s"the observed color steps ${steps.mkString(", ")} are not an in-order slice of the server palette " +
                                    s"cycle ${paletteChannels.mkString(", ")}; the click-driven color change does not match the feed"
                            )
                    }
                }
            }
        }
    }

    /** Records a screencast while clicking the cube center once per palette step. After each click it waits
      * until the sampler actually observes the expected fed color at the center before clicking again, so
      * every step's reflect renders and is sampled. Waiting on the observed color rather than a fixed delay
      * keeps an intermediate step (the low-luminance blue) from collapsing into the next under load, where a
      * delayed reflect could otherwise step the cube straight past blue before any frame samples it.
      */
    private def recordWithClicks(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 30000L, maxFrames = 2000) {
            // Step indices 1..4 click for green, blue, yellow, magenta (index 0, red, is the initial color).
            Loop(1) { step =>
                if step > 4 then Loop.done
                else clickCubeCenter.andThen(awaitCenterColor(paletteChannels(step))).andThen(Loop.continue(step + 1))
            }
        }.map { case (frames, _) => frames }

    /** Polls the per-frame sampler until the latest sampled center color equals `expected`, bounding the
      * wait so a never-arriving reflect fails the step rather than hanging. Gating each click on the prior
      * step's observed color makes every palette step deterministically sampled regardless of reflect
      * latency under load.
      */
    private def awaitCenterColor(expected: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.waitFor(
            s"""(function(){var l=window.__colorLog;return !!(l&&l.length>0&&l[l.length-1]==="$expected");})()""",
            Present(Schedule.fixed(50.millis).maxDuration(20.seconds))
        ).unit

    /** Dispatches a real `pointerdown`/`pointerup` at the `#app` canvas center via the page, so the client
      * raycast hits the centered cube and runs its `onClick` (which calls `Three.Feed.emit`). The capture
      * listener `setupPointerDelegation` registers fires on `pointerdown`.
      */
    private def clickCubeCenter(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(clickScript).unit

    /** Reads the page's per-frame color samples (`window.__colorLog`). */
    private def readColorLog(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        Browser.eval("JSON.stringify(window.__colorLog || [])").map { json =>
            Json.decode[Chunk[String]](json) match
                case Result.Success(log) => log
                case _                   => Chunk.empty
        }

    private def installSampler(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(samplerScript).unit

    /** Writes each recorded frame as a JPEG under `runs/visual-review/feed-emit/frame-NNN.jpg`. */
    private def saveFrames(frames: Chunk[ScreenshotFrame])(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val dir = "runs/visual-review/feed-emit"
        Sync.defer(mkdirp(dir)).andThen {
            Kyo.foreachIndexed(frames) { (i, frame) =>
                val idx  = f"$i%03d"
                val path = s"$dir/frame-$idx.jpg"
                Abort.run[FileWriteException](frame.image.writeFileBinary(path)).unit
            }.unit
        }
    end saveFrames

    /** Serves the PUBLIC `Three.Feed.run` handlers plus the self-contained emit island bundle, then hands
      * the page URL to `f`. The page, the WS route (with the app-event routing), and the per-id feed
      * observers are all produced by `Three.Feed.run`.
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

    /** The page head linking the self-contained emit island bundle (three inlined; no import map). */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs Three.Feed.emit", moduleScript = Present(islandRoute))

    /** The page body the island mounts into, plus the server-owned fed color signal and the app-event
      * handler. The `serverSignal` registration declares the fed color; the `onAppEvent` registration is
      * the server leg of the back-channel: each inbound `Bump` advances a palette index and sets the fed
      * color signal, which `run` feeds back over the WS.
      */
    private def ui(using Frame): UI < Async =
        for
            color <- Three.Feed.serverSignal[Int](FeedEmitScene.colorId, FeedEmitScene.palette.head)
            idx   <- AtomicInt.init(0)
            _ <- Three.Feed.onAppEvent[FeedEmitScene.Bump](FeedEmitScene.eventId) { bump =>
                // Advance the palette by the bump amount and set the fed color; run feeds it back.
                idx.updateAndGet(_ + math.max(1, bump.amount)).map { i =>
                    color.set(FeedEmitScene.palette(i % FeedEmitScene.palette.size))
                }
            }
        yield UI.host("canvas").id("app")

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
                steps.indices.forall(k => steps(k) == cycle((start + k) % cycle.size))
            }
    end isPaletteCycleSlice

end ThreeFeedEmitBrowserTest

object ThreeFeedEmitBrowserTest:

    /** The served route of the emit island bundle the page links through `head.moduleScript`. */
    private val islandRoute: String = "/_kyo/emit-island.js"

    /** The dominant-channel names in the server's palette cycle order (red, green, blue, yellow, magenta). */
    private val paletteChannels: Seq[String] = Seq("red", "green", "blue", "yellow", "magenta")

    /** Creates `dir` and any missing parents, mirroring `mkdir -p`. */
    private def mkdirp(dir: String): Unit =
        NodeFsMk.mkdirSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true))
        ()
    end mkdirp

    /** Reads the bundled emit island ESM (`main.js`, three inlined) from the emit-island esbuild output. */
    private def readIslandBundle: String =
        val target = NodePathJ.join(NodeProcessJ.cwd(), "kyo-threejs", "emit-island", "target")
        val located = NodeFsMk.readdirSync(target).toSeq.collectFirst {
            case d
                if d.startsWith("scala-") &&
                    NodeFsMk.existsSync(NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")) =>
                NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")
        }
        NodeFsMk.readFileSync(
            located.getOrElse(sys.error(
                s"emit island bundle main.js not found under $target; run 'sbt emitIslandBundle' first"
            )),
            "utf8"
        )
    end readIslandBundle

    /** Dispatches a real pointerdown then pointerup at the `#app` canvas center: the capture-phase
      * pointerdown listener `setupPointerDelegation` registers raycasts the centered cube and runs onClick.
      */
    private val clickScript: String =
        """(function(){
          |  var c = document.getElementById("app");
          |  if (!c) return;
          |  var r = c.getBoundingClientRect();
          |  var x = r.left + r.width / 2, y = r.top + r.height / 2;
          |  var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0, pointerId: 1, isPrimary: true };
          |  c.dispatchEvent(new PointerEvent("pointerdown", opts));
          |  c.dispatchEvent(new PointerEvent("pointerup", opts));
          |})()""".stripMargin

    /** The per-frame canvas sampler installed over the live page: copies the `#app` WebGL canvas into a 2D
      * canvas inside requestAnimationFrame and classifies the center pixel to a dominant-channel color name.
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
          |    // Remove the achromatic (white) component so ambient/specular lighting on the cube does not
          |    // pull a low-luminance hue (blue) toward white; classify the remaining chroma by dominance.
          |    var m = Math.min(r, g, b);
          |    r -= m; g -= m; b -= m;
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

end ThreeFeedEmitBrowserTest
