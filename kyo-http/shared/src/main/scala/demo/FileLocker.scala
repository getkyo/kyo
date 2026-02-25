package demo

import kyo.*

/** File upload/download service.
  *
  * Demonstrates: multipart upload, binary download, content-disposition, cache-control.
  */
object FileLocker extends KyoApp:

    case class FileInfo(name: String, size: Long) derives Schema
    case class FileList(files: List[FileInfo]) derives Schema
    case class ApiError(error: String) derives Schema

    val storageDir = java.nio.file.Files.createTempDirectory("filelocker").toString

    val serverFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.cors())

    val uploadRoute = HttpRoute
        .postRaw("upload")
        .filter(serverFilter)
        .request(_.bodyMultipart)
        .response(_.bodyJson[FileInfo].status(HttpStatus.Created))
        .metadata(_.summary("Upload a file").tag("files"))
        .handler { req =>
            val parts = req.fields.body
            parts.find(_.name == "file") match
                case Some(filePart) =>
                    val fileName = filePart.filename.getOrElse("upload")
                    val bytes    = filePart.data.toArrayUnsafe
                    val path     = java.nio.file.Paths.get(storageDir, fileName)
                    java.nio.file.Files.write(path, bytes)
                    HttpResponse.okJson(FileInfo(fileName, bytes.length.toLong))
                case None =>
                    Abort.fail(ApiError("Missing 'file' part in multipart upload"))
            end match
        }

    val downloadRoute = HttpRoute
        .getRaw("files" / HttpPath.Capture[String]("name"))
        .filter(serverFilter)
        .response(_.bodyBinary.error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Download a file").tag("files"))
        .handler { req =>
            val path = java.nio.file.Paths.get(storageDir, req.fields.name)
            if java.nio.file.Files.exists(path) then
                HttpResponse.okBinary(Span.fromUnsafe(java.nio.file.Files.readAllBytes(path)))
            else
                Abort.fail(ApiError(s"File not found: ${req.fields.name}"))
            end if
        }

    val listRoute = HttpHandler.getJson[FileList]("list") { _ =>
        val dir   = java.nio.file.Paths.get(storageDir)
        val files = java.nio.file.Files.list(dir)
        try
            val entries = files.toArray.toList.map { p =>
                val path = p.asInstanceOf[java.nio.file.Path]
                FileInfo(path.getFileName.toString, java.nio.file.Files.size(path))
            }
            FileList(entries)
        finally files.close()
        end try
    }

    val health = HttpHandler.health()

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            _ <- Console.printLine(s"Storage directory: $storageDir")
            server <- HttpServer.init(
                HttpServer.Config().port(port).maxContentLength(10 * 1024 * 1024)
                    .openApi("/openapi.json", "File Locker")
            )(uploadRoute, downloadRoute, listRoute, health)
            _ <- Console.printLine(s"FileLocker running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  echo 'hello world' > /tmp/test.txt")
            _ <- Console.printLine(s"  curl -X POST http://localhost:${server.port}/upload -F 'file=@/tmp/test.txt'")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/files/test.txt")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/list")
            _ <- server.await
        yield ()
        end for
    }
end FileLocker
