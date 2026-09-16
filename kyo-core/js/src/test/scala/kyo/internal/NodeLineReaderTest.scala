package kyo.internal

import kyo.*
import kyo.test.HostFilter
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
  * The suite stages descriptors through `process.getBuiltinModule`, as production reads them, so linking it adds no static `node:` import.
  * `scala.scalajs.js` is aliased because `kyo.test.Test` has its own `js` member, the platform selector for a JS-only leaf.
  */
class NodeLineReaderTest extends kyo.test.Test[Any]:

    // Stages descriptors through node:fs, which a browser has not.
    override protected def hostFilters = Chunk(HostFilter.NotBrowser)

    private def builtin(id: String): sjs.Dynamic =
        PlatformJs.nodeBuiltin(id).getOrElse(throw new IllegalStateException(s"this suite needs $id, which the host does not provide"))

    // Lazy, so that a host without node:fs (a browser page, where the filter above cancels every leaf) reaches the
    // cancel rather than this throw, which would run while the suite is still being constructed.
    private lazy val fs =
        CoreNodeFs.module.getOrElse(throw new IllegalStateException("this suite needs node:fs, which the host does not provide"))

    /** Write `content` to a fresh temporary file and open it for reading, returning the descriptor. */
    private def descriptorOf(content: String): Int =
        val path = builtin("node:path").join(builtin("node:os").tmpdir(), s"kyo-node-line-reader-${counter()}.txt")
        discard(builtin("node:fs").writeFileSync(path, content, "utf8"))
        builtin("node:fs").openSync(path, "r").asInstanceOf[Int]
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
                reader.readLine(fs) match
                    case Present(line) =>
                        builder += line
                        loop()
                    case Absent => ()
            loop()
            builder.result()
        finally discard(builtin("node:fs").closeSync(fd))
        end try
    end linesOf

    /** An `fs` that reports "nothing typed yet" a few times before it answers, which is what a non-blocking terminal
      * does while a person is still typing. A real descriptor cannot be made to do this on demand, and the retry it
      * drives is the one path in the reader that waits.
      */
    private def fsThatWaitsBefore(content: String, waits: Int): CoreNodeFs =
        var remaining = waits
        var offset    = 0
        val bytes     = sjs.Dynamic.global.Buffer.applyDynamic("from")(content, "utf8")
        val readSync: sjs.Function5[Int, sjs.Dynamic, Int, Int, sjs.Any, Int] =
            (_: Int, buffer: sjs.Dynamic, off: Int, len: Int, _: sjs.Any) =>
                if remaining > 0 then
                    remaining -= 1
                    throw sjs.JavaScriptException(sjs.Dynamic.literal(code = "EAGAIN"))
                else
                    val available = bytes.selectDynamic("length").asInstanceOf[Int] - offset
                    val n         = if available < len then available else len
                    if n <= 0 then 0
                    else
                        discard(bytes.applyDynamic("copy")(buffer, off, offset, offset + n))
                        offset += n
                        n
                    end if
                end if
        sjs.Dynamic.literal(readSync = readSync).asInstanceOf[CoreNodeFs]
    end fsThatWaitsBefore

    "waits for a line that has not been typed yet, rather than reporting none" in {
        val reader = new NodeLineReader(0)
        val fs     = fsThatWaitsBefore("typed at last\n", waits = 3)
        assert(reader.readLine(fs) == Present("typed at last"))
    }

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
