package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.query.JarCentralDirectory

/** JVM platform: walk the JAR central directory to compute a content-addressed digest.
  *
  * Uses JarCentralDirectory.read to enumerate CEN entries and feed their (name, CRC32) pairs to the xxh3-based digestForJar. This digest is
  * stable across mtime changes and machine boundaries (INV-003). The CRC32 values stored in the CEN are computed by the jar writer and
  * reflect the actual entry byte content.
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object PlatformDigest:

    /** Compute the content-addressed digest for a jar root on JVM.
      *
      * Reads the CEN via RandomAccessFile under AllowUnsafe; converts JarEntry records to JarDigestEntry and delegates to
      * DigestComputer.digestForJar.
      *
      * Unsafe: synchronous JAR CEN walk via JarCentralDirectory.read; AllowUnsafe bounded to this call site; no Scope required.
      */
    def digestForJarRoot(jarPath: String): Long =
        // Unsafe: synchronous JAR CEN walk via JarCentralDirectory.read; bounded to this helper; no Scope required.
        val entries       = JarCentralDirectory.read(jarPath)(using AllowUnsafe.embrace.danger)
        val digestEntries = entries.map(e => DigestComputer.JarDigestEntry(e.name, e.crc32))
        DigestComputer.digestForJar(digestEntries)
    end digestForJarRoot

end PlatformDigest
