package kyo.internal.tasty.query

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import kyo.*
import kyo.internal.tasty.classfile.ModuleInfoReader
import scala.jdk.CollectionConverters.*

/** JVM implementation: reads JDK module-info.class files from the `jrt:/` virtual filesystem.
  *
  * The `jrt:/` filesystem is mounted automatically by the JVM (no explicit `FileSystem.newFileSystem` call needed on JDK 11+). Module
  * descriptors live at `/modules/<module-name>/module-info.class` within that filesystem.
  *
  * Q-006 / F-D-001: platform split so JS/Native return UnsupportedPlatform without carrying JVM-specific imports.
  */
private[kyo] object PlatformModuleOps:

    /** Read all JDK module descriptors from the `jrt:/` virtual filesystem.
      *
      * Returns a `Map[String, Tasty.ModuleDescriptor]` keyed by module name (e.g., "java.base"). Each module whose `module-info.class`
      * decodes successfully is included; modules with decode errors are silently skipped (soft-fail per the existing classpath contract).
      */
    def readJdkModuleDescriptors(using Frame): Map[String, Tasty.ModuleDescriptor] < (Sync & Abort[TastyError]) =
        Sync.defer:
            val jrtFs       = FileSystems.getFileSystem(URI.create("jrt:/"))
            val modulesRoot = jrtFs.getPath("/modules")
            Files.list(modulesRoot).iterator().asScala.toList
        .flatMap: moduleDirs =>
            val builder = scala.collection.mutable.Map.empty[String, Tasty.ModuleDescriptor]
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

end PlatformModuleOps
