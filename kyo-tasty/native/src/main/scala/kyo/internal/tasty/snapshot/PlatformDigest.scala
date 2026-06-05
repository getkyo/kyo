package kyo.internal.tasty.snapshot

import kyo.*

/** Native platform: jar CEN walk is not available in this shared snapshot impl; fall back to mtime+size digest.
  *
  * On Native, the JarCentralDirectory is JVM-only. Native jar roots use the same mtime+size hash as the legacy FNV-1a path (INV-003
  * Native fallback). The digest is still deterministic within a single run but not content-addressed across machine boundaries.
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object PlatformDigest:

    /** Native fallback: jar digest by mtime+size (no CEN walk available on Native). */
    def digestForJarRoot(jarPath: String): Long =
        DigestComputer.digestForJarFallback(jarPath)

end PlatformDigest
