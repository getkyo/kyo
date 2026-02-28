package kyo

import kyo.*
import kyo.Record2.~

// RFC 7578: Returning Values from Forms: multipart/form-data
// Tests validate multipart handling per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc7578Test extends Test:

    val rawRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](port: Int, route: HttpRoute[In, Out, Any], request: HttpRequest[In])(using
        Frame
    ): HttpResponse[Out] < (Async & Abort[HttpError]) =
        HttpClient.use { client =>
            client.sendWith(
                route,
                request.copy(url =
                    HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery)
                )
            )(identity)
        }

    // ==================== Section 4.2: Content-Disposition ====================

    "Section 4.2 - Part with filename parameter" in run {
        // RFC 7578 §4.2: "each part MAY have an (optional) 'Content-Disposition' header field
        // that further describes the part... may include a 'filename' parameter"
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts       = req.fields.body
            val hasFilename = parts.headOption.exists(_.filename.nonEmpty)
            HttpResponse.okText(s"filename=${parts.head.filename}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "file",
                Present("document.pdf"),
                Present("application/pdf"),
                Span.fromUnsafe("pdf data".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body.contains("document.pdf"), s"Filename should be preserved, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.2 - Part without filename" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"filename=${parts.head.filename}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "field",
                Absent,
                Absent,
                Span.fromUnsafe("value".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body.contains("Absent"), s"Filename should be Absent, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.4 - Part with explicit Content-Type" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"ct=${parts.head.contentType}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "file",
                Present("image.png"),
                Present("image/png"),
                Span.fromUnsafe(Array[Byte](0, 1, 2, 3))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body.contains("image/png"), s"Content-Type should be preserved, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.4 - Part without Content-Type" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"ct=${parts.head.contentType}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "field",
                Absent,
                Absent,
                Span.fromUnsafe("text".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                // RFC 7578 §4.4: "If the contents of a file are to be sent, the Content-Type
                // should be set to... If not, text/plain is the default"
                assert(
                    resp.fields.body.contains("Absent") || resp.fields.body.contains("text/plain"),
                    s"No explicit Content-Type, got: ${resp.fields.body}"
                )
            }
        }
    }

    "Section 4 - Empty part body" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"size=${parts.head.data.size}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "empty",
                Absent,
                Absent,
                Span.empty[Byte]
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body == "size=0", s"Empty part should have size 0, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4 - Many parts (>10) preserved in order" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            val names = parts.map(_.name).mkString(",")
            HttpResponse.okText(s"count=${parts.size},names=$names")
        }
        withServer(ep) { port =>
            val parts = (1 to 15).map { i =>
                HttpPart(
                    s"part$i",
                    Absent,
                    Absent,
                    Span.fromUnsafe(s"data$i".getBytes("UTF-8"))
                )
            }.toSeq
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", parts)
            ).map { resp =>
                assert(resp.fields.body.contains("count=15"), s"Should preserve all 15 parts, got: ${resp.fields.body}")
                // Check order
                val expectedNames = (1 to 15).map(i => s"part$i").mkString(",")
                assert(resp.fields.body.contains(expectedNames), s"Parts should be in order, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4 - Streaming multipart parts arrive in order" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val ep = route.handler { req =>
            req.fields.body.run.map { chunks =>
                val parts = chunks.toSeq
                val names = parts.map(_.name).mkString(",")
                HttpResponse.okText(s"count=${parts.size},names=$names")
            }
        }
        val sendRoute = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        withServer(ep) { port =>
            val parts = (1 to 3).map { i =>
                HttpPart(
                    s"p$i",
                    Absent,
                    Absent,
                    Span.fromUnsafe(s"d$i".getBytes("UTF-8"))
                )
            }.toSeq
            send(
                port,
                sendRoute,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", parts)
            ).map { resp =>
                assert(resp.fields.body.contains("count=3"), s"Should have 3 parts, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("p1,p2,p3"), s"Parts should be in order, got: ${resp.fields.body}")
            }
        }
    }

    // ==================== Additional multipart tests ====================

    "Section 4.2 - Part name is preserved" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"name=${parts.head.name}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "my-field-name",
                Absent,
                Absent,
                Span.fromUnsafe("data".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body == "name=my-field-name", s"Part name should be preserved, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4 - Binary data preserved in part" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            val bytes = parts.head.data.toArray
            HttpResponse.okText(s"size=${bytes.length},first=${bytes(0)},last=${bytes(bytes.length - 1)}")
        }
        withServer(ep) { port =>
            val binaryData = Array[Byte](0, 1, 127, -128, -1)
            val part = HttpPart(
                "bin",
                Present("binary.dat"),
                Present("application/octet-stream"),
                Span.fromUnsafe(binaryData)
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body.contains("size=5"), s"Binary data size should be preserved, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("first=0"), s"First byte should be 0, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("last=-1"), s"Last byte should be -1, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.2 - Multiple parts with different content types" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            val cts   = parts.map(p => s"${p.name}:${p.contentType}").mkString(";")
            HttpResponse.okText(cts)
        }
        withServer(ep) { port =>
            val parts = Seq(
                HttpPart("text", Absent, Present("text/plain"), Span.fromUnsafe("hello".getBytes("UTF-8"))),
                HttpPart("json", Absent, Present("application/json"), Span.fromUnsafe("{\"a\":1}".getBytes("UTF-8"))),
                HttpPart("img", Present("photo.jpg"), Present("image/jpeg"), Span.fromUnsafe(Array[Byte](1, 2, 3)))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", parts)
            ).map { resp =>
                assert(resp.fields.body.contains("text/plain"), s"text/plain should be preserved, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("application/json"), s"application/json should be preserved, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("image/jpeg"), s"image/jpeg should be preserved, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4 - Single part upload and download" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            val text  = new String(parts.head.data.toArray, "UTF-8")
            HttpResponse.okText(text)
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "content",
                Absent,
                Present("text/plain"),
                Span.fromUnsafe("Hello, World!".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(resp.fields.body == "Hello, World!", s"Part text should round-trip, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.2 - Filename with special characters" in run {
        val route = HttpRoute.postRaw("upload")
            .request(_.bodyMultipart)
            .response(_.bodyText)
        val ep = route.handler { req =>
            val parts = req.fields.body
            HttpResponse.okText(s"filename=${parts.head.filename}")
        }
        withServer(ep) { port =>
            val part = HttpPart(
                "file",
                Present("my file (1).txt"),
                Present("text/plain"),
                Span.fromUnsafe("data".getBytes("UTF-8"))
            )
            send(
                port,
                route,
                HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", Seq(part))
            ).map { resp =>
                assert(
                    resp.fields.body.contains("my file (1).txt"),
                    s"Filename with special chars should be preserved, got: ${resp.fields.body}"
                )
            }
        }
    }

end Rfc7578Test
