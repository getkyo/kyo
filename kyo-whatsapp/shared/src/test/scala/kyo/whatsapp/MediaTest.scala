package kyo.whatsapp

import kyo.*
import kyo.whatsapp.Id.*

class MediaTest extends BaseWhatsAppTest:

    "Source.ById carries a MediaId, ByLink carries a String" in {
        val byId: Media.Source   = Media.Source.ById(Id.MediaId("M1"))
        val byLink: Media.Source = Media.Source.ByLink("https://x/y.jpg")
        byId match
            case Media.Source.ById(id) => assert(id.value == "M1")
            case _                     => assert(false)
        byLink match
            case Media.Source.ByLink(link) => assert(link == "https://x/y.jpg")
            case _                         => assert(false)
    }

    "id-XOR-link is type-level exclusive (cannot set both)" in {
        typeCheckFailure("""Media.Source.ById(Id.MediaId("m"), link = "l")""")
    }

    "every MediaType case exposes its documented MIME" in {
        assert(Media.MediaType.ImageJpeg.mime == "image/jpeg")
        assert(Media.MediaType.ImagePng.mime == "image/png")
        assert(Media.MediaType.AudioAac.mime == "audio/aac")
        assert(Media.MediaType.AudioAmr.mime == "audio/amr")
        assert(Media.MediaType.AudioMp3.mime == "audio/mpeg")
        assert(Media.MediaType.AudioMp4.mime == "audio/mp4")
        assert(Media.MediaType.AudioOgg.mime == "audio/ogg")
        assert(Media.MediaType.VideoMp4.mime == "video/mp4")
        assert(Media.MediaType.Video3gp.mime == "video/3gp")
        assert(Media.MediaType.DocumentPdf.mime == "application/pdf")
        assert(Media.MediaType.DocumentText.mime == "text/plain")
        assert(Media.MediaType.StickerWebp.mime == "image/webp")
        assert(Media.MediaType.Other("application/zip").mime == "application/zip")
    }

    "MediaInfo carries all five retrieve-url fields" in {
        val info = Media.MediaInfo(Id.MediaId("M"), "https://lookaside/...", "image/jpeg", "abc123", 12345L)
        assert(info.id.value == "M")
        assert(info.url == "https://lookaside/...")
        assert(info.mimeType == "image/jpeg")
        assert(info.sha256 == "abc123")
        assert(info.fileSize == 12345L)
    }

    "MediaInfo decodes from the documented response with string file_size" in {
        val bytes =
            Span.from("""{"messaging_product":"whatsapp","url":"<MEDIA_URL>","mime_type":"image/jpeg","sha256":"<HASH>","file_size":"204800","id":"<MEDIA_ID>"}""".getBytes(
                "UTF-8"
            ))
        kyo.whatsapp.internal.Codec.decodeMediaInfo(bytes) match
            case Result.Success(info) =>
                assert(info.id == Id.MediaId("<MEDIA_ID>"))
                assert(info.url == "<MEDIA_URL>")
                assert(info.mimeType == "image/jpeg")
                assert(info.sha256 == "<HASH>")
                assert(info.fileSize == 204800L)
            case r => assert(false, s"unexpected: $r")
        end match
    }

    val token   = "TEST_MEDIA_TOKEN"
    val phoneId = Id.PhoneNumberId("106540352242922")
    val mediaId = Id.MediaId("2762702944112137")

    def makeConfig(port: Int): WhatsApp.Config =
        WhatsApp.Config(token, phoneId, baseUrl = s"http://localhost:$port")

    def mediaIdResponse: String = s"""{"id":"${mediaId.value}"}"""

    def mediaInfoBody(fileSize: String): String =
        s"""{"messaging_product":"whatsapp","url":"<MEDIA_URL>","mime_type":"image/jpeg","sha256":"abc123","file_size":$fileSize,"id":"${mediaId.value}"}"""

    def graphErrorBody(code: Int): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Error $code","fbtrace_id":"fb1"}}"""

    def withUploadServer[A, S](responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[Seq[HttpRequest.Part]], Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope) =
        Channel.init[Seq[HttpRequest.Part]](1).map { partsCapture =>
            Channel.init[(String, String)](1).map { reqCapture =>
                val route = HttpRoute.postRaw(s"v25.0/${phoneId.value}/media")
                    .request(_.bodyMultipart)
                    .response(_.bodyText)
                val handler = route.handler { req =>
                    val parts = req.fields.body
                    val auth  = req.headers.get("Authorization").getOrElse("")
                    val path  = req.path
                    partsCapture.put(parts).map { _ =>
                        reqCapture.put((path, auth)).map { _ =>
                            if statusOk then HttpResponse.ok(responseBody)
                            else HttpResponse.badRequest(responseBody)
                        }
                    }
                }
                HttpClient.init().map { httpClient =>
                    HttpServer.init(0, "localhost")(handler).map { s =>
                        HttpClient.let(httpClient) {
                            test(s.port, partsCapture, reqCapture)
                        }
                    }
                }
            }
        }

    def withGetServer[A, S](path: String, responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope) =
        Channel.init[(String, String)](1).map { reqCapture =>
            val route = HttpRoute.getRaw(path).response(_.bodyText)
            val handler = route.handler { req =>
                val auth = req.headers.get("Authorization").getOrElse("")
                val p    = req.path
                reqCapture.put((p, auth)).map { _ =>
                    if statusOk then HttpResponse.ok(responseBody)
                    else HttpResponse.badRequest(responseBody)
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        test(s.port, reqCapture)
                    }
                }
            }
        }

    def withDeleteServer[A, S](path: String, responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[(String, String)]) => A < S
    )(using Frame): A < (S & Async & Scope) =
        Channel.init[(String, String)](1).map { reqCapture =>
            val route = HttpRoute.deleteRaw(path).response(_.bodyText)
            val handler = route.handler { req =>
                val auth = req.headers.get("Authorization").getOrElse("")
                val p    = req.path
                reqCapture.put((p, auth)).map { _ =>
                    if statusOk then HttpResponse.ok(responseBody)
                    else HttpResponse.badRequest(responseBody)
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        test(s.port, reqCapture)
                    }
                }
            }
        }

    "upload posts three named multipart parts and returns the media id".notNative in {
        val bytes = Array[Byte](10, 20, 30, 40)
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.upload(Span.from(bytes), Media.MediaType.ImageJpeg, Present("photo.jpg")).map { result =>
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

    "upload POSTs to the versioned /media endpoint with bearer".notNative in {
        val bytes = Array[Byte](1, 2, 3)
        withUploadServer(mediaIdResponse) { (port, _, reqCapture) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.upload(Span.from(bytes), Media.MediaType.ImageJpeg, Present("f.jpg")).map { _ =>
                    reqCapture.take.map { case (path, auth) =>
                        assert(path.endsWith(s"/v25.0/${phoneId.value}/media"))
                        assert(auth == s"Bearer $token")
                    }
                }
            }
        }
    }

    "upload uses a default filename when none is given".notNative in {
        val bytes = Array[Byte](5, 6, 7)
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.upload(Span.from(bytes), Media.MediaType.ImagePng).map { _ =>
                    partsCapture.take.map { parts =>
                        val file = parts.find(_.name == "file").get
                        assert(file.filename == Present("file.png"))
                    }
                }
            }
        }
    }

    "upload maps a 131053 error to MediaError.UploadFailed".notNative in {
        val bytes = Array[Byte](1)
        withUploadServer(graphErrorBody(131053), statusOk = false) { (port, _, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.upload(Span.from(bytes), Media.MediaType.ImageJpeg, Present("f.jpg"))
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.MediaError.UploadFailed) => assert(true)
                    case other                                                 => assert(false, s"expected UploadFailed, got: $other")
            }
        }
    }

    "resolveUrl GETs the media endpoint and decodes MediaInfo".notNative in {
        val reqMediaId = Id.MediaId("M1")
        withGetServer(s"v25.0/${reqMediaId.value}", mediaInfoBody("\"204800\"")) { (port, reqCapture) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.resolveUrl(reqMediaId).map { info =>
                    reqCapture.take.map { case (path, auth) =>
                        assert(path.endsWith(s"/v25.0/${reqMediaId.value}"))
                        assert(auth == s"Bearer $token")
                        assert(info.id == mediaId)
                        assert(info.fileSize == 204800L)
                        assert(info.mimeType == "image/jpeg")
                    }
                }
            }
        }
    }

    "resolveUrl decodes file_size given as a JSON number".notNative in {
        val reqMediaId = Id.MediaId("M1")
        withGetServer(s"v25.0/${reqMediaId.value}", mediaInfoBody("204800")) { (port, _) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.resolveUrl(reqMediaId).map { info =>
                    assert(info.fileSize == 204800L)
                }
            }
        }
    }

    "resolveUrl maps a Graph error to a typed leaf".notNative in {
        val reqMediaId = Id.MediaId("M2")
        withGetServer(s"v25.0/${reqMediaId.value}", graphErrorBody(100), statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.resolveUrl(reqMediaId)
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.InvalidParameter(100, _)) => assert(true)
                    case other => assert(false, s"expected InvalidParameter(100), got: $other")
            }
        }
    }

    "download fuses url then downloadFrom returning the bytes".notNative in {
        val reqMediaId  = Id.MediaId("M1")
        val cannedBytes = Array[Byte](7, 8, 9, 10, 11)
        val bytesRoute  = HttpRoute.getRaw("bytes").response(_.bodyBinary)
        val bytesHandler = bytesRoute.handler { _ =>
            HttpResponse.ok(Span.from(cannedBytes))
        }
        val metaRoute = HttpRoute.getRaw(s"v25.0/${reqMediaId.value}").response(_.bodyText)
        HttpClient.init().map { httpClient =>
            HttpServer.init(0, "localhost")(bytesHandler).map { s =>
                val infoBody =
                    s"""{"messaging_product":"whatsapp","url":"http://localhost:${s.port}/bytes","mime_type":"image/jpeg","sha256":"h","file_size":"5","id":"${mediaId.value}"}"""
                val metaHandler = metaRoute.handler { _ =>
                    HttpResponse.ok(infoBody)
                }
                HttpServer.init(0, "localhost")(metaHandler).map { metaS =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(metaS.port)
                        WhatsApp.let(cfg) {
                            Media.download(reqMediaId).map { result =>
                                assert(result.toArray sameElements cannedBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    "downloadFrom GETs the info url with bearer and returns the bytes".notNative in {
        val cannedBytes = Array[Byte](100, 101, 102, 103)
        Channel.init[(String, String)](1).map { reqCapture =>
            val bytesRoute = HttpRoute.getRaw("bytes").response(_.bodyBinary)
            val bytesHandler = bytesRoute.handler { req =>
                val auth = req.headers.get("Authorization").getOrElse("")
                val ua   = req.headers.get("User-Agent").getOrElse("")
                reqCapture.put((auth, ua)).map { _ =>
                    HttpResponse.ok(Span.from(cannedBytes))
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(bytesHandler).map { s =>
                    HttpClient.let(httpClient) {
                        val cfg  = makeConfig(s.port)
                        val info = Media.MediaInfo(mediaId, s"http://localhost:${s.port}/bytes", "image/jpeg", "h", 4L)
                        WhatsApp.let(cfg) {
                            Media.downloadFrom(info).map { result =>
                                reqCapture.take.map { case (auth, ua) =>
                                    assert(result.toArray sameElements cannedBytes)
                                    assert(auth == s"Bearer $token")
                                    assert(ua == "kyo-whatsapp")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "downloadFrom maps a 131052 error to MediaError.DownloadFailed".notNative in {
        val errRoute = HttpRoute.getRaw("bad").response(_.bodyText)
        val errHandler = errRoute.handler { _ =>
            HttpResponse.badRequest(graphErrorBody(131052))
        }
        HttpClient.init().map { httpClient =>
            HttpServer.init(0, "localhost")(errHandler).map { s =>
                HttpClient.let(httpClient) {
                    val cfg  = makeConfig(s.port)
                    val info = Media.MediaInfo(mediaId, s"http://localhost:${s.port}/bad", "image/jpeg", "h", 4L)
                    Abort.run[WhatsAppError](
                        WhatsApp.let(cfg) {
                            Media.downloadFrom(info)
                        }
                    ).map { result =>
                        result match
                            case Result.Failure(WhatsAppError.MediaError.DownloadFailed) => assert(true)
                            case other => assert(false, s"expected DownloadFailed, got: $other")
                    }
                }
            }
        }
    }

    "download returns bytes byte-identical to what the server serves".notNative in {
        val reqMediaId = Id.MediaId("M")
        val fixedBytes = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        val bytesRoute = HttpRoute.getRaw("bytes").response(_.bodyBinary)
        val bytesHandler = bytesRoute.handler { _ =>
            HttpResponse.ok(Span.from(fixedBytes))
        }
        val metaRoute = HttpRoute.getRaw(s"v25.0/${reqMediaId.value}").response(_.bodyText)
        HttpClient.init().map { httpClient =>
            HttpServer.init(0, "localhost")(bytesHandler).map { s =>
                val infoBody =
                    s"""{"messaging_product":"whatsapp","url":"http://localhost:${s.port}/bytes","mime_type":"image/jpeg","sha256":"h","file_size":"8","id":"${mediaId.value}"}"""
                val metaHandler = metaRoute.handler { _ =>
                    HttpResponse.ok(infoBody)
                }
                HttpServer.init(0, "localhost")(metaHandler).map { metaS =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(metaS.port)
                        WhatsApp.let(cfg) {
                            Media.download(reqMediaId).map { result =>
                                assert(result.toArray sameElements fixedBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    "delete DELETEs the media endpoint and consumes success body as Unit".notNative in {
        val reqMediaId = Id.MediaId("M1")
        withDeleteServer(s"v25.0/${reqMediaId.value}", """{"success":true}""") { (port, reqCapture) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.delete(reqMediaId).map { result =>
                    reqCapture.take.map { case (path, auth) =>
                        assert(path.endsWith(s"/v25.0/${reqMediaId.value}"))
                        assert(auth == s"Bearer $token")
                        assert(result == ())
                    }
                }
            }
        }
    }

    "delete maps a Graph error to a typed leaf".notNative in {
        val reqMediaId = Id.MediaId("M3")
        withDeleteServer(s"v25.0/${reqMediaId.value}", graphErrorBody(100), statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.delete(reqMediaId)
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.InvalidParameter(100, _)) => assert(true)
                    case other => assert(false, s"expected InvalidParameter(100), got: $other")
            }
        }
    }

    "upload effect row is exactly Async & Abort[WhatsAppError]" in {
        typeCheck("""
            import kyo.*
            import kyo.whatsapp.*
            import kyo.whatsapp.Id.*
            val bytes: Span[Byte] = Span.from(Array[Byte](1, 2, 3))
            val _: Id.MediaId < (Async & Abort[WhatsAppError]) =
                Media.upload(bytes, Media.MediaType.ImageJpeg)
        """)
    }

    "a connection failure on downloadFrom surfaces Transport".notNative in {
        HttpClient.init().map { httpClient =>
            Scope.run(
                HttpServer.init(0, "localhost")().map { deadServer =>
                    deadServer.port
                }
            ).map { deadPort =>
                HttpClient.let(httpClient) {
                    val cfg  = makeConfig(deadPort)
                    val info = Media.MediaInfo(mediaId, s"http://localhost:$deadPort/bytes", "image/jpeg", "h", 4L)
                    Abort.run[WhatsAppError](
                        WhatsApp.let(cfg) {
                            Media.downloadFrom(info)
                        }
                    ).map { result =>
                        result match
                            case Result.Failure(_: WhatsAppError.Transport) => assert(true)
                            case other                                      => assert(false, s"expected Transport, got: $other")
                    }
                }
            }
        }
    }

    "upload of Other media type uses its custom mime and bin default name".notNative in {
        val bytes = Array[Byte](50, 51)
        withUploadServer(mediaIdResponse) { (port, partsCapture, _) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.upload(Span.from(bytes), Media.MediaType.Other("application/zip")).map { _ =>
                    partsCapture.take.map { parts =>
                        val tp   = parts.find(_.name == "type").get
                        val file = parts.find(_.name == "file").get
                        assert(new String(tp.data.toArray, "UTF-8") == "application/zip")
                        assert(file.filename == Present("file.bin"))
                    }
                }
            }
        }
    }

    "resolveUrl result id is taken from the response body not the request id".notNative in {
        val reqId  = Id.MediaId("REQ_ID")
        val respId = mediaId
        val respBody =
            s"""{"messaging_product":"whatsapp","url":"<U>","mime_type":"image/jpeg","sha256":"h","file_size":"1","id":"${respId.value}"}"""
        withGetServer(s"v25.0/${reqId.value}", respBody) { (port, _) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                Media.resolveUrl(reqId).map { info =>
                    assert(info.id == respId)
                }
            }
        }
    }

    "a structurally-broken media-info body surfaces DecodeError".notNative in {
        val reqMediaId = Id.MediaId("M")
        withGetServer(s"v25.0/${reqMediaId.value}", """{"truncated":""") { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.resolveUrl(reqMediaId)
                }
            ).map { result =>
                result match
                    case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                    case other                                        => assert(false, s"expected DecodeError, got: $other")
            }
        }
    }

    "a 200 upload response missing the id field surfaces DecodeError".notNative in {
        val bytes = Array[Byte](1, 2)
        withUploadServer("{}") { (port, _, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.upload(Span.from(bytes), Media.MediaType.ImageJpeg, Present("f.jpg"))
                }
            ).map { result =>
                result match
                    case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                    case other                                        => assert(false, s"expected DecodeError, got: $other")
            }
        }
    }

    "delete with a broken success body surfaces DecodeError".notNative in {
        val reqMediaId = Id.MediaId("M4")
        withDeleteServer(s"v25.0/${reqMediaId.value}", """{"ok":1}""") { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    Media.delete(reqMediaId)
                }
            ).map { result =>
                result match
                    case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                    case other                                        => assert(false, s"expected DecodeError, got: $other")
            }
        }
    }

end MediaTest
