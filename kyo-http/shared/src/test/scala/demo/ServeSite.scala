package demo

import kyo.*

/** Static-site preview server: serves a generated directory over HTTP.
  *
  * Built for previewing the `kyo-website` output locally (`sbt 'kyo-websiteJVM/run --out site ...'` then
  * `sbt 'kyo-httpJVM/Test/runMain demo.ServeSite site'`), but it serves any directory of static files. It
  * loads the directory into memory once at startup (via [[kyo.Path.walk]]) and answers every GET by
  * resolving the request path to a file, mapping a directory path (`/foo/` or a bare `/foo`) to its
  * `index.html`, so the generated per-route `index.html` tree resolves the way GitHub Pages serves it.
  *
  * Because it snapshots the directory at startup, a re-render needs a restart to be picked up.
  *
  * Demonstrates: [[kyo.Path]] directory walking + byte reads, a [[kyo.HttpPath.Capture.Rest]] catch-all
  * route, binary response bodies with explicit per-extension `Content-Type` headers, and `404.html`
  * fallback.
  */
object ServeSite extends KyoApp:

    /** Default preview port (matches the `kyo-website` README workflow). */
    private val defaultPort = 8474

    /** Content-Type by file extension. `.js`/`.mjs` map to `text/javascript` so the website's ESModule
      * bundle loads (a browser refuses a `<script type="module">` served as `application/octet-stream`).
      */
    private val contentTypes: Map[String, String] = Map(
        "html"  -> "text/html; charset=utf-8",
        "js"    -> "text/javascript; charset=utf-8",
        "mjs"   -> "text/javascript; charset=utf-8",
        "css"   -> "text/css; charset=utf-8",
        "json"  -> "application/json; charset=utf-8",
        "map"   -> "application/json; charset=utf-8",
        "svg"   -> "image/svg+xml",
        "png"   -> "image/png",
        "jpg"   -> "image/jpeg",
        "jpeg"  -> "image/jpeg",
        "gif"   -> "image/gif",
        "webp"  -> "image/webp",
        "ico"   -> "image/x-icon",
        "xml"   -> "application/xml; charset=utf-8",
        "txt"   -> "text/plain; charset=utf-8",
        "woff"  -> "font/woff",
        "woff2" -> "font/woff2",
        "ttf"   -> "font/ttf"
    )

    private def contentTypeOf(key: String): String =
        val ext = key.lastIndexOf('.') match
            case -1 => ""
            case i  => key.substring(i + 1).toLowerCase
        contentTypes.getOrElse(ext, "application/octet-stream")
    end contentTypeOf

    /** Load every regular file under `root` into memory, keyed by its forward-slash path relative to
      * `root` (so `root/latest/kyo-core/index.html` is keyed `latest/kyo-core/index.html`).
      */
    private def loadDir(root: Path)(using Frame): Map[String, Span[Byte]] < (Async & Abort[FileException]) =
        val depth = root.parts.size
        Path.runReadOnly(
            Scope.run(root.walk.run).map { entries =>
                Kyo.foreach(entries) { entry =>
                    entry.isDirectory.map {
                        case true => Absent
                        case false =>
                            entry.readBytes.map(bytes => Present(entry.parts.drop(depth).mkString("/") -> bytes))
                    }
                }.map(pairs => pairs.collect { case Present(kv) => kv }.toMap)
            }
        )
    end loadDir

    /** Resolve a request path to a stored file key: empty/`/` and trailing-slash paths map to `index.html`;
      * an extension-less path falls back to `<path>/index.html` (the per-route directory's index).
      */
    private def resolve(rawPath: String, store: Map[String, Span[Byte]]): Maybe[(String, Span[Byte])] =
        val clean = rawPath.takeWhile(_ != '?').stripPrefix("/")
        val candidates =
            if clean.isEmpty then List("index.html")
            else if clean.endsWith("/") then List(clean + "index.html")
            else List(clean, clean + "/index.html")
        Maybe.fromOption(candidates.iterator.flatMap(key => store.get(key).map(key -> _)).nextOption())
    end resolve

    private def respond(rawPath: String, store: Map[String, Span[Byte]]) =
        resolve(rawPath, store) match
            case Present((key, bytes)) =>
                HttpResponse.ok(bytes).setHeader("Content-Type", contentTypeOf(key)).noCache
            case Absent =>
                store.get("404.html") match
                    case Some(bytes) =>
                        HttpResponse.notFound(bytes).setHeader("Content-Type", "text/html; charset=utf-8").noCache
                    case None =>
                        HttpResponse.notFound(Span.from(s"Not found: $rawPath".getBytes("UTF-8")))
                            .setHeader("Content-Type", "text/plain; charset=utf-8")

    // Two handlers: a root route (`GET /`, which the `Rest` catch-all does not match because the
    // remaining path is empty) serving `index.html`, and the catch-all for every other path.
    private def rootHandler(store: Map[String, Span[Byte]])(using Frame) =
        HttpRoute.getRaw("").filter(HttpFilter.server.logging).response(_.bodyBinary)
            .handler(_ => respond("", store))

    private def restHandler(store: Map[String, Span[Byte]])(using Frame) =
        HttpRoute.getRaw(Capture.Rest("path")).filter(HttpFilter.server.logging).response(_.bodyBinary)
            .handler(req => respond(req.fields.path, store))

    run {
        args.toList match
            case dir :: rest =>
                val port = rest.headOption.flatMap(_.toIntOption).getOrElse(defaultPort)
                Abort.run[FileException](loadDir(Path(dir))).map {
                    case Result.Success(store) =>
                        for
                            server <- HttpServer.init(HttpServerConfig.default.port(port))(rootHandler(store), restHandler(store))
                            _ <- Console.printLine(s"ServeSite: serving '$dir' (${store.size} files) on http://localhost:${server.port}")
                            _ <- server.await
                        yield ()
                    case Result.Failure(e) =>
                        Console.printLine(s"ServeSite: failed to load '$dir': ${e.getMessage}")
                    case p: Result.Panic =>
                        Console.printLine(s"ServeSite: panic: ${p.exception.getMessage}")
                }
            case Nil =>
                Console.printLine("usage: ServeSite <directory> [port]  (default port 8474)")
    }
end ServeSite
