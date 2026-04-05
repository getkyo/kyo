package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class ByteStreamTest extends kyo.Test:

    private val Utf8 = StandardCharsets.UTF_8

    def streamOf(spans: Span[Byte]*): Stream[Span[Byte], Async] = Stream.init(spans.toSeq)
    def bytes(s: String): Span[Byte]                            = Span.fromUnsafe(s.getBytes(Utf8))
    def str(span: Span[Byte]): String                           = new String(span.toArrayUnsafe, Utf8)

    "ByteStream" - {

        "readUntil" - {

            "delimiter in single span" in run {
                val src = streamOf(bytes("hello\r\nworld"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).toList == List("world"))
                    }
                }
            }

            "delimiter spans two spans" in run {
                val src = streamOf(bytes("hello\r"), bytes("\nworld"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).toList == List("world"))
                    }
                }
            }

            "delimiter at very start of data" in run {
                val src = streamOf(bytes("\r\nhello"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(result.isEmpty)
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).toList == List("hello"))
                    }
                }
            }

            "delimiter at very end" in run {
                val src = streamOf(bytes("hello\r\n"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "multiple delimiters stops at first" in run {
                val src = streamOf(bytes("hello\r\nworld\r\nfoo"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        val combined = leftover.map(str).mkString
                        assert(combined == "world\r\nfoo")
                    }
                }
            }

            "no delimiter stream ends" in run {
                val src = streamOf(bytes("hello"))
                Abort.run[HttpException](ByteStream.readUntil(src, ByteStream.CRLF, 65536)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
                }
            }

            "exceeds maxSize before delimiter" in run {
                val src = streamOf(bytes("hello world this is too long"))
                Abort.run[HttpException](ByteStream.readUntil(src, ByteStream.CRLF, 5)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpProtocolException]))
                }
            }

            "empty stream" in run {
                val src = streamOf()
                Abort.run[HttpException](ByteStream.readUntil(src, ByteStream.CRLF, 65536)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
                }
            }

            "single-byte spans worst case fragmentation" in run {
                val src = streamOf(
                    bytes("h"),
                    bytes("e"),
                    bytes("l"),
                    bytes("l"),
                    bytes("o"),
                    bytes("\r"),
                    bytes("\n"),
                    bytes("w")
                )
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "w")
                    }
                }
            }

            "delimiter is 1 byte" in run {
                val src = streamOf(bytes("hello\nworld"))
                ByteStream.readUntil(src, "\n".getBytes(Utf8), 65536).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "world")
                    }
                }
            }

            "data exactly equals delimiter" in run {
                val src = streamOf(bytes("\r\n"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result, remaining) =>
                    assert(result.isEmpty)
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "large data split into 8KB spans" in run {
                val chunkSize = 8192
                val numChunks = 10
                val bigData   = "x" * (chunkSize * numChunks - 2)
                val spans     = bigData.grouped(chunkSize).map(bytes).toSeq :+ bytes("\r\nafter")
                val src       = Stream.init(spans)
                ByteStream.readUntil(src, ByteStream.CRLF, chunkSize * numChunks + 100).map { (result, remaining) =>
                    assert(result.size == chunkSize * numChunks - 2)
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "after")
                    }
                }
            }

            "remaining stream is consumable" in run {
                val src = streamOf(bytes("first\r\nsecond\r\nthird"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (result1, rem1) =>
                    assert(str(result1) == "first")
                    ByteStream.readUntil(rem1, ByteStream.CRLF, 65536).map { (result2, rem2) =>
                        assert(str(result2) == "second")
                        rem2.run.map { leftover =>
                            assert(leftover.map(str).mkString == "third")
                        }
                    }
                }
            }

        } // readUntil

        "readExact" - {

            "exact fit one span" in run {
                val src = streamOf(bytes("hello"))
                ByteStream.readExact(src, 5).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "span larger than n" in run {
                val src = streamOf(bytes("hello world"))
                ByteStream.readExact(src, 5).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == " world")
                    }
                }
            }

            "multiple spans needed" in run {
                val src = streamOf(bytes("hel"), bytes("lo"), bytes("!"))
                ByteStream.readExact(src, 6).map { (result, remaining) =>
                    assert(str(result) == "hello!")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "n = 0" in run {
                val src = streamOf(bytes("hello"))
                ByteStream.readExact(src, 0).map { (result, remaining) =>
                    assert(result.isEmpty)
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "hello")
                    }
                }
            }

            "stream ends before n bytes" in run {
                val src = streamOf(bytes("hi"))
                Abort.run[HttpException](ByteStream.readExact(src, 10)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
                }
            }

            "n = 1 single byte" in run {
                val src = streamOf(bytes("hello"))
                ByteStream.readExact(src, 1).map { (result, remaining) =>
                    assert(str(result) == "h")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "ello")
                    }
                }
            }

            "large n across many small spans" in run {
                val totalBytes = 1024 * 1024
                val spanSize   = 1024
                val spans      = (0 until totalBytes / spanSize).map(_ => Span.fromUnsafe(Array.fill[Byte](spanSize)(42)))
                val src        = Stream.init(spans.toSeq)
                ByteStream.readExact(src, totalBytes).map { (result, remaining) =>
                    assert(result.size == totalBytes)
                    assert(result.toArrayUnsafe.forall(_ == 42))
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "remaining stream consumable after readExact" in run {
                val src = streamOf(bytes("helloworld"))
                ByteStream.readExact(src, 5).map { (result1, rem1) =>
                    assert(str(result1) == "hello")
                    ByteStream.readExact(rem1, 5).map { (result2, rem2) =>
                        assert(str(result2) == "world")
                        rem2.run.map { leftover =>
                            assert(leftover.map(str).mkString == "")
                        }
                    }
                }
            }

        } // readExact

        "readLine" - {

            "hello\\r\\n" in run {
                val src = streamOf(bytes("hello\r\n"))
                ByteStream.readLine(src).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "hello\\r\\nworld\\r\\n" in run {
                val src = streamOf(bytes("hello\r\nworld\r\n"))
                ByteStream.readLine(src).map { (result, remaining) =>
                    assert(str(result) == "hello")
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "world\r\n")
                    }
                }
            }

            "sequential readLine calls" in run {
                val src = streamOf(bytes("line1\r\nline2\r\nline3"))
                ByteStream.readLine(src).map { (l1, rem1) =>
                    assert(str(l1) == "line1")
                    ByteStream.readLine(rem1).map { (l2, rem2) =>
                        assert(str(l2) == "line2")
                        rem2.run.map { leftover =>
                            assert(leftover.map(str).mkString == "line3")
                        }
                    }
                }
            }

            "empty line CRLF only" in run {
                val src = streamOf(bytes("\r\n"))
                ByteStream.readLine(src).map { (result, remaining) =>
                    assert(result.isEmpty)
                    remaining.run.map { leftover =>
                        assert(leftover.map(str).mkString == "")
                    }
                }
            }

            "line exceeds maxSize" in run {
                val src = streamOf(bytes("this is a very long line without crlf"))
                Abort.run[HttpException](ByteStream.readLine(src, maxSize = 5)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpProtocolException]))
                }
            }

            "no CRLF stream ends" in run {
                val src = streamOf(bytes("no crlf here"))
                Abort.run[HttpException](ByteStream.readLine(src)).map { result =>
                    assert(result.isError)
                    assert(result.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
                }
            }

        } // readLine

        "composition" - {

            "readUntil(CRLF_CRLF) then readExact on remaining" in run {
                val body    = bytes("body data")
                val headers = bytes("header block\r\n\r\n")
                val src     = streamOf(headers, body)
                ByteStream.readUntil(src, ByteStream.CRLF_CRLF, 65536).map { (headerBytes, rem) =>
                    assert(str(headerBytes) == "header block")
                    ByteStream.readExact(rem, 9).map { (bodyBytes, _) =>
                        assert(str(bodyBytes) == "body data")
                    }
                }
            }

            "readLine readLine readExact all from same stream" in run {
                val src = streamOf(bytes("line1\r\nline2\r\nbody!"))
                ByteStream.readLine(src).map { (l1, rem1) =>
                    assert(str(l1) == "line1")
                    ByteStream.readLine(rem1).map { (l2, rem2) =>
                        assert(str(l2) == "line2")
                        ByteStream.readExact(rem2, 5).map { (body, _) =>
                            assert(str(body) == "body!")
                        }
                    }
                }
            }

            "readUntil remaining stream delivers subsequent data" in run {
                val src = streamOf(bytes("abc\r\nxyz"))
                ByteStream.readUntil(src, ByteStream.CRLF, 65536).map { (before, remaining) =>
                    assert(str(before) == "abc")
                    remaining.run.map { chunks =>
                        assert(chunks.map(str).mkString == "xyz")
                    }
                }
            }

        } // composition

    } // ByteStream

end ByteStreamTest
