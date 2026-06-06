package kyo.test.runner.internal

import java.io.BufferedReader
import java.io.InputStreamReader
import kyo.Chunk
import kyo.Result
import kyo.test.internal.TestBase
import scala.jdk.CollectionConverters.*

/** JVM service-loader-based test class discovery.
  *
  * Reads all `META-INF/services/kyo.test.Test` resource files from the current thread's context classloader. Each line in those files is
  * the fully qualified name of a class that extends `kyo.test.Test`. Lines that are blank or start with `#` are ignored (standard
  * service-loader convention).
  *
  * Each class name is loaded via `Class.forName` and cast to `Class[? <: TestBase[?]]`. Classes that fail to load are recorded in
  * [[DiscoveryResult.errors]] so the runner can surface them in the summary.
  *
  * This object is in `jvm/` to shadow the shared-default [[SuiteDiscoveryPlatform]] stub, which returns an empty `Chunk`. The shared `Cli`
  * object calls [[SuiteDiscoveryPlatform.discover()]] which resolves to this implementation on JVM.
  */
private[internal] object SuiteDiscovery:

    /** Result of a discovery pass: the classes that loaded successfully plus any per-line error messages. */
    final private[internal] case class DiscoveryResult(
        classes: Chunk[Class[? <: TestBase[?]]],
        errors: Chunk[String]
    ) derives CanEqual

    private val ServiceFile = "META-INF/services/kyo.test.Test"

    def discover(): Chunk[Class[? <: TestBase[?]]] =
        discoverDetailed(Thread.currentThread().getContextClassLoader).classes

    def discover(loader: ClassLoader): Chunk[Class[? <: TestBase[?]]] =
        discoverDetailed(loader).classes

    private def readLines(loader: ClassLoader): Chunk[String] =
        Chunk.from(loader.getResources(ServiceFile).asScala.toIndexedSeq).flatMap { url =>
            val in = url.openStream()
            try
                val reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))
                try Chunk.from(reader.lines().iterator().asScala.toIndexedSeq)
                finally reader.close()
            finally in.close()
            end try
        }

    private def classifyLine(loader: ClassLoader, line: String): Result[String, Class[? <: TestBase[?]]] =
        try
            val cls = loader.loadClass(line)
            if classOf[TestBase[?]].isAssignableFrom(cls) then
                // Unsafe: isAssignableFrom guard above confirms cls extends TestBase[?]; the type param is erased so this cast is safe
                Result.succeed(cls.asInstanceOf[Class[? <: TestBase[?]]])
            else
                Result.fail(s"kyo-test: class '$line' in $ServiceFile does not extend kyo.test.Test - skipping")
            end if
        catch
            case e: ClassNotFoundException =>
                Result.fail(s"kyo-test: cannot load class '$line' from $ServiceFile - ${e.getMessage}")
            case e: Throwable =>
                Result.fail(s"kyo-test: unexpected error loading '$line' from $ServiceFile - $e")

    def discoverDetailed(loader: ClassLoader): DiscoveryResult =
        val trimmed = readLines(loader).map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#"))
        val zero    = DiscoveryResult(Chunk.empty, Chunk.empty)
        trimmed.foldLeft(zero) { (acc, line) =>
            classifyLine(loader, line) match
                case Result.Success(cls) => acc.copy(classes = acc.classes :+ cls)
                case Result.Failure(msg) =>
                    java.lang.System.err.println(msg)
                    acc.copy(errors = acc.errors :+ msg)
        }
    end discoverDetailed

end SuiteDiscovery
