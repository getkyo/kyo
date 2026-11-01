package kyo.lang

import java.net.URLClassLoader
import kyo.*
import scala.jdk.CollectionConverters.*

/** Drives the Scala compiler in-process against the `kyo-lang` plugin jar, for the suites that need a whole compilation.
  *
  * The build passes the plugin jar and the fixture source directory to the forked test JVM as the `kyo.lang.plugin` and
  * `kyo.lang.sources` properties. The compile classpath is this JVM's own, which carries kyo-core, the `kyo-lang` library, and the
  * Scala libraries. File work goes through `Path`; a suite runs it under `Path.run`.
  */
object Compilation:

    /** The `kyo-lang-plugin` jar the build produced. */
    def plugin(using Frame): Path < (Sync & Abort[Nothing]) = property("kyo.lang.plugin").map(Path(_))

    /** The directory holding the module's `.kyo` fixtures. */
    def sources(using Frame): Path < (Sync & Abort[Nothing]) = property("kyo.lang.sources").map(Path(_))

    /** This JVM's classpath entries. */
    def classpath(using Frame): Chunk[Path] < (Sync & Abort[Nothing]) =
        property("java.class.path").map(value => Chunk.from(value.split(Path.pathSeparator)).map(Path(_)))

    /** A missing property is a misconfigured test JVM, which no suite can recover from, so it panics. */
    private def property(name: String)(using Frame): String < (Sync & Abort[Nothing]) =
        System.property[String](name).map {
            case Present(value) => value
            case Absent         =>
                Abort.panic(new IllegalStateException(s"Missing system property $name; the build sets it for the forked test JVM"))
        }

    /** The diagnostics of one compiler run. */
    final case class Outcome(errors: Chunk[String]):
        def hasErrors: Boolean = errors.nonEmpty

        /** True when one error message contains `expected`. */
        def reports(expected: String): Boolean = errors.exists(_.contains(expected))
    end Outcome

    /** A temporary directory removed when the enclosing `Scope` closes. */
    def workspace(using Frame): Path < (PathWrite & Sync & Scope) = Path.tempDir("kyo-lang")

    /** Writes `content` as `name` under `dir` and returns the file. */
    def write(dir: Path, name: String, content: String)(using Frame): Path < PathWrite =
        val file = dir / name
        file.write(content).andThen(file)

    /** Compiles `files` into `destination`, with `dependencies` ahead of this JVM's classpath, loading the plugin when `withPlugin`. */
    def compile(files: Chunk[Path], destination: Path, dependencies: Chunk[Path], withPlugin: Boolean)(using
        Frame
    ): Outcome < (PathWrite & Sync & Abort[Nothing]) =
        for
            _       <- destination.mkDir
            jar     <- plugin
            entries <- classpath
            outcome <- Sync.defer {
                val plugins =
                    if withPlugin then List("-Xplugin:" + jar.toString, "-Xplugin-require:kyo-lang") else Nil
                val arguments =
                    List(
                        "-classpath",
                        (dependencies ++ entries).map(_.toString).mkString(Path.pathSeparator),
                        "-d",
                        destination.toString,
                        "-language:strictEquality",
                        "-Xcheck-macros",
                        "-color:never"
                    ) ::: plugins ::: files.map(_.toString).toList
                val reporter = dotty.tools.dotc.Main.process(arguments.toArray)
                Outcome(Chunk.from(reporter.allErrors).map(_.message))
            }
        yield outcome

    /** A class loader over `directories`, delegating everything else to this suite's loader. */
    def loader(directories: Chunk[Path])(using Frame): URLClassLoader < Sync =
        Sync.defer(new URLClassLoader(
            directories.map(dir => new java.io.File(dir.toString).toURI.toURL).toArray,
            Compilation.getClass.getClassLoader
        ))
end Compilation
