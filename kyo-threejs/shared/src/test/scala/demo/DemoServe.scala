package demo

import kyo.*

/** Shared serving configuration for the self-serving demo `KyoApp`s. Each live-scene demo extends
  * `KyoApp`, builds its scene into a kyo-ui tree, and serves that tree over kyo-ui's server-push
  * transport. [[DemoServe.head]] links the client island bundle into the SSR page and
  * [[DemoServe.islandHandler]] serves the bundle bytes.
  *
  * The island is the linked `kyo-threejs-demos` Scala.js output (its `kyoThreeIsland` entry mounts
  * every server-pushed 3D host on the page). The bundle is read from disk when the handler runs, so
  * a demo launches against whatever bundle the last `kyo-threejs-demos/fastLinkJS` produced without
  * the demo itself depending on the link step.
  */
object DemoServe:

    /** The served route of the client island bundle. The page links it as a module script and
      * [[islandHandler]] serves the bytes at the same route.
      */
    val islandPath: String = "/_kyo/island.js"

    /** The page head that links the client island bundle. `moduleScript` emits a
      * `<script type="module" src="/_kyo/island.js">` into the SSR page, so the browser loads the
      * island that mounts the server-pushed 3D hosts.
      */
    def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs demo", moduleScript = Present(islandPath))

    /** Serves the linked `kyo-threejs-demos` bundle bytes at [[islandPath]] with
      * `Content-Type: application/javascript`. The bundle must be linked first with
      * `sbt 'kyo-threejs-demos/fastLinkJS'`; the bytes are read at request time. When the bundle is
      * absent (the link step has not run), the handler responds with a `500` whose body names the
      * link command, so the missing-bundle case is a clear server error rather than a blank page.
      */
    def islandHandler(using Frame): HttpHandler[Any, "body" ~ Span[Byte], Nothing] =
        HttpRoute.getBinary(islandPath).handler { _ =>
            Abort.run[FileException](bundleBytes).map {
                case Result.Success(Present(bytes)) =>
                    HttpResponse.ok(bytes).setHeader("Content-Type", "application/javascript")
                case _ =>
                    val message =
                        "island bundle not found under kyo-threejs/demos/target; run " +
                            "'sbt kyo-threejs-demos/fastLinkJS' first"
                    HttpResponse(HttpStatus.InternalServerError)
                        .addField("body", Span.from(message.getBytes("UTF-8")))
                        .setHeader("Content-Type", "text/plain")
            }
        }

    /** Reads the linked demos bundle (`main.js`) from the `kyo-threejs-demos` fastLink output tree,
      * or `Absent` when no linked bundle exists yet. The output lands under
      * `kyo-threejs/demos/target/scala-<ver>/kyo-threejs-demos-fastopt/main.js`; the `scala-<ver>`
      * segment is discovered by listing the target directory so the version is not hard-coded.
      */
    private def bundleBytes(using Frame): Maybe[Span[Byte]] < (Sync & Abort[FileException]) =
        val demosTarget = Path("kyo-threejs", "demos", "target")
        demosTarget.exists.map {
            case false => Absent
            case true =>
                demosTarget.list.map { entries =>
                    val candidates = entries.filter(p => p.name.exists(_.startsWith("scala-")))
                        .map(_ / "kyo-threejs-demos-fastopt" / "main.js")
                    locateExisting(candidates, 0)
                }
        }
    end bundleBytes

    private def locateExisting(candidates: Chunk[Path], i: Int)(using
        Frame
    ): Maybe[Span[Byte]] < (Sync & Abort[FileException]) =
        if i >= candidates.length then Absent
        else
            val candidate = candidates(i)
            candidate.exists.map {
                case true  => candidate.readBytes.map(Present(_))
                case false => locateExisting(candidates, i + 1)
            }
    end locateExisting

end DemoServe
