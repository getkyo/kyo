package kyo

class JsonRpcTransportFramerTest extends JsonRpcTest:

    "lineDelimited.frame appends LF" in {
        val payload = Chunk.from("abc".getBytes("UTF-8"))
        JsonRpcFramer.lineDelimited.frame(payload).map { result =>
            assert(result == Chunk.from("abc\n".getBytes("UTF-8")))
        }
    }

    "lineDelimited.parse splits multi-line buffer" in {
        val input  = Chunk.from("a\nb\nc\n".getBytes("UTF-8"))
        val stream = Stream.init[Chunk[Byte], Any](Seq(input))
        JsonRpcFramer.lineDelimited.parse(stream).run.map { frames =>
            val strs = frames.map(f => new String(f.toArray, "UTF-8"))
            assert(strs == Chunk("a", "b", "c"))
        }
    }

    "lineDelimited.parse strips CR before LF" in {
        val input  = Chunk.from("a\r\nb\r\n".getBytes("UTF-8"))
        val stream = Stream.init[Chunk[Byte], Any](Seq(input))
        JsonRpcFramer.lineDelimited.parse(stream).run.map { frames =>
            val strs = frames.map(f => new String(f.toArray, "UTF-8"))
            assert(strs == Chunk("a", "b"))
            assert(!frames.exists(f => f.toArray.contains('\r'.toByte)))
        }
    }

    "lineDelimited.parse skips empty lines" in {
        val input  = Chunk.from("a\n\n\nb\n".getBytes("UTF-8"))
        val stream = Stream.init[Chunk[Byte], Any](Seq(input))
        JsonRpcFramer.lineDelimited.parse(stream).run.map { frames =>
            val strs = frames.map(f => new String(f.toArray, "UTF-8"))
            assert(strs == Chunk("a", "b"))
        }
    }

    "contentLength.frame prepends header with strict CRLF" in {
        val payload = Chunk.from("{}".getBytes("UTF-8"))
        JsonRpcFramer.contentLength.frame(payload).map { result =>
            val expected = Chunk.from("Content-Length: 2\r\n\r\n{}".getBytes("UTF-8"))
            assert(result == expected)
        }
    }

    "contentLength.parse extracts one frame" in {
        val input  = Chunk.from("Content-Length: 5\r\n\r\nhello".getBytes("UTF-8"))
        val stream = Stream.init[Chunk[Byte], Any](Seq(input))
        JsonRpcFramer.contentLength.parse(stream).run.map { frames =>
            assert(frames.size == 1)
            assert(new String(frames.head.toArray, "UTF-8") == "hello")
        }
    }

    "contentLength.parse tolerates double-LF header terminator" in {
        val input  = Chunk.from("Content-Length: 2\n\n{}".getBytes("UTF-8"))
        val stream = Stream.init[Chunk[Byte], Any](Seq(input))
        JsonRpcFramer.contentLength.parse(stream).run.map { frames =>
            assert(frames.size == 1)
            assert(new String(frames.head.toArray, "UTF-8") == "{}")
        }
    }

end JsonRpcTransportFramerTest
