// Old kyo package test — commented out, replaced by kyo.http2 tests
// package kyo
//
// import HttpRequest.*
// import kyo.HttpStatus
//
// /** End-to-end multipart upload tests: client sends multipart request, server receives and parses parts. JVM-only because
//   * HttpRequest.multipart uses java.util.UUID.randomUUID().
//   */
// class HttpMultipartUploadTest extends Test:
//
//     // Need larger maxContentLength for multipart payloads
//     def startUploadServer(handlers: HttpHandler[?]*)(using Frame): Int < (Async & Scope) =
//         HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 1024 * 1024), PlatformTestBackend.server)(handlers*).map(_.port)
//
//     "single file upload" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val parts = in.request.parts
//             if parts.isEmpty then HttpResponse.badRequest("no parts")
//             else
//                 val part = parts(0)
//                 HttpResponse.ok(s"name=${part.name},filename=${part.filename.getOrElse("none")},size=${part.data.size}")
//             end if
//         }
//         startUploadServer(handler).map { port =>
//             val parts   = Seq(Part("file", Present("test.txt"), Present("text/plain"), Span.fromUnsafe("hello world".getBytes("UTF-8"))))
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "name=file,filename=test.txt,size=11")
//             }
//         }
//     }
//
//     "multiple file upload" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val parts   = in.request.parts
//             val summary = (0 until parts.size).map(i => s"${parts(i).name}:${parts(i).data.size}").mkString(",")
//             HttpResponse.ok(s"count=${parts.size},$summary")
//         }
//         startUploadServer(handler).map { port =>
//             val parts = Seq(
//                 Part("file1", Present("a.txt"), Present("text/plain"), Span.fromUnsafe("aaa".getBytes("UTF-8"))),
//                 Part("file2", Present("b.txt"), Present("text/plain"), Span.fromUnsafe("bbbbbb".getBytes("UTF-8"))),
//                 Part("file3", Present("c.txt"), Present("text/plain"), Span.fromUnsafe("c".getBytes("UTF-8")))
//             )
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "count=3,file1:3,file2:6,file3:1")
//             }
//         }
//     }
//
//     "form fields and file mixed" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val parts = in.request.parts
//             val fields = (0 until parts.size).map { i =>
//                 val p       = parts(i)
//                 val isFile  = p.filename.isDefined
//                 val content = new String(p.data.toArrayUnsafe, "UTF-8")
//                 s"${p.name}:${if isFile then "file" else "field"}=${content}"
//             }.mkString(";")
//             HttpResponse.ok(fields)
//         }
//         startUploadServer(handler).map { port =>
//             val parts = Seq(
//                 Part("title", Absent, Absent, Span.fromUnsafe("My Document".getBytes("UTF-8"))),
//                 Part("file", Present("doc.txt"), Present("text/plain"), Span.fromUnsafe("file content".getBytes("UTF-8"))),
//                 Part("tags", Absent, Absent, Span.fromUnsafe("important".getBytes("UTF-8")))
//             )
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "title:field=My Document;file:file=file content;tags:field=important")
//             }
//         }
//     }
//
//     "filename and content-type preservation" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val part = in.request.parts(0)
//             HttpResponse.ok(
//                 s"filename=${part.filename.getOrElse("none")},ct=${part.contentType.getOrElse("none")}"
//             )
//         }
//         startUploadServer(handler).map { port =>
//             val parts   = Seq(Part("photo", Present("sunset.jpg"), Present("image/jpeg"), Span.fromUnsafe(Array[Byte](1, 2, 3))))
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "filename=sunset.jpg,ct=image/jpeg")
//             }
//         }
//     }
//
//     "binary file content" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val part  = in.request.parts(0)
//             val bytes = part.data
//             // Echo back as comma-separated byte values
//             HttpResponse.ok(bytes.toArrayUnsafe.toSeq.map(_ & 0xff).mkString(","))
//         }
//         startUploadServer(handler).map { port =>
//             val binaryData = Array[Byte](0x00, 0x01, 0x7f, 0x80.toByte, 0xff.toByte, 0x0d, 0x0a)
//             val parts      = Seq(Part("data", Present("binary.dat"), Present("application/octet-stream"), Span.fromUnsafe(binaryData)))
//             val request    = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "0,1,127,128,255,13,10")
//             }
//         }
//     }
//
//     "large file upload" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val part = in.request.parts(0)
//             HttpResponse.ok(s"size=${part.data.size}")
//         }
//         startUploadServer(handler).map { port =>
//             val largeContent = new Array[Byte](100000)
//             java.util.Arrays.fill(largeContent, 'A'.toByte)
//             val parts   = Seq(Part("bigfile", Present("large.bin"), Present("application/octet-stream"), Span.fromUnsafe(largeContent)))
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "size=100000")
//             }
//         }
//     }
//
//     "empty file upload" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val part = in.request.parts(0)
//             HttpResponse.ok(s"name=${part.name},size=${part.data.size}")
//         }
//         startUploadServer(handler).map { port =>
//             val parts   = Seq(Part("empty", Present("empty.txt"), Present("text/plain"), Span.fromUnsafe(Array.empty[Byte])))
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "name=empty,size=0")
//             }
//         }
//     }
//
//     "upload with custom request headers" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val auth = in.request.header("Authorization").getOrElse("none")
//             val part = in.request.parts(0)
//             HttpResponse.ok(s"auth=$auth,file=${part.name}")
//         }
//         startUploadServer(handler).map { port =>
//             val parts = Seq(Part("doc", Present("secret.pdf"), Present("application/pdf"), Span.fromUnsafe("data".getBytes)))
//             val request = HttpRequest.multipart(
//                 s"http://localhost:$port/upload",
//                 parts,
//                 HttpHeaders.empty.add("Authorization", "Bearer test-token")
//             )
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "auth=Bearer test-token,file=doc")
//             }
//         }
//     }
//
//     "upload with content containing CRLF" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val part    = in.request.parts(0)
//             val content = new String(part.data.toArrayUnsafe, "UTF-8")
//             HttpResponse.ok(content)
//         }
//         startUploadServer(handler).map { port =>
//             val content = "line1\r\nline2\r\nline3"
//             val parts   = Seq(Part("text", Present("lines.txt"), Present("text/plain"), Span.fromUnsafe(content.getBytes("UTF-8"))))
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "line1\r\nline2\r\nline3")
//             }
//         }
//     }
//
//     "many parts upload" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val parts = in.request.parts
//             HttpResponse.ok(s"count=${parts.size}")
//         }
//         startUploadServer(handler).map { port =>
//             val parts = (1 to 15).map { i =>
//                 Part(s"field$i", Present(s"file$i.txt"), Present("text/plain"), Span.fromUnsafe(s"content$i".getBytes))
//             }
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "count=15")
//             }
//         }
//     }
//
//     "upload to path with params" in run {
//         val handler = HttpHandler.post("uploads" / Capture[String]("category")) { in =>
//             val part = in.request.parts(0)
//             HttpResponse.ok(s"category=${in.category},file=${part.name}")
//         }
//         startUploadServer(handler).map { port =>
//             val parts   = Seq(Part("photo", Present("img.png"), Present("image/png"), Span.fromUnsafe("pixels".getBytes)))
//             val request = HttpRequest.multipart(s"http://localhost:$port/uploads/photos", parts)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "category=photos,file=photo")
//             }
//         }
//     }
//
//     "empty parts list" in run {
//         val handler = HttpHandler.post("/upload") { in =>
//             val parts = in.request.parts
//             HttpResponse.ok(s"count=${parts.size}")
//         }
//         startUploadServer(handler).map { port =>
//             val request = HttpRequest.multipart(s"http://localhost:$port/upload", Seq.empty)
//             HttpClient.send(request).map { response =>
//                 assertStatus(response, HttpStatus.OK)
//                 assertBodyText(response, "count=0")
//             }
//         }
//     }
//
// end HttpMultipartUploadTest
