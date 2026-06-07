package kyo

import kyo.internal.MemoryFileSource

/** Tasty.evictOlderThan.
  *
  * evictOlderThan deletes files older than cutoff, keeps recent files.
  * old files are removed from the in-memory source after eviction.
  * evictOlderThan actually removes bytes; no stale residue files appear.
  * evictOlderThan on a non-existent path aborts with SnapshotIoError or returns cleanly.
  *
  * Uses MemoryFileSource with controlled mtimes so the test is deterministic and cross-platform.
  */
class EvictOlderThanTest extends kyo.test.Test[Any]:

    // ── Leaf 9: evictOlderThan deletes files older than cutoff ───────────────
    // Given: cacheDir with 3.krfl files: two mtime 30 days ago, one mtime 1 hour ago
    // When: Tasty.Snapshot.evictOlderThanWithSource(cacheDir, 7 days, src)
    // Then: the two old files are deleted; the recent file remains; returns Success
    "evictOlderThan deletes old .krfl files and keeps recent ones" in {
        val src    = MemoryFileSource()
        val now    = java.lang.System.currentTimeMillis()
        val old1   = "cache/aaa.krfl"
        val old2   = "cache/bbb.krfl"
        val recent = "cache/ccc.krfl"

        // Two files 30 days old (beyond 7-day retention window).
        src.add(old1, Array[Byte](1, 2, 3))
        src.setMtime(old1, now - 30L * 24 * 60 * 60 * 1000)
        src.add(old2, Array[Byte](4, 5, 6))
        src.setMtime(old2, now - 30L * 24 * 60 * 60 * 1000)

        // One file 1 hour old (within 7-day retention window).
        src.add(recent, Array[Byte](7, 8, 9))
        src.setMtime(recent, now - 60L * 60 * 1000)

        val maxAgeMs = 7L * 24 * 60 * 60 * 1000 // 7 days in ms
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource("cache", maxAgeMs, src)
        ).map:
            case Result.Success(_) =>
                // after eviction via source.delete the old paths must be completely absent.
                val remaining = src.allPaths.filter(_.startsWith("cache/"))
                assert(remaining == Set("cache/ccc.krfl"), s"only recent file must remain; got: $remaining")
                succeed
            case Result.Failure(e) =>
                fail(s"evictOlderThan must succeed; got TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Leaf 9b: verify old files are completely removed after eviction ───────
    // after eviction via source.delete the.krfl namespace is clean.
    "evictOlderThan removes old files completely (no residual paths)" in {
        val src  = MemoryFileSource()
        val now  = java.lang.System.currentTimeMillis()
        val old1 = "cache2/aaaa.krfl"

        src.add(old1, Array[Byte](1, 2, 3))
        src.setMtime(old1, now - 30L * 24 * 60 * 60 * 1000)

        val maxAgeMs = 7L * 24 * 60 * 60 * 1000
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource("cache2", maxAgeMs, src).flatMap: _ =>
                // After eviction, listing.krfl files in cache2 must return empty.
                src.list("cache2", Chunk(".krfl"))
        ).map:
            case Result.Success(remaining) =>
                assert(
                    remaining.isEmpty,
                    s"old .krfl file must be deleted after eviction; remaining: $remaining"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"evictOlderThan must succeed; got TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Leaf 9c: evictOlderThan removes bytes; no stale residue files appear
    // Given: MemoryFileSource pre-loaded with old1, old2 (30 days old) and recent (1 hour old)
    // When: evictOlderThanWithSource(cacheDir, 7 days, src) runs to completion
    // Then: old paths are absent; recent path is present; no residue paths with ".deleting" suffix exist
    "evictOlderThan removes bytes from source; no stale residue paths appear" in {
        val src      = MemoryFileSource()
        val now      = java.lang.System.currentTimeMillis()
        val cacheDir = "cache9c"
        val old1     = s"$cacheDir/old1.krfl"
        val old2     = s"$cacheDir/old2.krfl"
        val recent   = s"$cacheDir/recent.krfl"

        src.add(old1, Array[Byte](1, 2, 3))
        src.setMtime(old1, now - 30L * 24 * 60 * 60 * 1000)
        src.add(old2, Array[Byte](4, 5, 6))
        src.setMtime(old2, now - 30L * 24 * 60 * 60 * 1000)
        src.add(recent, Array[Byte](7, 8, 9))
        src.setMtime(recent, now - 60L * 60 * 1000)

        val maxAgeMs = 7L * 24 * 60 * 60 * 1000
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource(cacheDir, maxAgeMs, src)
        ).map:
            case Result.Success(_) =>
                val paths = src.allPaths
                // Old files must be completely gone (: source.delete removes them directly).
                assert(paths.exists(_.contains("old1")) == false, s"old1 must be absent; paths: $paths")
                assert(paths.exists(_.contains("old2")) == false, s"old2 must be absent; paths: $paths")
                // Recent file must remain.
                assert(paths.contains(recent) == true, s"recent must remain; paths: $paths")
                // No stale residue from any prior rename-based approach.
                // The suffix ".deleting" and the double-suffix form must not appear.
                val deletingSuffix     = ".deleting"
                val deletingGoneSuffix = deletingSuffix + ".gone"
                assert(
                    paths.exists(_.endsWith(deletingSuffix)) == false,
                    s"no residue with .deleting suffix; paths: $paths"
                )
                assert(
                    paths.exists(_.endsWith(deletingGoneSuffix)) == false,
                    s"no residue with double-suffix form; paths: $paths"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"evictOlderThan must succeed; got TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Leaf 10: evictOlderThan on non-existent path returns Success cleanly ──
    // Given: a path that does not exist in the MemoryFileSource
    // When: Tasty.Snapshot.evictOlderThanWithSource(nonExistent, maxAgeMs, src)
    // Then: returns Result.Success -- listing an empty/absent directory returns Chunk.empty
    //       and there are no files to delete, so the operation is a no-op
    "evictOlderThan on non-existent directory is a no-op (Success)" in {
        val src         = MemoryFileSource()
        val nonExistent = "no-such-dir"
        val maxAgeMs    = 7L * 24 * 60 * 60 * 1000
        // MemoryFileSource.list returns Chunk.empty for any dir with no matching keys.
        // So evictOlderThan on a missing dir finds nothing to delete and returns Success.
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource(nonExistent, maxAgeMs, src)
        ).map:
            case Result.Success(_) =>
                succeed
            case Result.Failure(e) =>
                fail(s"evictOlderThan on missing dir must be a no-op; got TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

end EvictOlderThanTest
