package kyo.internal.tasty.query

import kyo.*

/** Scala.js implementation: JPMS module resolution is not available on this platform.
  *
  * The `jrt:/` virtual filesystem does not exist in the JS runtime. `readJdkModuleDescriptors` fails with
  * `TastyError.UnsupportedPlatform`. `listJdkClassFiles` returns Chunk.empty so
  * `initWithPlatformModules` degrades gracefully on JS without JDK class entries.
  */
private[kyo] object PlatformModuleOps:
    def readJdkModuleDescriptors(using Frame): Map[String, Tasty.Java.Module.Descriptor] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.UnsupportedPlatform("initWithPlatformModules is JVM-only"))

    /** Returns Chunk.empty on JS: jrt:/ filesystem is not available. */
    def listJdkClassFiles(moduleFilter: Set[String] = Set.empty, classFilter: Set[String] = Set.empty)(using AllowUnsafe): Chunk[String] =
        Chunk.empty
end PlatformModuleOps
