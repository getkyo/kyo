package kyo.internal.tasty.snapshot

import kyo.*

/** Native platform: jar CEN walk is not available; fall back to path-only digest.
  *
  * On Native, the JarCentralDirectory is JVM-only. Native jar roots use a path-based xxh64-custom hash (INV-003 Native fallback).
  * The digest is deterministic within a single run (same path always produces the same value) but is NOT content-addressed:
  * in-place jar mutation (replacing jar bytes without changing the path) will NOT invalidate the cache on Native.
  *
  * Limitation: users who mutate jars in-place on Native must clear the cache directory manually. This is a known trade-off; real
  * content-addressed digest on Native requires a synchronous file-stat API that is not available at this call site. The JVM platform
  * uses a CEN-based content-addressed digest (JarCentralDirectory.read) which does not have this limitation.
  *
  * See also: `Tasty.withClasspath(roots, cacheDir)` scaladoc for the full platform-by-platform digest contract.
  */
private[kyo] object PlatformDigest:

    /** Native fallback: jar digest by path hash only (no CEN walk available on Native; not content-addressed for in-place jar mutation). */
    def digestForJarRoot(jarPath: String): Long =
        DigestComputer.digestForJarFallback(jarPath)

end PlatformDigest
