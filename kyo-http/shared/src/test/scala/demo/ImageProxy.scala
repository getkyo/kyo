package demo

import kyo.*

/** Binary content proxy with caching, custom timing filter, and content-disposition.
  *
  * Server: POST binary data to store it, GET to retrieve. A custom filter injects X-Processing-Time on every response. A legacy endpoint is
  * marked deprecated in OpenAPI. Client: uploads binary, downloads it back, verifies round-trip.
  *
  * Demonstrates: HttpHandler.getBinary, HttpHandler.postBinary, HttpClient.getBinary, HttpClient.postBinary, custom user-written filter
  * (Passthrough), noCache/noStore, contentDisposition, OpenAPI deprecated, OpenAPI externalDocs.
  */
object ImageProxy extends KyoApp:

    case class ImageMeta(id: Int, name: String, size: Int) derives Schema

    case class NotFound(error: String) derives Schema

    case class Store(images: Map[Int, (ImageMeta, Span[Byte])], nextId: Int)

    val timingFilter =
        new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                Clock.stopwatch.map { sw =>
                    next(request).map { res =>
                        sw.elapsed.map { dur =>
                            res.setHeader("X-Processing-Time", s"${dur.toMillis}ms")
                        }
                    }
                }

    def handlers(storeRef: AtomicRef[Store]) =

        val upload = HttpRoute
            .postRaw("images")
            .request(_.bodyBinary)
            .response(_.bodyBinary.status(HttpStatus.Created))
            .filter(timingFilter)
            .metadata(_.copy(
                summary = Present("Upload binary data"),
                tags = Seq("images"),
                externalDocsUrl = Present("https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST"),
                externalDocsDesc = Present("MDN POST reference")
            ))
            .handler { req =>
                val bytes = req.fields.body
                storeRef.updateAndGet { s =>
                    val meta = ImageMeta(s.nextId, s"image-${s.nextId}.bin", bytes.size)
                    Store(s.images + (s.nextId -> (meta, bytes)), s.nextId + 1)
                }.map(_ => HttpResponse.okBinary(bytes).noStore)
            }

        val download = HttpRoute
            .getRaw("images" / HttpPath.Capture[Int]("id"))
            .response(
                _.bodyBinary
                    .error[NotFound](HttpStatus.NotFound)
            )
            .filter(timingFilter)
            .metadata(_.copy(
                summary = Present("Download a stored image"),
                tags = Seq("images")
            ))
            .handler { req =>
                storeRef.get.map { store =>
                    store.images.get(req.fields.id) match
                        case Some((meta, bytes)) =>
                            HttpResponse.okBinary(bytes)
                                .contentDisposition(meta.name)
                                .cacheControl("public, max-age=3600")
                        case None =>
                            Abort.fail(NotFound(s"Image ${req.fields.id} not found"))
                }
            }

        val list = HttpHandler.getJson[List[ImageMeta]]("images") { _ =>
            storeRef.get.map(_.images.values.toList.map(_._1).sortBy(_.id))
        }

        // Legacy endpoint marked as deprecated
        val downloadLegacy = HttpRoute
            .getRaw("files" / HttpPath.Capture[Int]("id"))
            .response(
                _.bodyBinary
                    .error[NotFound](HttpStatus.NotFound)
            )
            .filter(timingFilter)
            .metadata(_.copy(
                summary = Present("Download (deprecated — use /images/:id)"),
                tags = Seq("legacy"),
                deprecated = true
            ))
            .handler { req =>
                storeRef.get.map { store =>
                    store.images.get(req.fields.id) match
                        case Some((meta, bytes)) =>
                            HttpResponse.okBinary(bytes)
                                .noCache
                                .contentDisposition(meta.name)
                        case None =>
                            Abort.fail(NotFound(s"File ${req.fields.id} not found"))
                }
            }

        (upload, download, list, downloadLegacy)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (upload, download, list, downloadLegacy) = handlers(storeRef)
            health                                   = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "ImageProxy")
            )(upload, download, list, downloadLegacy, health)
            _ <- Console.printLine(s"ImageProxy running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/images -H "Content-Type: application/octet-stream" --data-binary @photo.jpg"""
            )
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/images/1 -o downloaded.bin")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/images")
            _ <- server.await
        yield ()
        end for
    }
end ImageProxy

/** Client that exercises ImageProxy binary upload/download round-trip.
  *
  * Demonstrates: HttpClient.postBinary, HttpClient.getBinary, binary round-trip verification, withConfig baseUrl.
  */
object ImageProxyClient extends KyoApp:

    import ImageProxy.*

    run {
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (upload, download, list, downloadLegacy) = handlers(storeRef)
            server <- HttpServer.init(HttpServer.Config().port(0))(upload, download, list, downloadLegacy)
            _      <- Console.printLine(s"ImageProxyClient started server on http://localhost:${server.port}")

            _ <- HttpClient.withConfig(_.baseUrl(s"http://localhost:${server.port}").timeout(5.seconds)) {
                for
                    // Create some binary data
                    payload = Span.from("Hello, binary world!".getBytes("UTF-8"))

                    _     <- Console.printLine("\n=== Uploading binary ===")
                    echod <- HttpClient.postBinary("/images", payload)
                    _     <- Console.printLine(s"  Uploaded ${payload.size} bytes, echo: ${echod.size} bytes")

                    _          <- Console.printLine("\n=== Downloading binary ===")
                    downloaded <- HttpClient.getBinary("/images/1")
                    _          <- Console.printLine(s"  Downloaded: ${downloaded.size} bytes")
                    _          <- Console.printLine(s"  Content: ${new String(downloaded.toArray, "UTF-8")}")

                    match_ = payload.size == downloaded.size
                    _ <- Console.printLine(s"  Round-trip match: $match_")

                    _    <- Console.printLine("\n=== Listing images ===")
                    imgs <- HttpClient.getJson[List[ImageMeta]]("/images")
                    _ <- Kyo.foreach(imgs) { m =>
                        Console.printLine(s"  [${m.id}] ${m.name} (${m.size} bytes)")
                    }

                    _           <- Console.printLine("\n=== Legacy endpoint (deprecated) ===")
                    legacyBytes <- HttpClient.getBinary("/files/1")
                    _           <- Console.printLine(s"  Legacy download: ${legacyBytes.size} bytes")
                yield ()
            }

            _ <- server.closeNow
            _ <- Console.printLine("\nDone.")
        yield ()
        end for
    }
end ImageProxyClient
