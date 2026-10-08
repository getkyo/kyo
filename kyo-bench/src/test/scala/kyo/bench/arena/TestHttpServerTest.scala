package kyo.bench.arena

import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

// The child stand-ins are top-level objects: a forked `java <class>` needs the static `main` forwarder, which an object nested in
// another object does not get.

// Holds the server's startup at a gate the test opens, standing in for a cold start of any length. The marker and gate files are
// named after the parent JVM's pid, so concurrent test JVMs never share them.
object GatedTestHttpServer:
    def waiting(parentPid: Long): Path = Path.of(java.lang.System.getProperty("java.io.tmpdir"), s"kyo-bench-gate-$parentPid-waiting")
    def gate(parentPid: Long): Path    = Path.of(java.lang.System.getProperty("java.io.tmpdir"), s"kyo-bench-gate-$parentPid-open")

    def main(args: Array[String]): Unit =
        val parentPid = ProcessHandle.current().parent().get().pid()
        Files.createFile(waiting(parentPid)): Unit
        while !Files.exists(gate(parentPid)) do Thread.sleep(10)
        TestHttpServer.main(args)
    end main
end GatedTestHttpServer

object ExitingTestHttpServer:
    def main(args: Array[String]): Unit = java.lang.System.exit(7)

class TestHttpServerTest extends AnyFreeSpec:

    private def ping(url: String): String =
        val connection = java.net.URI.create(url).toURL().openConnection().asInstanceOf[HttpURLConnection]
        try new String(connection.getInputStream.readAllBytes())
        finally connection.disconnect()
    end ping

    // A catastrophic bound on an event the child always produces, never a timing assertion.
    private def awaitFile(path: Path): Unit =
        val deadline = java.lang.System.nanoTime() + 5.minutes.toNanos
        while !Files.exists(path) do
            if java.lang.System.nanoTime() > deadline then fail(s"the child never reached its gate: $path")
            Thread.sleep(10)
    end awaitFile

    private def delete(path: Path): Unit = Files.deleteIfExists(path): Unit

    "start returns only after the child reports it is listening, however long its startup takes" in {
        val pid     = ProcessHandle.current().pid()
        val waiting = GatedTestHttpServer.waiting(pid)
        val gate    = GatedTestHttpServer.gate(pid)
        delete(waiting)
        delete(gate)
        try
            val started = Future(TestHttpServer.start(1, "kyo.bench.arena.GatedTestHttpServer"))(using ExecutionContext.global)
            awaitFile(waiting)
            assert(!started.isCompleted, s"start returned while the child was held before listening: ${started.value}")
            Files.createFile(gate): Unit
            val url = Await.result(started, 5.minutes)
            assert(ping(url) == "pong")
        finally
            delete(waiting)
            delete(gate)
        end try
    }

    "start fails with the child's exit code when the child dies before listening" in {
        val failure = intercept[RuntimeException](TestHttpServer.start(1, "kyo.bench.arena.ExitingTestHttpServer"))
        assert(failure.getMessage.contains("exited with code 7"), failure.getMessage)
    }

end TestHttpServerTest
