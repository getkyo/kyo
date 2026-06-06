package kyo.internal.tasty.query

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import kyo.*
import kyo.internal.tasty.classfile.ModuleInfoReader
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** JVM implementation: reads JDK module-info.class files from the `jrt:/` virtual filesystem.
  *
  * The `jrt:/` filesystem is mounted automatically by the JVM (no explicit `FileSystem.newFileSystem` call needed on JDK 11+). Module
  * descriptors live at `/modules/<module-name>/module-info.class` within that filesystem.
  *
  * Platform split so JS/Native return UnsupportedPlatform without carrying JVM-specific imports.
  */
private[kyo] object PlatformModuleOps:

    /** Read all JDK module descriptors from the `jrt:/` virtual filesystem.
      *
      * Returns a `Map[String, Tasty.Java.Module.Descriptor]` keyed by module name (e.g., "java.base"). Each module whose `module-info.class`
      * decodes successfully is included; modules with decode errors are silently skipped (soft-fail per the existing classpath contract).
      */
    def readJdkModuleDescriptors(using Frame): Map[String, Tasty.Java.Module.Descriptor] < (Sync & Abort[TastyError]) =
        Sync.defer:
            val jrtFs       = FileSystems.getFileSystem(URI.create("jrt:/"))
            val modulesRoot = jrtFs.getPath("/modules")
            Files.list(modulesRoot).iterator().asScala.toList
        .flatMap: moduleDirs =>
            val builder = scala.collection.mutable.Map.empty[String, Tasty.Java.Module.Descriptor]
            Kyo.foreachDiscard(moduleDirs): moduleDir =>
                val moduleInfoPath = moduleDir.resolve("module-info.class")
                Sync.defer(Files.exists(moduleInfoPath)).flatMap: exists =>
                    if exists then
                        Sync.defer(Files.readAllBytes(moduleInfoPath)).flatMap: bytes =>
                            Abort.run[TastyError](ModuleInfoReader.read(bytes)).map:
                                case Result.Success(desc) => builder(desc.name) = desc
                                case _                    => ()
                    else
                        (
                    )
            .map: _ =>
                builder.toMap

    /** Returns every `.class` path under the specified module subtrees in the running JDK's `jrt:/` filesystem (excluding
      * `module-info.class`, which is decoded separately as a module descriptor by `readJdkModuleDescriptors`). Paths are formatted as
      * `jrt://modules/<m>/<path/to/cls>.class` for consumption by `Tasty.Classpath.initWithPlatformModules`.
      *
      * Prior to this method, only `module-info.class` was decoded; JDK class symbols (`java.lang.String`,
      * `java.util.HashMap`, etc.) were unreachable. The classfile unpickler is complete; the only gap was the walker enumerating
      * only module-info.
      *
      * When `moduleFilter` is non-empty, only the named modules are walked. An empty set walks ALL modules, which is the
      * production default used by `initWithPlatformModules`. Tests should pass a small module set (e.g. `Set("java.base")`)
      * to limit decode time: java.base has ~7,000 classes vs ~27,000 across all modules.
      */
    def listJdkClassFiles(moduleFilter: Set[String] = Set.empty)(using AllowUnsafe): Chunk[String] =
        val fs = jrtFileSystem
        if fs == null then Chunk.empty
        else
            val modulesRoot = fs.getPath("/modules")
            if !Files.exists(modulesRoot) then Chunk.empty
            else
                val results = mutable.ArrayBuffer.empty[String]
                val moduleRoots: Iterator[java.nio.file.Path] =
                    if moduleFilter.isEmpty then
                        // Walk all modules (production path).
                        Files.list(modulesRoot).iterator().asScala
                    else
                        // Walk only the requested modules (test-fixture path).
                        moduleFilter.iterator.map(m => modulesRoot.resolve(m)).filter(Files.exists(_))
                moduleRoots.foreach: moduleRoot =>
                    Files.walk(moduleRoot).iterator().asScala.foreach: p =>
                        val name = p.getFileName.toString
                        if name.endsWith(".class") && name != "module-info.class" then
                            results += "jrt:/" + p.toString
                Chunk.from(results.toSeq)
            end if
        end if
    end listJdkClassFiles

    /** Lazy JRT filesystem handle. Returns null if JRT filesystem is unavailable. */
    // Unsafe: null sentinel for "unavailable"; Java FileSystems interop
    private lazy val jrtFileSystem: java.nio.file.FileSystem =
        try FileSystems.getFileSystem(URI.create("jrt:/"))
        catch
            case _: Throwable => null

end PlatformModuleOps
