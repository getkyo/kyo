package kyo

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

// JVM-only: exercises JVM class-initialization concurrency, which has no analogue on
// Scala.js (single-threaded) and differs on Scala Native. A codec object's config
// companion can read the codec object's own members as constructor defaults; if the
// codec object also forces the config companion during its own class init (a
// forward edge), two threads first-touching each class from opposite ends can block
// each other forever and poison both classes for the rest of the JVM. Each case
// below races a fresh-classloader first touch of the codec object against a first
// touch of its config companion's `Default` construction, covering every codec
// whose config reads codec-object constants: Yaml (the pairing that reproduced the
// original deadlock), Ion, and Bson.
//
// Json and IonBinary have no config companion of their own: their decode-limit
// constants are inline vals folded directly into the codec object itself, with no
// runtime read of any enclosing or companion object. Both are still raced here as
// degenerate concurrency smoke-tests (Json against Yaml.ReaderConfig, IonBinary
// against Ion.Config) to satisfy the guard-every-codec mandate, but neither pairing
// carries a live class-init-lock dependency in either direction: the raced classes
// cannot deadlock against each other today, and the case only proves the two classes
// initialize concurrently without wedging. A genuine future Json.Config or
// IonBinary.Config forced by its own codec object's clinit would need its own
// dedicated race case against that new companion. Raw threads and bounded waits are
// required: the subject is JVM class initialization itself, which cannot be
// suspended through effect-based primitives, and the trial loop below iterates the
// same raw mechanics for the same reason.
class CodecInitTest extends kyo.test.Test[Any]:

    private val Trials        = 20
    private val TrialDeadline = 20L

    private def classpathUrls: Array[java.net.URL] =
        val entries = java.lang.System.getProperty("java.class.path").split(File.pathSeparator).filter(_.nonEmpty)
        val expanded =
            if entries.length == 1 && entries(0).endsWith(".jar") then
                val jar = new JarFile(entries(0))
                val manifest =
                    try jar.getManifest
                    finally jar.close()
                val cp = Option(manifest).flatMap(m => Option(m.getMainAttributes.getValue("Class-Path")))
                cp match
                    case Some(list) => list.split(" ").filter(_.nonEmpty)
                    case None       => entries
            else entries
        expanded.map(p => new File(p).toURI.toURL)
    end classpathUrls

    /** Renders every live thread whose stack mentions one of `markers` as its name plus its frames, one per line, so a deadlock dump is
      * readable instead of the default array-of-Map.Entry toString.
      */
    private def deadlockDump(markers: Seq[String]): String =
        Thread.getAllStackTraces.asScala
            .filter { case (_, frames) => frames.exists(f => markers.exists(f.toString.contains)) }
            .map { case (thread, frames) =>
                (Seq(s"${thread.getName} (${thread.getState}):") ++ frames.map(f => s"    at $f")).mkString("\n")
            }
            .mkString("\n\n")

    /** Races a fresh-classloader first touch of `objectClassName` against a first touch of `configClassName` (module class, exposing a
      * `Default` accessor) from opposite ends, `Trials` times, each in its own classloader so class initialization genuinely re-runs every
      * trial.
      */
    private def raceFirstTouch(objectClassName: String, configClassName: String, configTypeName: String, markers: Seq[String])(using
        kyo.test.AssertScope
    ): Unit =
        val urls      = classpathUrls
        var completed = 0
        var trial     = 0
        while trial < Trials do
            val loader   = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader)
            val barrier  = new CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try
                val touchObject = executor.submit(new Callable[String]:
                    def call(): String =
                        barrier.await(TrialDeadline, TimeUnit.SECONDS)
                        Class.forName(objectClassName, true, loader).getName)
                val touchConfig = executor.submit(new Callable[String]:
                    def call(): String =
                        barrier.await(TrialDeadline, TimeUnit.SECONDS)
                        val cls    = Class.forName(configClassName, true, loader)
                        val module = cls.getField("MODULE$").get(null)
                        cls.getMethod("Default").invoke(module).getClass.getName)
                try
                    assert(touchObject.get(TrialDeadline, TimeUnit.SECONDS) == objectClassName)
                    assert(touchConfig.get(TrialDeadline, TimeUnit.SECONDS) == configTypeName)
                    completed += 1
                catch
                    case _: TimeoutException =>
                        fail(s"class-initialization deadlock on trial $trial:\n${deadlockDump(markers)}")
                end try
            finally
                executor.shutdownNow()
                loader.close()
            end try
            trial += 1
        end while
        assert(completed == Trials)
    end raceFirstTouch

    "concurrent first touch of Yaml and Yaml.ReaderConfig.Default completes" in {
        raceFirstTouch("kyo.Yaml$", "kyo.Yaml$ReaderConfig$", "kyo.Yaml$ReaderConfig", Seq("kyo.Yaml"))
    }

    "concurrent first touch of Ion and Ion.Config.Default completes" in {
        raceFirstTouch("kyo.Ion$", "kyo.Ion$Config$", "kyo.Ion$Config", Seq("kyo.Ion"))
    }

    "concurrent first touch of Bson and Bson.Config.Default completes" in {
        raceFirstTouch("kyo.Bson$", "kyo.Bson$Config$", "kyo.Bson$Config", Seq("kyo.Bson"))
    }

    "concurrent first touch of Json and Yaml.ReaderConfig.Default completes" in {
        raceFirstTouch("kyo.Json$", "kyo.Yaml$ReaderConfig$", "kyo.Yaml$ReaderConfig", Seq("kyo.Json", "kyo.Yaml"))
    }

    "concurrent first touch of IonBinary and Ion.Config.Default completes" in {
        raceFirstTouch("kyo.IonBinary$", "kyo.Ion$Config$", "kyo.Ion$Config", Seq("kyo.IonBinary", "kyo.Ion"))
    }
end CodecInitTest
