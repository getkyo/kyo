package kyo2

import java.io.*
import java.nio.charset.StandardCharsets
import kyo2.*

class ProcessTest extends Test:

    "execute command" in {
        assert {
            IO.run(Process.Command("echo", "-n", "some string").text).eval == "some string"
        }
    }
    "stream as stdin" in {
        val stream = new ByteArrayInputStream("some string".getBytes(StandardCharsets.UTF_8))
        val c      = Process.Command("cat").stdin(Process.Input.Stream(stream))

        assert {
            IO.run(c.text).eval == "some string"
        }
    }
    "piped commands" in {
        val c = Process.Command("echo", "-n", "some string").andThen(Process.Command("cat"))
        assert(IO.run(c.text).eval == "some string")
    }
    "piped commands with stream stdin" in {
        val stream = new ByteArrayInputStream("2\n1".getBytes(StandardCharsets.UTF_8))
        val c0     = Process.Command("cat").andThen(Process.Command("sort"))
        val c      = c0.stdin(Process.Input.Stream(stream))
        assert(IO.run(c.text).eval == "1\n2\n")
    }

    "read stdout to stream" in {
        val res = IO.run {
            Process.Command("echo", "-n", "some string").stream.map { s =>
                val arr = new Array[Byte](11)
                s.read(arr)
                arr
            }
        }.eval
        assert {
            new String(res, StandardCharsets.UTF_8) == "some string"
        }
    }
    "bad exit code" in {
        assert(IO.run(Process.Command("ls", "--wrong-flag").waitFor).eval != 0)
    }
    "good exit code" in {
        assert(IO.run(Process.Command("echo", "some string").waitFor).eval == 0)
    }

    "jvm" - {
        "execute new jvm and throw error" in {
            assert {
                IO.run(Process.jvm.command(classOf[TestMainClass.type], "some-arg" :: Nil).map(_.waitFor)).eval == 1
            }
        }

        "execute new jvm and end without error" in {
            assert {
                IO.run(Process.jvm.command(classOf[TestMainClass.type]).map(_.waitFor)).eval == 0
            }
        }
    }
end ProcessTest

object TestMainClass:
    def main(args: Array[String]) =
        if args.isEmpty then System.exit(0)
        else System.exit(1)
end TestMainClass
