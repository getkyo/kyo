package kyoTest

import java.io.*
import java.nio.charset.StandardCharsets
import kyo.*

class ProcessTest extends KyoTest:

    "execute command" in run {
        assert {
            IOs.run(Process.Command("echo", "-n", "some string").text).pure == "some string"
        }
    }
    "stream as stdin" in run {
        val stream = new ByteArrayInputStream("some string".getBytes(StandardCharsets.UTF_8))
        val c      = Process.Command("cat").stdin(Process.Input.Stream(stream))

        assert {
            IOs.run(c.text).pure == "some string"
        }
    }
    "piped commands" in run {
        val c = Process.Command("echo", "-n", "some string").andThen(Process.Command("cat"))
        assert(IOs.run(c.text).pure == "some string")
    }
    "piped commands with stream stdin" in run {
        val stream = new ByteArrayInputStream("2\n1".getBytes(StandardCharsets.UTF_8))
        val c0     = Process.Command("cat").andThen(Process.Command("sort"))
        val c      = c0.stdin(Process.Input.Stream(stream))
        assert(IOs.run(c.text).pure == "1\n2\n")
    }

    "read stdout to stream" in run {
        val res = IOs.run {
            Process.Command("echo", "-n", "some string").stream.map { s =>
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
        assert(IOs.run(Process.Command("ls", "--wrong-flag").waitFor).pure != 0)
    }
    "good exit code" in run {
        assert(IOs.run(Process.Command("echo", "some string").waitFor).pure == 0)
    }

    "jvm" - {
        "execute new jvm and throw error" in run {
            assert {
                IOs.run(Process.jvm.command(classOf[TestMainClass.type], "some-arg" :: Nil).map(_.waitFor)).pure == 1
            }
        }

        "execute new jvm and end without error" in run {
            assert {
                IOs.run(Process.jvm.command(classOf[TestMainClass.type]).map(_.waitFor)).pure == 0
            }
        }
    }
end ProcessTest

object TestMainClass:
    def main(args: Array[String]) =
        if args.isEmpty then System.exit(0)
        else System.exit(1)
end TestMainClass
