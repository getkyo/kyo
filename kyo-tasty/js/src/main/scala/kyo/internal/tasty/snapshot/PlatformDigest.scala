package kyo.internal.tasty.snapshot

import kyo.*

/** JS platform: jar CEN walk is not available; fall back to mtime+size digest.
  *
  * On JS, RandomAccessFile does not exist and JarCentralDirectory is JVM-only. JS jar roots use the same mtime+size hash as the legacy
  * FNV-1a path (INV-003 JS/Native fallback). The digest is still deterministic within a single run but not content-addressed across machine
  * boundaries.
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object PlatformDigest:

    /** JS fallback: jar digest by mtime+size (no CEN walk available on JS). */
    def digestForJarRoot(jarPath: String): Long =
        DigestComputer.digestForJarFallback(jarPath)

end PlatformDigest
