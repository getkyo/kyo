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

    def resolveSafe(storageDir: String, path: String): Option[java.nio.file.Path] =
        val resolved = java.nio.file.Paths.get(storageDir, path).normalize()
        if resolved.startsWith(storageDir) then Some(resolved)
        else None
    end resolveSafe

    run {
        val port       = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        val storageDir = java.nio.file.Files.createTempDirectory("staticsite").toString

        // Seed sample files
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get(storageDir, "index.html"),
            "<html><body><h1>Welcome to StaticSite</h1></body></html>"
        )
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get(storageDir, "style.css"),
            "body { font-family: sans-serif; margin: 2rem; }"
        )
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get(storageDir, "data.json"),
            """{"message":"hello from static site","version":1}"""
        )

        // GET /files/... — serve file with ETag caching
        val serve = HttpRoute
            .getRaw("files" / HttpPath.Rest("path"))
            .filter(serverFilter)
            .request(_.headerOpt[String]("if-none-match"))
            .response(_.bodyText.error[ApiError](HttpStatus.NotFound))
            .metadata(_.summary("Serve a file").description("Supports ETag/If-None-Match for 304.").tag("files"))
            .handler { req =>
                resolveSafe(storageDir, req.fields.path) match
                    case None => HttpResponse.halt(HttpResponse.forbidden)
                    case Some(filePath) =>
                        if !java.nio.file.Files.exists(filePath) || java.nio.file.Files.isDirectory(filePath) then
                            Abort.fail(ApiError(s"File not found: ${req.fields.path}"))
                        else
                            val bytes    = java.nio.file.Files.readAllBytes(filePath)
                            val etag     = computeEtag(bytes)
                            val fileName = filePath.getFileName.toString
                            req.fields.`if-none-match` match
                                case Present(clientEtag) if clientEtag == etag =>
                                    HttpResponse.halt(HttpResponse.notModified.etag(etag).cacheControl("public, max-age=86400"))
                                case _ =>
                                    HttpResponse.okText(new String(bytes, "UTF-8"))
                                        .etag(etag)
                                        .cacheControl("public, max-age=86400")
                                        .contentDisposition(fileName, isInline(fileName))
                            end match
            }

        // HEAD /files/... — file metadata without body
        val head = HttpRoute
            .headRaw("files" / HttpPath.Rest("path"))
            .filter(serverFilter)
            .metadata(_.summary("File metadata").tag("files"))
            .handler { req =>
                resolveSafe(storageDir, req.fields.path) match
                    case None => HttpResponse.forbidden
                    case Some(filePath) =>
                        if !java.nio.file.Files.exists(filePath) then HttpResponse.notFound
                        else
                            val bytes = java.nio.file.Files.readAllBytes(filePath)
                            HttpResponse.ok
                                .etag(computeEtag(bytes))
                                .setHeader("Content-Length", bytes.length.toString)
                                .cacheControl("public, max-age=86400")
            }

        // GET /list — list all files
        val list = HttpHandler.getJson[FileList]("list") { _ =>
            val dir    = java.nio.file.Paths.get(storageDir)
            val stream = java.nio.file.Files.walk(dir)
            try
                val files = stream.toArray.toList
                    .map(_.asInstanceOf[java.nio.file.Path])
                    .filter(java.nio.file.Files.isRegularFile(_))
                    .map { path =>
                        val bytes = java.nio.file.Files.readAllBytes(path)
                        FileInfo(dir.relativize(path).toString, bytes.length.toLong, computeEtag(bytes))
                    }
                FileList(files, storageDir)
            finally stream.close()
            end try
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
                        val path     = java.nio.file.Paths.get(storageDir, fileName)
                        java.nio.file.Files.createDirectories(path.getParent)
                        java.nio.file.Files.write(path, bytes)
                        HttpResponse.okJson(FileInfo(fileName, bytes.length.toLong, computeEtag(bytes)))
                    case None =>
                        Abort.fail(ApiError("Missing 'file' part in multipart upload"))
            }

        val health = HttpHandler.health()

        for
            server <- HttpServer.init(
                HttpServer.Config().port(port).maxContentLength(10 * 1024 * 1024)
                    .openApi("/openapi.json", "Static Site Server")
            )(serve, head, list, upload, health)
            _ <- Console.printLine(s"StaticSite running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  Serving files from: $storageDir")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/files/index.html")
            _ <- Console.printLine(s"  curl -I http://localhost:${server.port}/files/style.css")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/list")
            _ <- Console.printLine(s"  curl -X POST http://localhost:${server.port}/upload -F 'file=@/tmp/test.txt'")
            _ <- server.await
        yield ()
        end for
    }
end StaticSite
