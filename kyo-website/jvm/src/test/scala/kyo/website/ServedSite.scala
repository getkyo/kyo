package kyo.website

import kyo.*

/** Emits the site from the live repository with the real `fullLinkJS` bundle and serves it over localhost. Nothing is
  * stubbed: the docs sidebar renders one reactive region per module, so only the real content gives the real count.
  */
private[website] object ServedSite:

    private val version = WebsiteVersion("v1.0.0", "1.0.0", latest = true)

    private val contentTypes: Map[String, String] = Map(
        "html" -> "text/html; charset=utf-8",
        "js"   -> "text/javascript; charset=utf-8",
        "css"  -> "text/css; charset=utf-8",
        "json" -> "application/json; charset=utf-8",
        "map"  -> "application/json; charset=utf-8",
        "svg"  -> "image/svg+xml",
        "png"  -> "image/png",
        "ico"  -> "image/x-icon",
        "xml"  -> "application/xml; charset=utf-8",
        "txt"  -> "text/plain; charset=utf-8"
    )

    // A browser refuses a `<script type="module">` served as `application/octet-stream`, and the SPA never mounts.
    private def contentTypeOf(key: String): String =
        val ext = key.lastIndexOf('.') match
            case -1 => ""
            case i  => key.substring(i + 1).toLowerCase
        contentTypes.getOrElse(ext, "application/octet-stream")
    end contentTypeOf

    /** Runs `f` with the base URL, no trailing slash. */
    def serve[A, S](f: String => A < (Async & S))(using
        Frame
    ): A < (Async & Scope & Abort[WebsiteException | FileSystemException | HttpBindException] & S) =
        for
            root      <- repoRoot
            bundleDir <- bundleDir(root)
            outDir    <- Path.run(Path.tempDir("kyo-website-site"))
            content   <- WebsiteContent.fromRepo(root, version)
            _         <- WebsiteGenerator.emit(Chunk(content), outDir, WebsiteGenerator.Config(root, bundleDir))
            store     <- loadDir(outDir)
            rootHandler = HttpRoute.getRaw("").response(_.bodyBinary).handler(_ => respond("", store))
            restHandler = HttpRoute.getRaw(Capture.Rest("path")).response(_.bodyBinary)
                .handler(req => respond(req.fields.path, store))
            server <- HttpServer.init(0, "localhost")(rootHandler, restHandler)
            result <- f(s"http://localhost:${server.port}")
        yield result

    // Read once up front so the filesystem is never inside a measurement window.
    private def loadDir(root: Path)(using Frame): Map[String, Span[Byte]] < (Async & Abort[FileSystemException]) =
        val depth = root.parts.size
        Path.runReadOnly(
            Scope.run(root.walk.run).map { entries =>
                Kyo.foreach(entries) { entry =>
                    entry.isDirectory.map {
                        case true  => Absent
                        case false => entry.readBytes.map(bytes => Present(entry.parts.drop(depth).mkString("/") -> bytes))
                    }
                }.map(pairs => pairs.collect { case Present(kv) => kv }.toMap)
            }
        )
    end loadDir

    // Resolves the way GitHub Pages does: a directory path maps to its `index.html`.
    private def resolve(rawPath: String, store: Map[String, Span[Byte]]): Maybe[(String, Span[Byte])] =
        val clean      = rawPath.takeWhile(_ != '?').stripPrefix("/")
        val candidates =
            if clean.isEmpty then List("index.html")
            else if clean.endsWith("/") then List(clean + "index.html")
            else List(clean, clean + "/index.html")
        Maybe.fromOption(candidates.iterator.flatMap(key => store.get(key).map(key -> _)).nextOption())
    end resolve

    private def respond(rawPath: String, store: Map[String, Span[Byte]])(using Frame) =
        resolve(rawPath, store) match
            case Present((key, bytes)) =>
                HttpResponse.ok(bytes).setHeader("Content-Type", contentTypeOf(key)).noCache
            case Absent =>
                HttpResponse.notFound(Span.from(s"Not found: $rawPath".getBytes("UTF-8")))
                    .setHeader("Content-Type", "text/plain; charset=utf-8")

    // A forked test's working directory is the module directory, not the repository root.
    private def repoRoot(using Frame): Path < (Sync & Abort[FileSystemException]) =
        def loop(dir: Path): Path < (Sync & Abort[FileSystemException] & PathRead) = (dir / "build.sbt").exists.map {
            case true  => dir
            case false =>
                dir.parent match
                    case Present(parent) => loop(parent)
                    case Absent          => Abort.fail(FileNotFoundException(dir / "build.sbt"))
        }
        Path.runReadOnly(loop(Path(java.lang.System.getProperty("user.dir").nn)))
    end repoRoot

    private def bundleDir(root: Path)(using Frame): Path < (Sync & Abort[FileSystemException]) =
        val targetDir = root / "kyo-website-bundle" / "js" / "target"
        Path.runReadOnly {
            for
                scalaDirs <- childDirsMatching(targetDir, _.startsWith("scala-"))
                optDirs   <- Kyo.foreach(scalaDirs)(childDirsMatching(_, _.endsWith("-opt"))).map(_.flattenChunk)
                flagged   <- Kyo.foreach(optDirs)(d => (d / "main.js").isRegularFile.map(_ -> d))
                dir       <- flagged.collect { case (true, d) => d }.headMaybe match
                    case Present(d) => d: Path < Abort[FileSystemException]
                    case Absent     => Abort.fail(FileNotFoundException(targetDir / "scala-*" / "*-opt" / "main.js"))
            yield dir
        }
    end bundleDir

    private def childDirsMatching(dir: Path, p: String => Boolean)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileSystemException] & PathRead) =
        dir.list.map(entries =>
            Kyo.foreach(entries)(d => d.isDirectory.map(_ -> d)).map(
                _.collect { case (true, d) if d.name.exists(p) => d }.sortBy(_.toString)
            )
        )

end ServedSite
