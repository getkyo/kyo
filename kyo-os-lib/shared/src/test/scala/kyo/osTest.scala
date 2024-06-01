package kyo

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kyo.*

class osTest extends KyoTest:

    "execute command" in run {
        assert {
            IOs.run(ProcessCommand("echo", "-n", "some string").text).pure == "some string"
        }
    }
    "stream as stdin" in run {
        val stream = new ByteArrayInputStream("some string".getBytes(StandardCharsets.UTF_8))
        val c      = ProcessCommand("cat").stdin(ProcessInput.Stream(stream))

        assert {
            IOs.run(c.text).pure == "some string"
        }
    }
    "piped commands" in run {
        val c = ProcessCommand("echo", "-n", "some string") andThen ProcessCommand("cat")
        assert(IOs.run(c.text).pure == "some string")
    }
    "piped commands with stream stdin" in run {
        val stream = new ByteArrayInputStream("2\n1".getBytes(StandardCharsets.UTF_8))
        val c0     = ProcessCommand("cat") andThen ProcessCommand("sort")
        val c      = c0.stdin(ProcessInput.Stream(stream))
        assert(IOs.run(c.text).pure == "1\n2\n")
    }

    "read stdout to stream" in run {
        val res = IOs.run {
            ProcessCommand("echo", "-n", "some string").stream.map { s =>
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
        assert(IOs.run(ProcessCommand("ls", "--wrong-flag").waitFor).pure != 0)
    }
    "good exit code" in run {
        assert(IOs.run(ProcessCommand("echo", "some string").waitFor).pure == 0)
    }
end osTest
