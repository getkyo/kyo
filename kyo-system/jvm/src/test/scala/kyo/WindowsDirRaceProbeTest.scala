package kyo

import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path as JPath
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Scratch probe, never merged: measures what a concurrent scan of a directory does to a removal or a move of it on this platform,
  * and what the scan itself observes while the directory goes away. It always passes and prints its measurements as `PROBE` lines.
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

    // Each step is one call the polling scan makes, named so an observation says which call saw it.
    private def steps(kind: String, root: JPath): Seq[(String, () => Unit)] =
        val exists: (String, () => Unit) = "exists"      -> (() => discard(Files.readAttributes(root, classOf[BasicFileAttributes])))
        val isDir: (String, () => Unit)  = "isDirectory" -> (() => discard(Files.isDirectory(root)))
        val list: (String, () => Unit)   = "list"        ->
            (() =>
                val stream = Files.list(root)
                try discard(stream.iterator().asScala.toList)
                finally stream.close()
            )
        kind match
            case "none"   => Seq.empty
            case "exists" => Seq(exists)
            case "list"   => Seq(list)
            case "all"    => Seq(exists, isDir, list)
        end match
    end steps

    private def key(step: String, ex: Throwable, base: JPath): String =
        s"$step:${ex.getClass.getSimpleName}"

    private def probe(op: String, kind: String, withChild: Boolean): String =
        val base       = Files.createTempDirectory(s"kyo-probe-$op-$kind")
        val failures   = mutable.LinkedHashMap.empty[String, Int]
        val scanSeen   = new ConcurrentHashMap[String, AtomicLong]()
        val afterSeen  = mutable.LinkedHashMap.empty[String, Int]
        var failed     = 0
        var maxTries   = 1
        var maxLinger  = 0L
        var lingerings = 0
        var round      = 0
        while round < rounds do
            val ancestor = base.resolve(s"anc-$round")
            val root     = if op.endsWith("Ancestor") then ancestor.resolve(s"root-$round") else base.resolve(s"root-$round")
            discard(Files.createDirectories(root))
            if withChild then discard(Files.writeString(root.resolve("child"), "x"))
            val stop   = new AtomicBoolean(false)
            val scans  = new AtomicLong(0L)
            val calls  = steps(kind, root)
            val thread = new Thread((
                () =>
                    while !stop.get() do
                        calls.foreach { (name, call) =>
                            try call()
                            catch
                                case ex: Throwable =>
                                    discard(scanSeen.computeIfAbsent(key(name, ex, base), _ => new AtomicLong(0L)).incrementAndGet())
                        }
                        discard(scans.incrementAndGet())
            ): Runnable)
            thread.setDaemon(true)
            thread.start()
            while kind != "none" && scans.get() < 3 do Thread.onSpinWait()
            var tries = 0
            var done  = false
            while !done && tries < 10000 do
                tries += 1
                try
                    op match
                        case "remove"         => removeAll(root)
                        case "move"           => discard(Files.move(root, base.resolve(s"moved-$round")))
                        case "moveAncestor"   => discard(Files.move(ancestor, base.resolve(s"moved-$round")))
                        case "removeAncestor" => removeAll(ancestor)
                    end match
                    done = true
                catch
                    case ex: Throwable =>
                        if tries == 1 then
                            failed += 1
                            val k = key(op, ex, base)
                            failures.update(k, failures.getOrElse(k, 0) + 1)
                        end if
                end try
            end while
            if tries > maxTries then maxTries = tries
            // What the mutating thread itself reads at the old path once the mutation returned, while the scan keeps going.
            val start   = java.lang.System.nanoTime()
            var gone    = false
            var lingers = false
            while !gone && java.lang.System.nanoTime() - start < 2000000000L do
                try
                    discard(Files.readAttributes(root, classOf[BasicFileAttributes]))
                    lingers = true
                    afterSeen.update("still-readable", afterSeen.getOrElse("still-readable", 0) + 1)
                catch
                    case _: java.nio.file.NoSuchFileException => gone = true
                    case ex: Throwable                        =>
                        lingers = true
                        val k = ex.getClass.getSimpleName
                        afterSeen.update(k, afterSeen.getOrElse(k, 0) + 1)
                end try
            end while
            val linger = (java.lang.System.nanoTime() - start) / 1000L
            if lingers then
                lingerings += 1
                if linger > maxLinger then maxLinger = linger
            end if
            stop.set(true)
            thread.join(5000)
            round += 1
        end while
        val scanDetail = scanSeen.asScala.toSeq.sortBy(_._1).map((k, v) => s"$k=${v.get()}").mkString(",")
        s"PROBE os=${java.lang.System.getProperty("os.name")} arch=${java.lang.System.getProperty("os.arch")} op=$op scan=$kind " +
            s"child=$withChild mutationFailures=$failed/$rounds [${failures.map((k, v) => s"$k=$v").mkString(",")}] maxTries=$maxTries " +
            s"scanSaw=[$scanDetail] afterMutation: roundsNotGoneAtOnce=$lingerings maxLingerMicros=$maxLinger " +
            s"saw=[${afterSeen.map((k, v) => s"$k=$v").mkString(",")}]"
    end probe

    "directory mutation under a concurrent scan" in {
        Sync.defer {
            val lines =
                for
                    op    <- Seq("moveAncestor", "removeAncestor")
                    kind  <- Seq("none", "exists", "list", "all")
                    child <- Seq(false, true)
                yield probe(op, kind, child)
            lines.foreach(line => java.lang.System.out.println(line))
            assert(lines.size == 16)
        }
    }
end WindowsDirRaceProbeTest
