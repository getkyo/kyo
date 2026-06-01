package kyo.internal.tasty.query

import kyo.*

/** Scala Native implementation: JPMS module resolution is not available on this platform.
  *
  * The `jrt:/` virtual filesystem does not exist in the Native runtime. `readJdkModuleDescriptors` fails with
  * `TastyError.UnsupportedPlatform` per Q-006 / F-D-001.
  */
private[kyo] object PlatformModuleOps:
    def readJdkModuleDescriptors(using Frame): Map[String, Tasty.ModuleDescriptor] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.UnsupportedPlatform("initWithPlatformModules is JVM-only"))
end PlatformModuleOps
