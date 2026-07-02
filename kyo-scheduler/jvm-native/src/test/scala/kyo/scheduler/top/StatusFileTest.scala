package kyo.scheduler.top

import java.io.File
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.io.Source

class StatusFileTest extends AnyFreeSpec with NonImplicitAssertions {

    def read(path: String): String = {
        val s = Source.fromFile(path)
        try s.mkString
        finally s.close()
    }

    "write" - {
        "writes the line with a trailing newline and leaves no temp file" in {
            val path = File.createTempFile("kyo-sched-status", ".log").getAbsolutePath
            StatusFile.write(path, "kyo.sched cur=1 alloc=1")
            assert(read(path) == "kyo.sched cur=1 alloc=1\n")
            assert(!new File(path + ".tmp").exists())
        }

        "last write wins" in {
            val path = File.createTempFile("kyo-sched-status", ".log").getAbsolutePath
            StatusFile.write(path, "first")
            StatusFile.write(path, "second")
            assert(read(path) == "second\n")
        }
    }
}
