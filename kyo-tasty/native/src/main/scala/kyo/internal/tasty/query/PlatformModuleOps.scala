package kyo.internal.tasty.query

import kyo.*

/** Scala Native implementation: JPMS module resolution is not available on this platform.
  *
  * The `jrt:/` virtual filesystem does not exist in the Native runtime. `readJdkModuleDescriptors` fails with
  * `TastyError.UnsupportedPlatform` per Q-006 / F-D-001. `listJdkClassFiles` returns Chunk.empty so
  * `initWithPlatformModules` degrades gracefully on Native without JDK class entries.
  */
private[kyo] object PlatformModuleOps:
    def readJdkModuleDescriptors(using Frame): Map[String, Tasty.ModuleDescriptor] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.UnsupportedPlatform("initWithPlatformModules is JVM-only"))

    /** Returns Chunk.empty on Native: jrt:/ filesystem is not available. */
    def listJdkClassFiles(moduleFilter: Set[String] = Set.empty)(using AllowUnsafe): Chunk[String] = Chunk.empty
end PlatformModuleOps
