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

    val serverFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.cors())

    def routes(store: AtomicRef[Map[String, Array[Byte]]]) =

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
                        store.updateAndGet(_.updated(fileName, bytes)).map { _ =>
                            HttpResponse.okJson(FileInfo(fileName, bytes.length.toLong))
                        }
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
                store.get.map { files =>
                    files.get(req.fields.name) match
                        case Some(bytes) =>
                            HttpResponse.okBinary(Span.fromUnsafe(bytes))
                        case None =>
                            Abort.fail(ApiError(s"File not found: ${req.fields.name}"))
                }
            }

        val listRoute = HttpRoute
            .getRaw("list")
            .filter(serverFilter)
            .response(_.bodyJson[FileList])
            .metadata(_.summary("List all files").tag("files"))
            .handler { _ =>
                store.get.map { files =>
                    val entries = files.toList.sortBy(_._1).map { (name, bytes) =>
                        FileInfo(name, bytes.length.toLong)
                    }
                    HttpResponse.okJson(FileList(entries))
                }
            }

        (uploadRoute, downloadRoute, listRoute)
    end routes

    val health = HttpHandler.health()

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            store <- AtomicRef.init(Map.empty[String, Array[Byte]])
            (uploadRoute, downloadRoute, listRoute) = routes(store)
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
