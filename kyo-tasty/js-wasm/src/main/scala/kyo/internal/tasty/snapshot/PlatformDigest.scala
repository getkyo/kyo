package kyo.internal.tasty.snapshot

import kyo.*

/** JS platform: jar CEN walk is not available; fall back to path-only digest.
  *
  * On JS, RandomAccessFile does not exist and JarCentralDirectory is JVM-only. JS jar roots use a path-based xxh64-custom hash.
  * The digest is deterministic within a single run (same path always produces the same value) but is
  * NOT content-addressed: in-place jar mutation (replacing jar bytes without changing the path) will NOT invalidate the cache on JS.
  *
  * Limitation: users who mutate jars in-place on JS must clear the cache directory manually. Real
  * content-addressed digest on JS requires a synchronous file-stat API that is not available at this call site.
  */
private[kyo] object PlatformDigest:

    /** JS fallback: jar digest by path hash only (no CEN walk available on JS; not content-addressed for in-place jar mutation). */
    def digestForJarRoot(jarPath: String): Long =
        DigestComputer.digestForJarFallback(jarPath)

    /** jrt:/ roots do not exist on JS; returns empty. */
    def collectJrtStats(root: String)(using Frame): Seq[(String, Long, Long)] < (Sync & Abort[TastyError]) =
        Sync.defer(Seq.empty)

end PlatformDigest
