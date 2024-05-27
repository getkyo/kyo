package kyoTest

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kyo.*

class osTest extends KyoTest:

    "execute command" in run {
        assert {
            IOs.run(Command("echo", "-n", "some string").string).pure == "some string"
        }
    }
    "stream as stdin" in run {
        val stream = new ByteArrayInputStream("some string".getBytes(StandardCharsets.UTF_8))
        val c      = Command("cat").stdin(ProcessInput.Stream(stream))

        assert {
            IOs.run(c.string).pure == "some string"
        }
    }
    "piped commands" in run {
        val c = Command("echo", "-n", "some string") >> Command("cat")
        assert(IOs.run(c.string).pure == "some string")
    }
    "piped commands with stream stdin" in run {
        val stream = new ByteArrayInputStream("2\n1".getBytes(StandardCharsets.UTF_8))
        val c0     = Command("cat") >> Command("sort")
        val c      = c0.stdin(ProcessInput.Stream(stream))
        assert(IOs.run(c.string).pure == "1\n2\n")
    }

    "read stdout to stream" in run {
        val res = IOs.run {
            Command("echo", "-n", "some string").stream.map { s =>
                val arr = new Array[Byte](11)
                s.read(arr)
                arr
            }
        }.pure
        assert {
            new String(res, StandardCharsets.UTF_8) == "some string"
        }
    }
    "bad exit code" in run {
        assert(IOs.run(Command("ls", "--wrong-flag").waitFor).pure != 0)
    }
    "good exit code" in run {
        assert(IOs.run(Command("echo", "some string").waitFor).pure == 0)
    }
end osTest
