package kyo

import java.io.*
import java.lang.System as JSystem
import java.nio.charset.StandardCharsets
import kyo.*

class ProcessTest extends Test:

    "execute command" in run {
        Process.Command("echo", "-n", "some string").text.map { result =>
            assert(result == "some string")
        }
    }
    "stream as stdin" in run {
        val stream = new ByteArrayInputStream("some string".getBytes(StandardCharsets.UTF_8))
        Process.Command("cat").stdin(Process.Input.Stream(stream)).text.map { result =>
            assert(result == "some string")
        }
    }
    "piped commands" in run {
        Process.Command("echo", "-n", "some string").andThen(Process.Command("cat")).text.map { result =>
            assert(result == "some string")
        }
    }
    "piped commands with stream stdin" in run {
        val stream = new ByteArrayInputStream("2\n1".getBytes(StandardCharsets.UTF_8))
        Process.Command("cat").andThen(Process.Command("sort"))
            .stdin(Process.Input.Stream(stream))
            .text
            .map { result =>
                assert(result == "1\n2\n")
            }
    }

    "read stdout to stream" in run {
        Process.Command("echo", "-n", "some string").stream.map { s =>
            val arr = new Array[Byte](11)
            s.read(arr)
            arr
        }.map { result =>
            assert {
                new String(result, StandardCharsets.UTF_8) == "some string"
            }
        }
    }
    "bad exit code" in run {
        Process.Command("ls", "--wrong-flag").waitFor.map { result =>
            assert(result != 0)
        }
    }
    "good exit code" in run {
        Process.Command("echo", "some string").waitFor.map { result =>
            assert(result == 0)
        }
    }

    "jvm" - {
        "execute new jvm and throw error" in run {
            Process.jvm.command(classOf[TestMainClass.type], "some-arg" :: Nil).map(_.waitFor).map { result =>
                assert(result == 1)
            }
        }

        "execute new jvm and end without error" in run {
            Process.jvm.command(classOf[TestMainClass.type]).map(_.waitFor).map { result =>
                assert(result == 0)
            }
        }
    }
end ProcessTest

object TestMainClass:
    def main(args: Array[String]) =
        if args.isEmpty then JSystem.exit(0)
        else JSystem.exit(1)
end TestMainClass
