package kyo

import demo.FeedChunkScene
import kyo.Browser.ScreenshotFrame
import kyo.internal.HtmlOp

/** Browser proof of the structural feed `Three.Feed.serverSignal[Chunk[A]]`,
  * end to end over a real WebSocket. The server feeds a CHANGING list of item ids by signal id (add
  * items, remove an item, reorder), and the client scene's mesh COUNT and arrangement change to match,
  * observed from real Chrome pixels.
  *
  *   1. CLIENT scene: a `foreachKeyed` field of colored cubes, one per fed item id, laid out left to right.
  *      The page imports the demos bundle's `mountFeedChunk` entry, which mounts it via `Three.runMount`
  *      and calls `Three.Feed.connectChunk(listId, mirror)`.
  *   2. SERVER feed: the WS pushes `HostPayload.SignalChunk(listId, encoded)` frames stepping the item
  *      list: [0,1,2] -> add 3 -> add 4 -> remove 1 -> reorder. Each push is the whole snapshot; the
  *      client's OWN keyed reconciler diffs it, materializing/disposing cubes so the rendered count tracks
  *      the list length.
  *
  * The proof reads real pixels: a per-frame sampler scans a horizontal strip of the rendered canvas and
  * counts how many distinct cube-colored columns are lit, pushing the count into `window.__countLog`. The
  * test asserts the observed column count steps through the fed list lengths (3 -> 4 -> 5 -> 4) in order,
  * proving the structural feed reshaped the live scene. Frames are saved under `runs/visual-review/feed-chunk/`.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeFeedChunkBrowserTest extends WebGLSceneHarness:

    import ThreeFeedChunkBrowserTest.*

    override def timeout = 180.seconds

    "Three.Feed.serverSignal[Chunk]: the server feeds a changing list and the client mesh count/arrangement matches" in {
        cancelOnUnsupportedPlatform {
            servedChunk { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _        <- Browser.goto(url)
                            _        <- awaitMountError
                            _        <- Browser.waitFor("window.__mounted === true").handle(diagnoseMountTimeout)
                            _        <- Browser.waitFor("window.__wsOpen === true")
                            frames   <- recordFrames
                            _        <- saveFrames(frames)
                            countLog <- readCountLog
                        yield
                            assert(frames.size >= 3, s"expected at least 3 recorded frames, got ${frames.size}")
                            val changedPairs = countChangedPairs(frames)
                            assert(
                                changedPairs >= 2,
                                s"canvas never changed structure: only $changedPairs of ${frames.size - 1} consecutive " +
                                    s"frame pairs changed. Frames saved under runs/visual-review/feed-chunk/"
                            )
                            // ---- STRUCTURAL: the observed cube-column count stepped through the fed list lengths ----
                            val steps = distinctCountSteps(countLog)
                            assert(
                                steps.size >= 3,
                                s"the cube count did not change on the structural feed: observed only ${steps.size} distinct " +
                                    s"count(s) (${steps.mkString(", ")}). The server feeds list lengths ${fedLengths.mkString(", ")}; " +
                                    s"countLog size=${countLog.size}"
                            )
                            assert(
                                isLengthSequenceSlice(steps),
                                s"the observed cube counts ${steps.mkString(", ")} are not an in-order slice of the fed list-length " +
                                    s"sequence ${fedLengths.mkString(", ")}; the mesh count does not match the structural feed"
                            )
                    }
                }
            }
        }
    }

    /** Records a screencast across the recording window (~6s), spanning every server list step. */
    private def recordFrames(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 14000L, maxFrames = 2000) {
            Abort.run[BrowserReadException](Browser.waitFor("window.__never === true", Present(recordWindow))).unit
        }.map { case (frames, _) => frames }

    /** Reads the page's per-frame lit-column counts (`window.__countLog`). */
    private def readCountLog(using Frame): Chunk[Int] < (Browser & Abort[BrowserReadException]) =
        Browser.eval("JSON.stringify(window.__countLog || [])").map { json =>
            Json.decode[Chunk[Int]](json) match
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

    /** Writes each recorded frame as a JPEG under `runs/visual-review/feed-chunk/frame-NNN.jpg`. */
    private def saveFrames(frames: Chunk[ScreenshotFrame])(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val dir = "runs/visual-review/feed-chunk"
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
                Abort.fail(BrowserScriptErrorException(s"feed-chunk mount ready flag never set: mountError='$err'"))
            }
        }(wait)

    /** Serves the demo bundle, three.js (+ OrbitControls jsm so the bundle's facade resolves), the chunk
      * page, and a WebSocket route that steps the fed item list every ~1s as a `SignalChunk` frame. Hands
      * the page URL to `f`.
      */
    private def servedChunk[A](
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
                WebGLSceneHarness.htmlHandler(chunkPage),
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

    /** The WebSocket endpoint the page connects to. On connect it forks a fiber that steps the fed item
      * list through [[fedLists]] every [[serverStepMs]]ms, each step a `HostUpdate(SignalChunk(listId,
      * encoded))`, then races it against the peer close, so the structural feed runs for the connection
      * lifetime and tears down on disconnect.
      */
    private def feedWsHandler(using Frame): HttpHandler[Any, Any, Nothing] =
        HttpHandler.webSocket("/_kyo/ws") { (_, ws) =>
            Async.race(listFeed(ws), ws.onPeerClose)
        }

    /** The server-driven structural feed: an index advanced every [[serverStepMs]]ms; each step encodes the
      * next item list as `Three.Feed.encodeChunkUpdate(listId, list)` (the structural wire leaf) and puts
      * the `HtmlOp.HostUpdate` frame over the WS. The first step replays the initial list, then the
      * add/add/remove/reorder steps follow. A closed socket ends the loop.
      */
    private def listFeed(ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.runPartial[Closed] {
            AtomicInt.init(0).map { idx =>
                Clock.repeatAtInterval(serverStepMs.millis) {
                    idx.getAndIncrement.map { i =>
                        val list    = fedLists(math.min(i, fedLists.size - 1))
                        val payload = Three.Feed.encodeChunkUpdate[Int](FeedChunkScene.listId, list)
                        val op      = HtmlOp.HostUpdate(Seq(FeedChunkScene.listId), payload)
                        ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))
                    }
                }.map(_.get)
            }
        }.unit

    /** Collapses the per-frame column-count log to the ordered sequence of DISTINCT counts (dropping runs
      * of the same count and any 0/transient before the first render), so only the structural steps remain.
      */
    private def distinctCountSteps(log: Chunk[Int]): Seq[Int] =
        log.toSeq.filter(_ > 0).foldLeft(Vector.empty[Int]) { (acc, c) =>
            if acc.lastOption.contains(c) then acc else acc :+ c
        }

    /** True when `steps` is an in-order contiguous slice of the fed list-length sequence, proving the
      * observed mesh-count changes match the server feed (same order, no foreign count).
      */
    private def isLengthSequenceSlice(steps: Seq[Int]): Boolean =
        if steps.isEmpty then false
        else
            val seq = fedLengths
            seq.indices.exists { start =>
                steps.indices.forall(k => start + k < seq.size && steps(k) == seq(start + k))
            }
    end isLengthSequenceSlice

end ThreeFeedChunkBrowserTest

object ThreeFeedChunkBrowserTest:

    /** The server step interval (ms): the server advances the fed item list once per this. */
    private val serverStepMs: Long = 1200L

    /** The recorder window: ~7s, spanning every ~1.2s server list step. */
    private val recordWindow: Schedule = Schedule.fixed(50.millis).take(140)

    /** The fed item-id lists in order: replay the initial three, then add 3, add 4, remove 1, reorder.
      * Each id maps to a fixed color, so the rearrangement is legible and the count tracks the length.
      */
    private val fedLists: Seq[Chunk[Int]] = Seq(
        Chunk(0, 1, 2),
        Chunk(0, 1, 2, 3),
        Chunk(0, 1, 2, 3, 4),
        Chunk(0, 2, 3, 4),
        Chunk(4, 3, 2, 0)
    )

    /** The list lengths in fed order (3, 4, 5, 4, 4): the cube count the client must render at each step. */
    private val fedLengths: Seq[Int] = fedLists.map(_.size)

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

    /** The chunk page: imports the demo bundle's `mountFeedChunk` entry (which mounts the foreachKeyed cube
      * field AND connects the `feed-list` mirror), opens a WebSocket to `/_kyo/ws`, and routes each inbound
      * `HostUpdate` to `window.__kyoHostChannels[path[0]]` exactly as the kyo-ui inline clientJs does, with
      * the same late-registration flush. A per-frame sampler scans a horizontal strip of the rendered canvas
      * and counts the distinct lit cube columns into `window.__countLog`, so the test proves the structural
      * feed changed the rendered mesh count from real pixels.
      */
    private val chunkPage: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs feed chunk</title>
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
           |<canvas id="app" width="768" height="320"></canvas>
           |<script type="module">
           |window.__mounted = false;
           |window.__mountError = "";
           |window.__wsOpen = false;
           |window.__countLog = [];
           |window.__kyoHostChannels = window.__kyoHostChannels || {};
           |window.__kyoHostPending = window.__kyoHostPending || {};
           |window.__kyoHostChannelRegister = function(p, rx) {
           |  window.__kyoHostChannels[p] = rx;
           |  var pend = window.__kyoHostPending[p];
           |  if (pend) { delete window.__kyoHostPending[p]; for (var i = 0; i < pend.length; i++) rx(pend[i]); }
           |};
           |try {
           |  var proto = location.protocol === "https:" ? "wss:" : "ws:";
           |  var ws = new WebSocket(proto + "//" + location.host + "/_kyo/ws");
           |  ws.onopen = function() { window.__wsOpen = true; };
           |  ws.onmessage = function(ev) {
           |    var op;
           |    try { op = JSON.parse(ev.data); } catch (e) { return; }
           |    if (op && op.HostUpdate) {
           |      var p = op.HostUpdate.path.join(".");
           |      var rx = window.__kyoHostChannels && window.__kyoHostChannels[p];
           |      if (rx) { rx(op.HostUpdate.payload); }
           |      else {
           |        var q = window.__kyoHostPending[p] = window.__kyoHostPending[p] || [];
           |        q.push(op.HostUpdate.payload);
           |        if (q.length > 4096) q.shift();
           |      }
           |    }
           |  };
           |} catch (e) { window.__mountError = "ws: " + String(e && e.message ? e.message : e); }
           |try {
           |  const { mountFeedChunk } = await import("/main.js");
           |  mountFeedChunk("#app");
           |  window.__mounted = true;
           |} catch (e) {
           |  window.__mountError = String(e && e.message ? e.message : e);
           |}
           |// Per-frame sampler: copy the WebGL canvas onto a 2D canvas, scan one horizontal row across the
           |// width, and count distinct lit cube "columns" (runs of non-background pixels separated by gaps).
           |// The cube count maps to the lit-column count, so a structural feed that adds/removes a cube
           |// changes this count, proving the live mesh count tracked the fed list from real pixels.
           |const src = document.getElementById("app");
           |const cap = document.createElement("canvas");
           |cap.width = 192; cap.height = 80;
           |const ctx = cap.getContext("2d");
           |function litColumns() {
           |  ctx.drawImage(src, 0, 0, cap.width, cap.height);
           |  var row = ctx.getImageData(0, cap.height / 2, cap.width, 1).data;
           |  var count = 0, inRun = false, runLen = 0;
           |  for (var x = 0; x < cap.width; x++) {
           |    var r = row[x*4], g = row[x*4+1], b = row[x*4+2];
           |    var lit = (r + g + b) > 90; // above the dark background
           |    if (lit) { if (!inRun) { inRun = true; runLen = 0; } runLen++; }
           |    else { if (inRun && runLen >= 2) count++; inRun = false; }
           |  }
           |  if (inRun && runLen >= 2) count++;
           |  return count;
           |}
           |function sample() {
           |  try { window.__countLog.push(litColumns()); } catch (e) {}
           |  requestAnimationFrame(sample);
           |}
           |requestAnimationFrame(sample);
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeFeedChunkBrowserTest
