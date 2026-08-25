package kyo.internal

import kyo.*
import scala.scalajs.js.annotation.*
import scala.scalajs.js as sjs

/** Drives [[NodeLineReader]] over a real Node descriptor.
  *
  * This is the read behind `Console.readLine` on JS and Wasm, and the reason it exists: `scala.Console.in` is `null` on Node, so the shared
  * JVM read threw the instant a program asked for a line. A Scala.js build reported that as
  * `UndefinedBehaviorError: java.lang.NullPointerException` and a Wasm build as `[Object: null prototype] {}`, with no name and no stack.
  * Before this reader there was no code that could return the line at all, on either backend.
  *
  * A temporary file stands in for descriptor 0. Every other property of the read is the same (the reader holds only an `fd`), and unlike
  * standard input a file can be given exact bytes, so the line framing is pinned rather than sampled: terminator handling, a last line with
  * no terminator, empty lines, and a multi-byte character straddling the reader's internal read boundary.
  *
  * `scala.scalajs.js` is aliased because `kyo.test.Test` has its own `js` member, the platform selector for a JS-only leaf.
  */
class NodeLineReaderTest extends kyo.test.Test[Any]:

    /** Write `content` to a fresh temporary file and open it for reading, returning the descriptor. */
    private def descriptorOf(content: String): Int =
        val path = TestNodePath.join(TestNodeOs.tmpdir(), s"kyo-node-line-reader-${counter()}.txt")
        TestNodeFs.writeFileSync(path, content, "utf8")
        TestNodeFs.openSync(path, "r")
    end descriptorOf

    private var next = 0
    private def counter(): Int =
        next += 1
        next

    /** Read every line the descriptor yields, then close it. */
    private def linesOf(content: String): Chunk[String] =
        val fd = descriptorOf(content)
        try
            val reader  = new NodeLineReader(fd)
            val builder = Chunk.newBuilder[String]
            @scala.annotation.tailrec
            def loop(): Unit =
                reader.readLine() match
                    case Present(line) =>
                        builder += line
                        loop()
                    case Absent => ()
            loop()
            builder.result()
        finally TestNodeFs.closeSync(fd)
        end try
    end linesOf

    "reads newline-terminated lines without their terminator" in {
        assert(linesOf("hello from stdin\nsecond line\n") == Chunk("hello from stdin", "second line"))
    }

    "reads a final line that has no terminator" in {
        assert(linesOf("first\nno trailing newline") == Chunk("first", "no trailing newline"))
    }

    "reports end of input as Absent rather than an empty line" in {
        assert(linesOf("") == Chunk.empty[String])
    }

    "reads an empty line as an empty string" in {
        assert(linesOf("\n\nafter\n") == Chunk("", "", "after"))
    }

    "drops the carriage return of a CRLF terminator" in {
        assert(linesOf("windows\r\nlines\r\n") == Chunk("windows", "lines"))
    }

    "decodes a multi-byte character that straddles the reader's read boundary" in {
        // The reader fills in 8192-byte blocks, so a character placed at 8191 has its bytes split across two reads. Decoding each block
        // on its own would produce replacement characters here; decoding once the whole line is in hand is what keeps it intact.
        val padding = "a" * 8191
        val line    = padding + "é" + "tail"
        assert(linesOf(line + "\n") == Chunk(line))
    }

    "reads a line longer than one block" in {
        val long = "x" * 20000
        assert(linesOf(s"$long\nshort\n") == Chunk(long, "short"))
    }

end NodeLineReaderTest

/** The `node:fs` members this suite needs to stage a descriptor. Imported the same way production does, so the suite links under CommonJS
  * and ESModule alike; the names are Test-prefixed to stay clear of the production facades in the same package.
  */
@sjs.native
@JSImport("node:fs", JSImport.Namespace)
private object TestNodeFs extends sjs.Object:
    def writeFileSync(path: String, data: String, encoding: String): Unit = sjs.native
    def openSync(path: String, flags: String): Int                        = sjs.native
    def closeSync(fd: Int): Unit                                          = sjs.native
end TestNodeFs

@sjs.native
@JSImport("node:os", JSImport.Namespace)
private object TestNodeOs extends sjs.Object:
    def tmpdir(): String = sjs.native
end TestNodeOs

@sjs.native
@JSImport("node:path", JSImport.Namespace)
private object TestNodePath extends sjs.Object:
    def join(parts: String*): String = sjs.native
end TestNodePath
