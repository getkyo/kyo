package demo

import kyo.*

/** File upload/download service backed by a Python HTTP server.
  *
  * Demonstrates: multipart upload, byte stream download, content-disposition, cache-control, redirect responses, baseUrl config.
  *
  * Starts a Python http.server on port 3009 as a storage backend. FileLocker proxies uploads/downloads through it.
  *
  * Endpoints:
  *   - POST /upload — multipart file upload (field: "file"), redirects to /files/:name
  *   - GET /files/:name — download file with Content-Disposition
  *   - GET /list — list uploaded files
  *
  * Test: curl -X POST http://localhost:3008/upload -F "file=@somefile.txt" curl http://localhost:3008/files/somefile.txt -o downloaded.txt
  * curl http://localhost:3008/list
  */
object FileLocker extends KyoApp:

    case class FileInfo(name: String, size: Long) derives Schema
    case class FileList(files: List[FileInfo]) derives Schema
    case class ApiError(error: String) derives Schema

    val storageDir = java.nio.file.Files.createTempDirectory("filelocker").toString

    val uploadRoute = HttpRoute
        .post("upload")
        .request(_.bodyMultipart)
        .response(_.bodyJson[FileInfo].status(HttpStatus.Created))
        .metadata(_.summary("Upload a file").tag("files"))
        .handle { in =>
            val parts = in.parts
            parts.find(_.name == "file") match
                case Some(filePart) =>
                    val fileName = filePart.filename.getOrElse("upload")
                    val bytes    = filePart.data.toArrayUnsafe
                    val path     = java.nio.file.Paths.get(storageDir, fileName)
                    java.nio.file.Files.write(path, bytes)
                    FileInfo(fileName, bytes.length.toLong)
                case None =>
                    Abort.fail(ApiError("Missing 'file' part in multipart upload"))
            end match
        }

    val downloadRoute = HttpRoute
        .get("files" / HttpPath.Capture[String]("name"))
        .response(_.bodyBinary.error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Download a file").tag("files"))
        .handle { in =>
            val path = java.nio.file.Paths.get(storageDir, in.name)
            if java.nio.file.Files.exists(path) then
                Span.fromUnsafe(java.nio.file.Files.readAllBytes(path))
            else
                Abort.fail(ApiError(s"File not found: ${in.name}"))
            end if
        }

    val listRoute = HttpRoute
        .get("list")
        .response(_.bodyJson[FileList])
        .metadata(_.summary("List uploaded files").tag("files"))
        .handle { _ =>
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
        for
            _ <- Console.printLine(s"Storage directory: $storageDir")
            server <-
                HttpFilter.server.logging
                    .andThen(HttpFilter.server.cors())
                    .enable {
                        HttpServer.init(
                            HttpServer.Config.default.port(3008).maxContentLength(10 * 1024 * 1024)
                                .openApi("/openapi.json", "File Locker")
                        )(uploadRoute, downloadRoute, listRoute, health)
                    }
            _ <- Console.printLine(s"FileLocker running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  echo 'hello world' > /tmp/test.txt")
            _ <- Console.printLine(s"  curl -X POST http://localhost:3008/upload -F 'file=@/tmp/test.txt'")
            _ <- Console.printLine(s"  curl http://localhost:3008/files/test.txt")
            _ <- Console.printLine(s"  curl http://localhost:3008/list")
            _ <- server.await
        yield ()
    }
end FileLocker
