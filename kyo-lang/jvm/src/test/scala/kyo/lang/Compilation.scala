package kyo.lang

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import scala.jdk.CollectionConverters.*

/** Drives the Scala compiler in-process against the `kyo-lang` plugin jar, for the suites that need a whole compilation.
  *
  * The build passes the plugin jar and the fixture source directory to the forked test JVM as the `kyo.lang.plugin` and
  * `kyo.lang.sources` properties. The compile classpath is this JVM's own, which carries kyo-core, the `kyo-lang` library, and the
  * Scala libraries.
  */
object Compilation:

    /** The `kyo-lang-plugin` jar the build produced. */
    val plugin: Path = Path.of(property("kyo.lang.plugin"))

    /** The directory holding the module's `.kyo` fixtures. */
    val sources: Path = Path.of(property("kyo.lang.sources"))

    private def property(name: String): String =
        Maybe(java.lang.System.getProperty(name)).getOrElse(
            throw new IllegalStateException(s"Missing system property $name; the build sets it for the forked test JVM")
        )

    /** The diagnostics of one compiler run. */
    final case class Outcome(errors: List[String]):
        def hasErrors: Boolean = errors.nonEmpty

        /** True when one error message contains `expected`. */
        def reports(expected: String): Boolean = errors.exists(_.contains(expected))
    end Outcome

    /** A temporary directory removed when the enclosing `Scope` closes. */
    def workspace(using Frame): Path < (Sync & Scope) =
        Scope.acquireRelease(Sync.defer(Files.createTempDirectory("kyo-lang-")))(dir => Sync.defer(delete(dir)))

    private def delete(path: Path): Unit =
        if Files.isDirectory(path) then
            val children = Files.list(path)
            try children.iterator().asScala.foreach(delete)
            finally children.close()
        end if
        Files.deleteIfExists(path)
        ()
    end delete

    /** Writes `content` as `name` under `dir` and returns the file. */
    def write(dir: Path, name: String, content: String)(using Frame): Path < Sync =
        Sync.defer {
            val file = dir.resolve(name)
            Files.createDirectories(file.getParent)
            Files.writeString(file, content, StandardCharsets.UTF_8)
        }

    /** Compiles `files` into `destination`, with `dependencies` ahead of this JVM's classpath, loading the plugin when `withPlugin`. */
    def compile(files: List[Path], destination: Path, dependencies: List[Path], withPlugin: Boolean)(using Frame): Outcome < Sync =
        Sync.defer {
            Files.createDirectories(destination)
            val classpath = (dependencies.map(_.toString) :+ java.lang.System.getProperty("java.class.path"))
                .mkString(File.pathSeparator)
            val plugins =
                if withPlugin then List("-Xplugin:" + plugin.toString, "-Xplugin-require:kyo-lang") else Nil
            val arguments =
                List("-classpath", classpath, "-d", destination.toString, "-language:strictEquality", "-Xcheck-macros", "-color:never") :::
                    plugins ::: files.map(_.toString)
            val reporter = dotty.tools.dotc.Main.process(arguments.toArray)
            Outcome(reporter.allErrors.map(_.message))
        }

    /** A class loader over `directories`, delegating everything else to this suite's loader. */
    def loader(directories: List[Path]): URLClassLoader =
        new URLClassLoader(directories.map(_.toUri.toURL).toArray, getClass.getClassLoader)
end Compilation
