package kyo

import demo.FeedClockScene
import kyo.Browser.ScreenshotFrame
import kyo.internal.HtmlOp

/** Browser proof of the feed-driven scene: ONE three.js scene that
  * simultaneously shows client animation and server-fed reactivity on the SAME cube, served and driven
  * over a real WebSocket.
  *
  *   1. CLIENT-side animation: the cube spins via a client `onFrame`/RAF loop. The server does not drive
  *      the spin; the motion is local and continuous.
  *   2. SERVER-driven reactivity: the cube's material color is bound to a server-fed mirror `SignalRef`
  *      addressed by the string id [[FeedClockScene.colorId]]. A SERVER fiber pushes
  *      `HostPayload.SignalUpdate(colorId, encoded)` frames over the WS on a ~1s schedule, cycling a
  *      fixed palette; the page routes each inbound `HostUpdate` to the per-id feed receiver
  *      (`Three.Feed.connect`), which writes the mirror, and the existing `forkBoundRef`/`patchProp`
  *      path steps the cube's color. The color steps in DISCRETE ~1s jumps, visually distinct from the
  *      smooth spin.
  *
  * The proof has two parts, both observed from real Chrome pixels:
  *   - ANIMATION: the screencast frames CHANGE consecutively (the cube spins) even between two server
  *     color steps.
  *   - REACTIVITY: the page samples the rendered canvas center pixel every animation frame into
  *     `window.__colorLog`; the test asserts the sampled color steps through the server's palette in
  *     order on the ~1s schedule, proving the server feed (not local animation) drove the color change.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded. The
  * screencast frames are saved under `runs/visual-review/feed-clock/` for inspection.
  */
class ThreeFeedClockBrowserTest extends WebGLSceneHarness:

    import ThreeFeedClockBrowserTest.*

    override def timeout = 180.seconds

    "one cube animates client-side AND steps color from the server feed over the WS" in {
        cancelOnUnsupportedPlatform {
            servedFeedClock { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitMountError
                            _ <- Browser.waitFor("window.__mounted === true").handle(diagnoseMountTimeout)
                            // Wait until the WS is open and the first feed has been routed, so the
                            // recording spans real server steps rather than the pre-connect window.
                            _         <- Browser.waitFor("window.__wsOpen === true")
                            frames    <- recordFrames
                            _         <- saveFrames(frames)
                            colorLog  <- readColorLog
                            feedCount <- Browser.eval("String(window.__feedCount)")
                            hadRx     <- Browser.eval("String(window.__hadReceiver)")
                            feedLog   <- Browser.eval("JSON.stringify(window.__feedLog.slice(0,8))")
                            chans     <- Browser.eval("JSON.stringify(Object.keys(window.__kyoHostChannels||{}))")
                        yield
                            val diag = s"[diag feedCount=$feedCount hadReceiver=$hadRx channels=$chans feedLog=$feedLog]"
                            // ---- ANIMATION: consecutive screencast frames differ (the cube spins) ----
                            assert(frames.size >= 3, s"expected at least 3 recorded frames, got ${frames.size}")
                            val changedPairs = countChangedPairs(frames)
                            assert(
                                changedPairs >= 2,
                                s"canvas did not animate: only $changedPairs of ${frames.size - 1} consecutive frame pairs " +
                                    s"changed (a static canvas yields ~identical frames). Frames saved under runs/visual-review/feed-clock/"
                            )

                            // ---- REACTIVITY: the sampled cube color stepped through the server palette ----
                            // The page samples the rendered canvas center pixel each frame; collapse the log to
                            // the ordered sequence of DISTINCT dominant-channel colors the cube held over time.
                            val steps = distinctColorSteps(colorLog)
                            assert(
                                steps.size >= 3,
                                s"the cube color did not step on the server feed: observed only ${steps.size} distinct " +
                                    s"color(s) over the recording (${steps.mkString(", ")}). The server cycles ${FeedClockScene.palette.size} " +
                                    s"colors every ~${serverStepMs}ms; a single color means the feed never reached the scene. " +
                                    s"colorLog size=${colorLog.size} $diag"
                            )
                            // The observed steps must be a contiguous slice of the server's palette cycle (same
                            // order, no foreign color), proving the SERVER feed drove the steps, not local noise.
                            assert(
                                isPaletteCycleSlice(steps),
                                s"the observed color steps ${steps.mkString(", ")} are not an in-order slice of the server " +
                                    s"palette cycle ${paletteChannels.mkString(", ")}; the color change does not match the server feed"
                            )
                    }
                }
            }
        }
    }

    /** Records a screencast across [[recordWindow]] (~5s), spanning several server color steps. The page
      * never raises `window.__never`, so the waitFor runs the whole window and aborts at its end; the
      * abort is swallowed so the recorder returns its frames cleanly.
      */
    private def recordFrames(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 12000L, maxFrames = 2000) {
            Abort.run[BrowserReadException](Browser.waitFor("window.__never === true", Present(recordWindow))).unit
        }.map { case (frames, _) => frames }

    /** Reads the page's per-frame color samples (`window.__colorLog`): each entry is the dominant RGB
      * channel the rendered cube held at that sample, in capture order.
      */
    private def readColorLog(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        Browser.eval("JSON.stringify(window.__colorLog || [])").map { json =>
            Json.decode[Chunk[String]](json) match
                case Result.Success(log) => log
                case _                   => Chunk.empty
        }

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

    /** Writes each recorded frame as a JPEG under `runs/visual-review/feed-clock/frame-NNN.jpg`. */
    private def saveFrames(frames: Chunk[ScreenshotFrame])(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val dir = "runs/visual-review/feed-clock"
        Sync.defer(mkdirp(dir)).andThen {
            Kyo.foreachIndexed(frames) { (i, frame) =>
                val idx  = f"$i%03d"
                val path = s"$dir/frame-$idx.jpg"
                Abort.run[FileWriteException](frame.image.writeFileBinary(path)).unit
            }.unit
        }
    end saveFrames

    private def awaitMountError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__mountError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    private def diagnoseMountTimeout(
        wait: => String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            Browser.eval("String(window.__mountError)").map { err =>
                Abort.fail(BrowserScriptErrorException(s"feed-clock mount ready flag never set: mountError='$err'"))
            }
        }(wait)

    /** Serves the demo bundle, three.js, the feed page, AND a WebSocket route at `/_kyo/ws`. The WS
      * server forks a fiber that cycles the color feed through the palette every ~1s, pushing each step as
      * a `HostUpdate(SignalUpdate(colorId, encoded))` over the socket. Hands the page URL to `f`.
      */
    private def servedFeedClock[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            orbit     <- WebGLSceneHarness.readThreeJsm("controls/OrbitControls.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(feedClockPage),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/controls/OrbitControls.js", orbit),
                feedWsHandler
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

    /** The WebSocket endpoint the page connects to. On connect it forks the color-feed fiber (cycling the
      * palette every [[serverStepMs]]ms, each step a `HostUpdate(SignalUpdate(colorId, encoded))`) and
      * races it against the peer close, so the feed runs for the connection lifetime and tears down on
      * disconnect.
      */
    private def feedWsHandler(using Frame): HttpHandler[Any, Any, Nothing] =
        HttpHandler.webSocket("/_kyo/ws") { (_, ws) =>
            Async.race(colorFeed(ws), ws.onPeerClose)
        }

    /** The server-driven color feed: an index advanced every [[serverStepMs]]ms; each step encodes the
      * next palette color as `Three.Feed.encodeUpdate(colorId, rgb)` (the wire leaf) and puts the
      * `HtmlOp.HostUpdate` frame over the WS, exactly as `UIServer.emitHostUpdate` does. A closed socket
      * ends the loop (the `Closed` is dropped: the connection went away).
      */
    private def colorFeed(ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.runPartial[Closed] {
            AtomicInt.init(0).map { idx =>
                Clock.repeatAtInterval(serverStepMs.millis) {
                    idx.getAndIncrement.map { i =>
                        val rgb     = FeedClockScene.palette(i % FeedClockScene.palette.size)
                        val payload = Three.Feed.encodeUpdate[Int](FeedClockScene.colorId, rgb)
                        val op      = HtmlOp.HostUpdate(Seq(FeedClockScene.colorId), payload)
                        ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))
                    }
                }.map(_.get)
            }
        }.unit

    /** Collapses the per-frame color log to the ordered sequence of DISTINCT server-palette colors the
      * cube held, dropping runs of the same color (so the smooth spin within one server step collapses to
      * one entry and only the server-driven steps remain). Keeps ONLY the palette channel names: a
      * `"none"` sample (a frame before the first render) and a `"mixed"`/`"cyan"` sample (a transient
      * captured mid-render or at the lit cube's shaded edge, never one of the server's saturated palette
      * colors) are dropped, so the remaining sequence is the server-driven color steps alone.
      */
    private def distinctColorSteps(log: Chunk[String]): Seq[String] =
        val paletteSet = paletteChannels.toSet
        log.toSeq.filter(paletteSet.contains).foldLeft(Vector.empty[String]) { (acc, c) =>
            if acc.lastOption.contains(c) then acc else acc :+ c
        }
    end distinctColorSteps

    /** True when `steps` is an in-order contiguous slice of the server palette cycle (the dominant-channel
      * names the page reports), allowing the cycle to wrap. Proves the observed steps match the server's
      * feed order, not local noise.
      */
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

end ThreeFeedClockBrowserTest

object ThreeFeedClockBrowserTest:

    /** The server color-step interval (ms): the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The recorder window: ~5s, spanning several ~1s server color steps. The page never raises
      * `window.__never`, so the waitFor runs the whole window and records frames for its full duration.
      */
    private val recordWindow: Schedule = Schedule.fixed(50.millis).take(100)

    /** The dominant-channel name the page reports for each palette color, in the server's cycle order
      * (red, green, blue, yellow, magenta). The page maps a sampled RGB to one of these names, so the
      * test compares the observed steps against this cycle without pixel-exact color matching.
      */
    private val paletteChannels: Seq[String] = Seq("red", "green", "blue", "yellow", "magenta")

    /** Creates `dir` and any missing parents, mirroring `mkdir -p`. */
    private def mkdirp(dir: String): Unit =
        NodeFsMk.mkdirSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true))
        ()
    end mkdirp

    import scala.scalajs.js
    import scala.scalajs.js.annotation.JSImport

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private object NodeFsMk extends js.Object:
        def mkdirSync(path: String, options: js.Object): Unit = js.native
    end NodeFsMk

    /** The FeedClock page: imports the demo bundle's `mountFeedClock` entry (which mounts the spinning cube
      * AND connects the `feed-color` mirror), opens a WebSocket to `/_kyo/ws`, and routes each inbound
      * `HostUpdate` to `window.__kyoHostChannels[path[0]]` exactly as the kyo-ui inline clientJs does
      * (`HtmlRenderer.scala:771-799`), with the same late-registration flush. It also runs a per-frame
      * sampler that reads the rendered canvas center pixel into `window.__colorLog` as a dominant-channel
      * color name, so the test can prove the server feed visibly changed the scene from real pixels.
      */
    private val feedClockPage: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs FeedClock</title>
           |<script type="importmap">
           |{ "imports": {
           |    "three": "/three.module.js",
           |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js",
           |    "three/examples/jsm/utils/BufferGeometryUtils.js": "/three/examples/jsm/utils/BufferGeometryUtils.js",
           |    "three/examples/jsm/utils/SkeletonUtils.js": "/three/examples/jsm/utils/SkeletonUtils.js",
           |    "three/examples/jsm/controls/OrbitControls.js": "/three/examples/jsm/controls/OrbitControls.js"
           |} }
           |</script>
           |<style>html,body{margin:0;background:#101018}#app{display:block}</style>
           |</head>
           |<body>
           |<canvas id="app" width="640" height="480"></canvas>
           |<script type="module">
           |window.__mounted = false;
           |window.__mountError = "";
           |window.__wsOpen = false;
           |window.__colorLog = [];
           |window.__feedCount = 0;
           |window.__feedLog = [];
           |window.__hadReceiver = false;
           |// The HostUpdate routing the kyo-ui inline clientJs provides (HtmlRenderer.scala:771-799):
           |// a per-path receiver registry plus a late-registration flush, so a feed pushed before the
           |// scene registers its receiver is buffered and replayed in order.
           |window.__kyoHostChannels = window.__kyoHostChannels || {};
           |window.__kyoHostPending = window.__kyoHostPending || {};
           |window.__kyoHostChannelRegister = function(p, rx) {
           |  window.__kyoHostChannels[p] = rx;
           |  var pend = window.__kyoHostPending[p];
           |  if (pend) { delete window.__kyoHostPending[p]; for (var i = 0; i < pend.length; i++) rx(pend[i]); }
           |};
           |// Open the WS and route each inbound HostUpdate op to its per-path receiver (or buffer it).
           |try {
           |  var proto = location.protocol === "https:" ? "wss:" : "ws:";
           |  var ws = new WebSocket(proto + "//" + location.host + "/_kyo/ws");
           |  ws.onopen = function() { window.__wsOpen = true; };
           |  ws.onmessage = function(ev) {
           |    var op;
           |    try { op = JSON.parse(ev.data); } catch (e) { return; }
           |    if (op && op.HostUpdate) {
           |      window.__feedCount++;
           |      try { window.__feedLog.push(JSON.stringify(op.HostUpdate.payload)); } catch (e) {}
           |      var p = op.HostUpdate.path.join(".");
           |      var rx = window.__kyoHostChannels && window.__kyoHostChannels[p];
           |      if (rx) { window.__hadReceiver = true; rx(op.HostUpdate.payload); }
           |      else {
           |        var q = window.__kyoHostPending[p] = window.__kyoHostPending[p] || [];
           |        q.push(op.HostUpdate.payload);
           |        if (q.length > 4096) q.shift();
           |      }
           |    }
           |  };
           |} catch (e) { window.__mountError = "ws: " + String(e && e.message ? e.message : e); }
           |// Mount the spinning cube + connect the feed mirror.
           |try {
           |  const { mountFeedClock } = await import("/main.js");
           |  mountFeedClock("#app");
           |  window.__mounted = true;
           |} catch (e) {
           |  window.__mountError = String(e && e.message ? e.message : e);
           |}
           |// Per-frame sampler: copy the WebGL canvas onto a 2D canvas inside requestAnimationFrame (the
           |// backbuffer is valid for a drawImage taken in the rAF callback) and read the center pixel,
           |// classifying it to a dominant-channel color name. This samples the REAL rendered scene, so a
           |// color step in the log proves the server feed changed the cube, not just that a message arrived.
           |const src = document.getElementById("app");
           |const cap = document.createElement("canvas");
           |cap.width = 64; cap.height = 64;
           |const ctx = cap.getContext("2d");
           |function channelName(r, g, b) {
           |  // Near-black/background -> no render yet.
           |  if (r < 40 && g < 40 && b < 40) return "none";
           |  // Remove the achromatic (white) component so ambient/specular lighting does not pull a
           |  // low-luminance hue (blue) toward white; classify the remaining chroma by dominance.
           |  var m = Math.min(r, g, b);
           |  r -= m; g -= m; b -= m;
           |  var hi = Math.max(r, g, b);
           |  var rOn = r > hi * 0.55, gOn = g > hi * 0.55, bOn = b > hi * 0.55;
           |  if (rOn && gOn && !bOn) return "yellow";
           |  if (rOn && bOn && !gOn) return "magenta";
           |  if (gOn && bOn && !rOn) return "cyan";
           |  if (rOn && !gOn && !bOn) return "red";
           |  if (gOn && !rOn && !bOn) return "green";
           |  if (bOn && !rOn && !gOn) return "blue";
           |  return "mixed";
           |}
           |function sample() {
           |  try {
           |    ctx.drawImage(src, 0, 0, cap.width, cap.height);
           |    var d = ctx.getImageData(cap.width / 2, cap.height / 2, 1, 1).data;
           |    window.__colorLog.push(channelName(d[0], d[1], d[2]));
           |  } catch (e) { /* a transient read failure is skipped, not fatal */ }
           |  requestAnimationFrame(sample);
           |}
           |requestAnimationFrame(sample);
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeFeedClockBrowserTest
