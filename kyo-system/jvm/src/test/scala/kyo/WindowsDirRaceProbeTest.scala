package kyo

import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path as JPath
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

/** Scratch probe, never merged: measures what a concurrent scan of a directory does to a removal or a move of it on this platform.
  * It always passes and prints its measurements as `PROBE` lines.
  */
class WindowsDirRaceProbeTest extends kyo.test.Test[Any]:

    private val rounds = 400

    private def removeAll(root: JPath): Unit =
        val visitor = new SimpleFileVisitor[JPath]:
            override def visitFile(p: JPath, a: BasicFileAttributes): FileVisitResult =
                Files.delete(p); FileVisitResult.CONTINUE
            override def postVisitDirectory(p: JPath, e: IOException): FileVisitResult =
                if e != null then throw e
                Files.delete(p); FileVisitResult.CONTINUE
        discard(Files.walkFileTree(root, visitor))
    end removeAll

    private def scanner(kind: String, root: JPath): () => Unit =
        kind match
            case "none"   => () => ()
            case "exists" => () => discard(Files.exists(root))
            case "attrs"  => () => discard(Files.readAttributes(root, classOf[BasicFileAttributes]))
            case "list"   =>
                () =>
                    val stream = Files.list(root)
                    try discard(stream.iterator().hasNext)
                    finally stream.close()
            case "all" =>
                () =>
                    discard(Files.exists(root))
                    discard(Files.isDirectory(root))
                    discard(Files.readAttributes(root, classOf[BasicFileAttributes]))
                    val stream = Files.list(root)
                    try discard(stream.iterator().hasNext)
                    finally stream.close()

    private def probe(op: String, kind: String, withChild: Boolean): String =
        val base      = Files.createTempDirectory(s"kyo-probe-$op-$kind")
        val failures  = mutable.LinkedHashMap.empty[String, Int]
        var failed    = 0
        var maxTries  = 1
        var maxMillis = 0L
        var round     = 0
        while round < rounds do
            val root = base.resolve(s"root-$round")
            discard(Files.createDirectory(root))
            if withChild then discard(Files.writeString(root.resolve("child"), "x"))
            val stop   = new AtomicBoolean(false)
            val scans  = new AtomicLong(0L)
            val scan   = scanner(kind, root)
            val thread = new Thread((
                () =>
                    while !stop.get() do
                        try scan()
                        catch case _: Throwable => ()
                        discard(scans.incrementAndGet())
            ): Runnable)
            thread.setDaemon(true)
            thread.start()
            while kind != "none" && scans.get() < 3 do Thread.onSpinWait()
            val start = java.lang.System.nanoTime()
            var tries = 0
            var done  = false
            while !done && tries < 10000 do
                tries += 1
                try
                    op match
                        case "remove" => removeAll(root)
                        case "move"   => discard(Files.move(root, base.resolve(s"moved-$round")))
                    done = true
                catch
                    case ex: Throwable =>
                        if tries == 1 then
                            failed += 1
                            val key = s"${ex.getClass.getName}: ${ex.getMessage}".replace(base.toString, "<base>").replaceAll("-\\d+", "-N")
                            failures.update(key, failures.getOrElse(key, 0) + 1)
                        end if
                end try
            end while
            val millis = (java.lang.System.nanoTime() - start) / 1000000L
            if tries > maxTries then maxTries = tries
            if millis > maxMillis then maxMillis = millis
            stop.set(true)
            thread.join(5000)
            round += 1
        end while
        val detail = failures.map((k, v) => s"$v x [$k]").mkString("; ")
        s"PROBE os=${java.lang.System.getProperty("os.name")} arch=${java.lang.System.getProperty("os.arch")} op=$op scan=$kind child=$withChild " +
            s"firstAttemptFailures=$failed/$rounds maxTries=$maxTries maxMillis=$maxMillis detail=$detail"
    end probe

    "directory mutation under a concurrent scan" in {
        Sync.defer {
            val lines =
                for
                    op    <- Seq("remove", "move")
                    kind  <- Seq("none", "exists", "attrs", "list", "all")
                    child <- Seq(false, true)
                yield probe(op, kind, child)
            lines.foreach(line => java.lang.System.out.println(line))
            assert(lines.size == 20)
        }
    }
end WindowsDirRaceProbeTest
