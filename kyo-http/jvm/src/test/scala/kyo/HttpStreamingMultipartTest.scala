package kyo

import HttpRequest.*
import kyo.HttpStatus

class HttpStreamingMultipartTest extends Test:

    def startUploadServer(handlers: HttpHandler[?]*)(using Frame): Int < (Async & Scope) =
        HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 1024 * 1024), PlatformTestBackend.server)(handlers*).map(_.port)

    "single file via streaming multipart" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                if chunk.isEmpty then "no parts"
                else
                    val part = chunk(0)
                    s"name=${part.name},filename=${part.filename.getOrElse("none")},size=${part.content.length}"
            }
        }
        startUploadServer(handler).map { port =>
            val parts   = Seq(Part("file", Present("test.txt"), Present("text/plain"), "hello world".getBytes("UTF-8")))
            val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "name=file,filename=test.txt,size=11")
            }
        }
    }

    "multiple files via streaming multipart" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                val summary = (0 until chunk.size).map(i => s"${chunk(i).name}:${chunk(i).content.length}").mkString(",")
                s"count=${chunk.size},$summary"
            }
        }
        startUploadServer(handler).map { port =>
            val parts = Seq(
                Part("file1", Present("a.txt"), Present("text/plain"), "aaa".getBytes("UTF-8")),
                Part("file2", Present("b.txt"), Present("text/plain"), "bbbbbb".getBytes("UTF-8")),
                Part("file3", Present("c.txt"), Present("text/plain"), "c".getBytes("UTF-8"))
            )
            val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "count=3,file1:3,file2:6,file3:1")
            }
        }
    }

    "mixed text fields and file fields" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                val fields = (0 until chunk.size).map { i =>
                    val p       = chunk(i)
                    val isFile  = p.filename.isDefined
                    val content = new String(p.content, "UTF-8")
                    s"${p.name}:${if isFile then "file" else "field"}=${content}"
                }.mkString(";")
                fields
            }
        }
        startUploadServer(handler).map { port =>
            val parts = Seq(
                Part("title", Absent, Absent, "My Document".getBytes("UTF-8")),
                Part("file", Present("doc.txt"), Present("text/plain"), "file content".getBytes("UTF-8")),
                Part("tags", Absent, Absent, "important".getBytes("UTF-8"))
            )
            val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "title:field=My Document;file:file=file content;tags:field=important")
            }
        }
    }

    "large file via streaming multipart" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                if chunk.isEmpty then "no parts"
                else s"size=${chunk(0).content.length}"
            }
        }
        startUploadServer(handler).map { port =>
            val largeContent = new Array[Byte](100000)
            java.util.Arrays.fill(largeContent, 'A'.toByte)
            val parts   = Seq(Part("bigfile", Present("large.bin"), Present("application/octet-stream"), largeContent))
            val request = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "size=100000")
            }
        }
    }

    "streaming multipart with path params" in run {
        val route = HttpRoute.post("uploads" / Capture[String]("category"))
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                if chunk.isEmpty then "no parts"
                else s"category=${in.category},file=${chunk(0).name}"
            }
        }
        startUploadServer(handler).map { port =>
            val parts   = Seq(Part("photo", Present("img.png"), Present("image/png"), "pixels".getBytes))
            val request = HttpRequest.multipart(s"http://localhost:$port/uploads/photos", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "category=photos,file=photo")
            }
        }
    }

    "empty parts list" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                s"count=${chunk.size}"
            }
        }
        startUploadServer(handler).map { port =>
            val request = HttpRequest.multipart(s"http://localhost:$port/upload", Seq.empty)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "count=0")
            }
        }
    }

    "binary content preservation" in run {
        val route = HttpRoute.post("/upload")
            .request(_.bodyMultipartStream)
            .response(_.bodyText)
        val handler = route.handle { in =>
            in.parts.run.map { chunk =>
                if chunk.isEmpty then "no parts"
                else
                    val bytes = chunk(0).content
                    bytes.map(_ & 0xff).mkString(",")
            }
        }
        startUploadServer(handler).map { port =>
            val binaryData = Array[Byte](0x00, 0x01, 0x7f, 0x80.toByte, 0xff.toByte, 0x0d, 0x0a)
            val parts      = Seq(Part("data", Present("binary.dat"), Present("application/octet-stream"), binaryData))
            val request    = HttpRequest.multipart(s"http://localhost:$port/upload", parts)
            HttpClient.send(request).map { response =>
                assertStatus(response, HttpStatus.OK)
                assertBodyText(response, "0,1,127,128,255,13,10")
            }
        }
    }

end HttpStreamingMultipartTest
