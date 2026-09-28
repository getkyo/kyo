package kyo

class WhatsAppMediaTest extends BaseWhatsAppTest:

    val token   = "TEST_MEDIA_TOKEN"
    val phoneId = WhatsAppId.PhoneNumberId("106540352242922")
    val mediaId = WhatsAppId.MediaId("2762702944112137")

    def makeConfig(port: Int)(using Frame): WhatsAppConfig =
        WhatsAppConfig(WhatsAppToken(token), phoneId, baseUrl = url(s"http://localhost:$port"))

    def info(link: HttpUrl, size: Long, id: WhatsAppId.MediaId = mediaId): WhatsAppMedia.MediaInfo =
        WhatsAppMedia.MediaInfo(id, link, "image/jpeg", "abc123", ByteSize.fromBytes(size))

    val mediaUrl = "https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1"

    def mediaIdResponse: String = s"""{"id":"${mediaId.value}"}"""

    def mediaInfoBody(fileSize: String, link: String = mediaUrl): String =
        s"""{"messaging_product":"whatsapp","url":"$link","mime_type":"image/jpeg","sha256":"abc123","file_size":$fileSize,"id":"${mediaId.value}"}"""

    def graphErrorBody(code: Int): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Error $code","fbtrace_id":"fb1"}}"""

    def decodeFailure(method: String, failure: WhatsAppDecodeException.Failure, path: Chunk[String], position: Maybe[Int] = Absent)(
        using Frame
    ): Result[WhatsAppException, Nothing] =
        Result.fail(WhatsAppDecodeException(method, WhatsAppDecodeException.Part.Response, failure, path, position))

    def withUploadServer[A, S](responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[Seq[HttpRequest.Part]], Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        Channel.init[Seq[HttpRequest.Part]](1).map { partsCapture =>
            Channel.init[(String, String)](1).map { reqCapture =>
                val route = HttpRoute.postRaw(s"v25.0/${phoneId.value}/media")
                    .request(_.bodyMultipart)
                    .response(_.bodyText)
                val handler = route.handler { req =>
                    partsCapture.put(req.fields.body).andThen(
                        reqCapture.put((req.path, req.headers.get("Authorization").getOrElse(""))).andThen(
                            if statusOk then HttpResponse.ok(responseBody) else HttpResponse.badRequest(responseBody)
                        )
                    )
                }
                HttpServer.init(0, "localhost")(handler).map(s => test(s.port, partsCapture, reqCapture))
            }
        }

    def withGetServer[A, S](path: String, responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        Channel.init[(String, String)](1).map { reqCapture =>
            val handler = HttpRoute.getRaw(path).response(_.bodyText).handler { req =>
                reqCapture.put((req.path, req.headers.get("Authorization").getOrElse(""))).andThen(
                    if statusOk then HttpResponse.ok(responseBody) else HttpResponse.badRequest(responseBody)
                )
            }
            HttpServer.init(0, "localhost")(handler).map(s => test(s.port, reqCapture))
        }

    def withDeleteServer[A, S](path: String, responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        Channel.init[(String, String)](1).map { reqCapture =>
            val handler = HttpRoute.deleteRaw(path).response(_.bodyText).handler { req =>
                reqCapture.put((req.path, req.headers.get("Authorization").getOrElse(""))).andThen(
                    if statusOk then HttpResponse.ok(responseBody) else HttpResponse.badRequest(responseBody)
                )
            }
            HttpServer.init(0, "localhost")(handler).map(s => test(s.port, reqCapture))
        }

    /** A media host serving `bytes` at `/bytes`, and the Graph server whose media info points at it. */
    def withMediaHost[A, S](bytes: Array[Byte], reqId: WhatsAppId.MediaId)(test: Int => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException]) =
        val bytesHandler = HttpRoute.getRaw("bytes").response(_.bodyBinary).handler(_ => HttpResponse.ok(Span.from(bytes)))
        HttpServer.init(0, "localhost")(bytesHandler).map { host =>
            val infoBody    = mediaInfoBody(s"\"${bytes.length}\"", s"http://localhost:${host.port}/bytes")
            val metaHandler = HttpRoute.getRaw(s"v25.0/${reqId.value}").response(_.bodyText).handler(_ => HttpResponse.ok(infoBody))
            HttpServer.init(0, "localhost")(metaHandler).map(meta => test(meta.port))
        }
    end withMediaHost

    "MediaInfo's toString redacts the pre-signed query and keeps the rest" in {
        val signature = Seq("SECRET", "QUERY", "c47e90").mkString("-")
        val signed    = info(url(s"https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1&hash=$signature&ext=1"), 3)
        assert(signed.toString ==
            s"MediaInfo(${mediaId.value},https://lookaside.fbsbx.com/whatsapp_business/attachments/?<redacted>,image/jpeg,abc123,${ByteSize.fromBytes(3).show})")
        assert(!signed.toString.contains(signature))
        val plain = signed.copy(url = url("https://lookaside.fbsbx.com/a"))
        assert(plain.toString ==
            s"MediaInfo(${mediaId.value},https://lookaside.fbsbx.com/a,image/jpeg,abc123,${ByteSize.fromBytes(3).show})")
    }

    "the MediaInfo resolveUrl returns renders no pre-signed query" in {
        val signature = Seq("SECRET", "QUERY", "19f3aa").mkString("-")
        withGetServer(s"v25.0/${mediaId.value}", mediaInfoBody("\"3\"", s"https://lookaside.fbsbx.com/m/?hash=$signature")) { (port, _) =>
            WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(mediaId)).map { resolved =>
                assert(resolved.url == url(s"https://lookaside.fbsbx.com/m/?hash=$signature"))
                assert(!resolved.toString.contains(signature))
            }
        }
    }

    "downloadFrom sends nothing to a url that is not an absolute http or https url on a host, whatever base url the caller set" in {
        AtomicInt.init(0).map { hits =>
            val route = HttpRoute.getRaw("steal").response(_.bodyText).handler(_ => hits.incrementAndGet.andThen(HttpResponse.ok("bytes")))
            HttpServer.init(0, "localhost")(route).map { server =>
                HttpClient.withConfig(_.baseUrl(s"http://localhost:${server.port}")) {
                    WhatsApp.let(makeConfig(1))(Abort.run[WhatsAppException](WhatsAppMedia.downloadFrom(info(url("/steal"), 3))))
                }.map { result =>
                    hits.get.map { count =>
                        assert(count == 0, s"the server received $count requests carrying the token")
                        assert(result.map(_ => ()) == Result.fail(WhatsAppRefusedUrlException("downloadFrom")))
                    }
                }
            }
        }
    }

    "downloadFrom refuses a unix socket, a scheme other than http or https, and a host outside printable ASCII" in {
        val socket  = HttpUrl(Present("http"), "localhost", 80, "/media", Absent, Present("/tmp/graph.sock"))
        val ftp     = HttpUrl(Present("ftp"), "lookaside.fbsbx.com", 21, "/media", Absent)
        val unicode = HttpUrl(Present("https"), "lookasíde.test", 443, "/media", Absent)
        WhatsApp.let(makeConfig(1)) {
            Kyo.foreach(Chunk(socket, ftp, unicode))(u => Abort.run[WhatsAppException](WhatsAppMedia.downloadFrom(info(u, 3))))
        }.map { results =>
            assert(results.map(_.map(_ => ())) == Chunk.fill(3)(Result.fail(WhatsAppRefusedUrlException("downloadFrom"))))
        }
    }

    "resolveUrl and delete send nothing for a media id that is not one path segment" in {
        AtomicInt.init(0).map { hits =>
            val route = HttpRoute.getRaw("v25.0" / "123" / "extra").response(_.bodyText).handler(_ =>
                hits.incrementAndGet.andThen(HttpResponse.ok("{}"))
            )
            HttpServer.init(0, "localhost")(route).map { server =>
                WhatsApp.let(makeConfig(server.port)) {
                    for
                        resolved <- Abort.run[WhatsAppException](WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("123/extra")))
                        dotted   <- Abort.run[WhatsAppException](WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("..")))
                        empty    <- Abort.run[WhatsAppException](WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("")))
                        deleted  <- Abort.run[WhatsAppException](WhatsAppMedia.delete(WhatsAppId.MediaId("123/extra")))
                        count    <- hits.get
                    yield
                        assert(count == 0, s"the server received $count requests carrying the token")
                        assert(resolved == Result.fail(WhatsAppRefusedUrlException("resolveUrl")))
                        assert(dotted == Result.fail(WhatsAppRefusedUrlException("resolveUrl")))
                        assert(empty == Result.fail(WhatsAppRefusedUrlException("resolveUrl")))
                        assert(deleted == Result.fail(WhatsAppRefusedUrlException("delete")))
                }
            }
        }
    }

    "upload posts three named multipart parts and returns the media id" in {
        val bytes = Array[Byte](10, 20, 30, 40)
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsAppMedia.upload(Span.from(bytes), WhatsAppMedia.MediaType.ImageJpeg, Present("photo.jpg")).map { result =>
                    partsCapture.take.map { parts =>
                        assert(parts.size == 3)
                        val mp   = parts.find(_.name == "messaging_product").get
                        val tp   = parts.find(_.name == "type").get
                        val file = parts.find(_.name == "file").get
                        assert(new String(mp.data.toArray, "UTF-8") == "whatsapp")
                        assert(new String(tp.data.toArray, "UTF-8") == "image/jpeg")
                        assert(file.filename == Present("photo.jpg"))
                        assert(file.contentType == Present("image/jpeg"))
                        assert(file.data.toArray sameElements bytes)
                        assert(result == mediaId)
                    }
                }
            }
        }
    }

    "upload POSTs to the versioned /media endpoint with bearer" in {
        withUploadServer(mediaIdResponse) { (port, _, reqCapture) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsAppMedia.upload(Span.from(Array[Byte](1, 2, 3)), WhatsAppMedia.MediaType.ImageJpeg, Present("f.jpg")).andThen(
                    reqCapture.take.map { case (path, auth) =>
                        assert(path == s"/v25.0/${phoneId.value}/media")
                        assert(auth == s"Bearer $token")
                    }
                )
            }
        }
    }

    "upload uses a default filename when none is given" in {
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsAppMedia.upload(Span.from(Array[Byte](5, 6, 7)), WhatsAppMedia.MediaType.ImagePng).andThen(
                    partsCapture.take.map(parts => assert(parts.find(_.name == "file").get.filename == Present("file.png")))
                )
            }
        }
    }

    "upload of Other media type uses its custom mime and bin default name" in {
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsAppMedia.upload(Span.from(Array[Byte](50, 51)), WhatsAppMedia.MediaType.Other("application/zip")).andThen(
                    partsCapture.take.map { parts =>
                        assert(new String(parts.find(_.name == "type").get.data.toArray, "UTF-8") == "application/zip")
                        assert(parts.find(_.name == "file").get.filename == Present("file.bin"))
                    }
                )
            }
        }
    }

    "upload maps a 131053 error to WhatsAppMediaUploadException" in {
        withUploadServer(graphErrorBody(131053), statusOk = false) { (port, _, _) =>
            Abort.run[WhatsAppException](
                WhatsApp.let(makeConfig(port))(WhatsAppMedia.upload(Span.from(Array[Byte](1)), WhatsAppMedia.MediaType.ImageJpeg))
            ).map { result =>
                assert(result == Result.fail(WhatsAppMediaUploadException("upload", Absent, "Error 131053", Absent, Present("fb1"))))
            }
        }
    }

    "a 200 upload response missing the id field is a decode failure" in {
        withUploadServer("{}") { (port, _, _) =>
            Abort.run[WhatsAppException](
                WhatsApp.let(makeConfig(port))(WhatsAppMedia.upload(Span.from(Array[Byte](1, 2)), WhatsAppMedia.MediaType.ImageJpeg))
            ).map(result => assert(result == decodeFailure("upload", WhatsAppDecodeException.Failure.MissingField, Chunk.empty)))
        }
    }

    "resolveUrl GETs the media endpoint and decodes MediaInfo" in {
        val reqMediaId = WhatsAppId.MediaId("M1")
        withGetServer(s"v25.0/${reqMediaId.value}", mediaInfoBody("\"204800\"")) { (port, reqCapture) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsAppMedia.resolveUrl(reqMediaId).map { resolved =>
                    reqCapture.take.map { case (path, auth) =>
                        assert(path == s"/v25.0/${reqMediaId.value}")
                        assert(auth == s"Bearer $token")
                        assert(resolved == info(url(mediaUrl), 204800))
                    }
                }
            }
        }
    }

    "resolveUrl decodes file_size given as a JSON number" in {
        withGetServer(s"v25.0/M1", mediaInfoBody("204800")) { (port, _) =>
            WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("M1"))).map { resolved =>
                assert(resolved == info(url(mediaUrl), 204800))
            }
        }
    }

    "resolveUrl result id is taken from the response body not the request id" in {
        withGetServer(s"v25.0/REQ_ID", mediaInfoBody("\"1\"")) { (port, _) =>
            WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("REQ_ID"))).map { resolved =>
                assert(resolved.id == mediaId)
            }
        }
    }

    "resolveUrl maps a Graph error to a typed leaf" in {
        withGetServer(s"v25.0/M2", graphErrorBody(100), statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("M2")))).map { result =>
                assert(result ==
                    Result.fail(WhatsAppInvalidParameterException("resolveUrl", 100, Absent, "Error 100", Absent, Present("fb1"))))
            }
        }
    }

    "resolveUrl with a non-numeric file_size is a Parse decode failure" in {
        withGetServer(s"v25.0/M5", mediaInfoBody("\"big\"")) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("M5")))).map { result =>
                assert(result == decodeFailure("resolveUrl", WhatsAppDecodeException.Failure.Parse, Chunk.empty))
            }
        }
    }

    "resolveUrl with a url that does not parse is a ConstructorRejected decode failure at url" in {
        withGetServer(s"v25.0/M7", mediaInfoBody("\"1\"", "ftp://lookaside.fbsbx.com/m")) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("M7")))).map { result =>
                assert(result == decodeFailure("resolveUrl", WhatsAppDecodeException.Failure.ConstructorRejected, Chunk("url")))
            }
        }
    }

    "a structurally-broken media-info body is a Parse decode failure at its position" in {
        withGetServer(s"v25.0/M", """{"truncated":""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.resolveUrl(WhatsAppId.MediaId("M")))).map { result =>
                assert(result == decodeFailure("resolveUrl", WhatsAppDecodeException.Failure.Parse, Chunk.empty, Present(13)))
            }
        }
    }

    "download resolves the url, then returns the bytes the media host serves, byte-identical" in {
        val served = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        withMediaHost(served, WhatsAppId.MediaId("M1")) { metaPort =>
            WhatsApp.let(makeConfig(metaPort))(WhatsAppMedia.download(WhatsAppId.MediaId("M1"))).map { result =>
                assert(result.toArray sameElements served)
            }
        }
    }

    "downloadFrom GETs the info url with bearer and a User-Agent, and returns the bytes" in {
        val served = Array[Byte](100, 101, 102, 103)
        Channel.init[(String, String)](1).map { reqCapture =>
            val bytesHandler = HttpRoute.getRaw("bytes").response(_.bodyBinary).handler { req =>
                reqCapture.put((req.headers.get("Authorization").getOrElse(""), req.headers.get("User-Agent").getOrElse(""))).andThen(
                    HttpResponse.ok(Span.from(served))
                )
            }
            HttpServer.init(0, "localhost")(bytesHandler).map { s =>
                WhatsApp.let(makeConfig(s.port))(WhatsAppMedia.downloadFrom(info(url(s"http://localhost:${s.port}/bytes"), 4))).map {
                    result =>
                        reqCapture.take.map { case (auth, ua) =>
                            assert(result.toArray sameElements served)
                            assert(auth == s"Bearer $token")
                            assert(ua == "kyo-whatsapp")
                        }
                }
            }
        }
    }

    "downloadFrom maps a non-Graph 404 from the media host to WhatsAppUnexpectedStatusException" in {
        val errHandler = HttpRoute.getRaw("gone").response(_.bodyText).handler(_ => HttpResponse.notFound("Media not found or URL expired"))
        HttpServer.init(0, "localhost")(errHandler).map { s =>
            Abort.run[WhatsAppException](
                WhatsApp.let(makeConfig(s.port))(WhatsAppMedia.downloadFrom(info(url(s"http://localhost:${s.port}/gone"), 4)))
            ).map { result =>
                assert(result.map(_ => ()) == Result.fail(WhatsAppUnexpectedStatusException("downloadFrom", HttpStatus(404))))
            }
        }
    }

    "a connection failure on downloadFrom is WhatsAppTransportException of kind Connect to the media host" in {
        // Port 1 is privileged and unused, so the connection is refused; a port freed by a closed test server can be taken by a
        // parallel suite or still accept while closing.
        val refusedPort = 1
        Abort.run[WhatsAppException](
            WhatsApp.let(makeConfig(refusedPort))(WhatsAppMedia.downloadFrom(info(url(s"http://localhost:$refusedPort/bytes"), 4)))
        ).map { result =>
            assert(result.map(_ => ()) == Result.fail(
                WhatsAppTransportException("downloadFrom", WhatsAppTransportException.Kind.Connect, "localhost", refusedPort, Absent)()
            ))
        }
    }

    "delete DELETEs the media endpoint and consumes success body as Unit" in {
        withDeleteServer(s"v25.0/M1", """{"success":true}""") { (port, reqCapture) =>
            WhatsApp.let(makeConfig(port))(WhatsAppMedia.delete(WhatsAppId.MediaId("M1"))).andThen(
                reqCapture.take.map { case (path, auth) =>
                    assert(path == "/v25.0/M1")
                    assert(auth == s"Bearer $token")
                }
            )
        }
    }

    "delete maps a Graph error to a typed leaf" in {
        withDeleteServer(s"v25.0/M3", graphErrorBody(100), statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.delete(WhatsAppId.MediaId("M3")))).map { result =>
                assert(result == Result.fail(WhatsAppInvalidParameterException("delete", 100, Absent, "Error 100", Absent, Present("fb1"))))
            }
        }
    }

    "delete with success false is a decode failure at success" in {
        withDeleteServer(s"v25.0/M6", """{"success":false}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.delete(WhatsAppId.MediaId("M6")))).map { result =>
                assert(result == decodeFailure("delete", WhatsAppDecodeException.Failure.ConstructorRejected, Chunk("success")))
            }
        }
    }

    "delete with a broken success body is a MissingField decode failure" in {
        withDeleteServer(s"v25.0/M4", """{"ok":1}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsAppMedia.delete(WhatsAppId.MediaId("M4")))).map { result =>
                assert(result == decodeFailure("delete", WhatsAppDecodeException.Failure.MissingField, Chunk.empty))
            }
        }
    }

end WhatsAppMediaTest
