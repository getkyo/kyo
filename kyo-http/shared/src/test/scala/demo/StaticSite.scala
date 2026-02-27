package demo

import kyo.*

/** Static file server with proper HTTP caching semantics.
  *
  * Demonstrates: HttpPath.Rest (catch-all file paths), etag + cacheControl + notModified (304), contentDisposition (inline vs attachment),
  * HEAD method, HttpResponse.forbidden / notFound, path traversal protection, multipart upload, OpenAPI.
  */
object StaticSite extends KyoApp:

    case class FileInfo(name: String, size: Long, etag: String) derives Schema
    case class FileList(files: List[FileInfo], directory: String) derives Schema
    case class ApiError(error: String) derives Schema

    val serverFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.securityHeaders(csp = Present("default-src 'self'")))
        .andThen(HttpFilter.server.cors())

    def computeEtag(bytes: Array[Byte]): String =
        val hash = java.util.Arrays.hashCode(bytes).toHexString
        s""""file-$hash""""

    val inlineExtensions = Set("txt", "html", "css", "js", "json", "xml", "svg", "md")

    def isInline(name: String): Boolean =
        val ext = name.lastIndexOf('.') match
            case -1 => ""
            case i  => name.substring(i + 1).toLowerCase
        inlineExtensions.contains(ext)
    end isInline

    /** Resolve a path safely, preventing directory traversal. Returns None if the path escapes the root. */
    def resolveSafe(path: String): Option[String] =
        val segments = path.split("/").filter(s => s.nonEmpty && s != ".")
        if segments.exists(_ == "..") then None
        else Some(segments.mkString("/"))
    end resolveSafe

    def handlers(store: AtomicRef[Map[String, Array[Byte]]]) =

        // GET /files/... — serve file with ETag caching
        val serve = HttpRoute
            .getRaw("files" / HttpPath.Rest("path"))
            .filter(serverFilter)
            .request(_.headerOpt[String]("if-none-match"))
            .response(_.bodyText.error[ApiError](HttpStatus.NotFound))
            .metadata(_.summary("Serve a file").description("Supports ETag/If-None-Match for 304.").tag("files"))
            .handler { req =>
                resolveSafe(req.fields.path) match
                    case None => HttpResponse.halt(HttpResponse.forbidden)
                    case Some(safePath) =>
                        store.get.map { files =>
                            files.get(safePath) match
                                case None => Abort.fail(ApiError(s"File not found: ${req.fields.path}"))
                                case Some(bytes) =>
                                    val etag     = computeEtag(bytes)
                                    val fileName = safePath.split("/").last
                                    req.fields.`if-none-match` match
                                        case Present(clientEtag) if clientEtag == etag =>
                                            HttpResponse.halt(
                                                HttpResponse.notModified.etag(etag).cacheControl("public, max-age=86400")
                                            )
                                        case _ =>
                                            HttpResponse.okText(new String(bytes, "UTF-8"))
                                                .etag(etag)
                                                .cacheControl("public, max-age=86400")
                                                .contentDisposition(fileName, isInline(fileName))
                                    end match
                        }
            }

        // HEAD /files/... — file metadata without body
        val head = HttpRoute
            .headRaw("files" / HttpPath.Rest("path"))
            .filter(serverFilter)
            .metadata(_.summary("File metadata").tag("files"))
            .handler { req =>
                resolveSafe(req.fields.path) match
                    case None => HttpResponse.forbidden
                    case Some(safePath) =>
                        store.get.map { files =>
                            files.get(safePath) match
                                case None => HttpResponse.notFound
                                case Some(bytes) =>
                                    HttpResponse.ok
                                        .etag(computeEtag(bytes))
                                        .setHeader("Content-Length", bytes.length.toString)
                                        .cacheControl("public, max-age=86400")
                        }
            }

        // GET /list — list all files
        val list = HttpHandler.getJson[FileList]("list") { _ =>
            store.get.map { files =>
                val entries = files.toList.sortBy(_._1).map { (name, bytes) =>
                    FileInfo(name, bytes.length.toLong, computeEtag(bytes))
                }
                FileList(entries, "(in-memory)")
            }
        }

        // POST /upload — multipart file upload
        val upload = HttpRoute
            .postRaw("upload")
            .filter(serverFilter)
            .request(_.bodyMultipart)
            .response(_.bodyJson[FileInfo].status(HttpStatus.Created))
            .metadata(_.summary("Upload a file").tag("files"))
            .handler { req =>
                req.fields.body.find(_.name == "file") match
                    case Some(filePart) =>
                        val fileName = filePart.filename.getOrElse("upload")
                        val bytes    = filePart.data.toArrayUnsafe
                        store.updateAndGet(_.updated(fileName, bytes)).map { _ =>
                            HttpResponse.okJson(FileInfo(fileName, bytes.length.toLong, computeEtag(bytes)))
                        }
                    case None =>
                        Abort.fail(ApiError("Missing 'file' part in multipart upload"))
            }

        (serve, head, list, upload)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)

        // Seed sample files
        val seedFiles = Map(
            "index.html" -> "<html><body><h1>Welcome to StaticSite</h1></body></html>".getBytes("UTF-8"),
            "style.css"  -> "body { font-family: sans-serif; margin: 2rem; }".getBytes("UTF-8"),
            "data.json"  -> """{"message":"hello from static site","version":1}""".getBytes("UTF-8")
        )

        for
            store <- AtomicRef.init(seedFiles)
            (serve, head, list, upload) = handlers(store)
            health                      = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).maxContentLength(10 * 1024 * 1024)
                    .openApi("/openapi.json", "Static Site Server")
            )(serve, head, list, upload, health)
            _ <- Console.printLine(s"StaticSite running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/files/index.html")
            _ <- Console.printLine(s"  curl -I http://localhost:${server.port}/files/style.css")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/list")
            _ <- Console.printLine(s"  curl -X POST http://localhost:${server.port}/upload -F 'file=@/tmp/test.txt'")
            _ <- server.await
        yield ()
        end for
    }
end StaticSite
