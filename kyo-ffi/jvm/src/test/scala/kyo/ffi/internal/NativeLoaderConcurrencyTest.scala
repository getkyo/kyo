package kyo.ffi.internal

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.Test

/** Resource-extraction concurrency.
  *
  * Deterministically exercises the helpers that back the cross-process extraction safety net:
  *   - `tryCleanupStaleLock`: abandoned `.lck` files from crashed peer processes get cleaned up.
  *   - `resolveExtractDir`: `-Dkyo.ffi.extractDir=` overrides every other lookup knob.
  *   - `cleanupExtractedFiles`: the opt-in shutdown hook only removes this-JVM files newer than install time.
  *
  * The multi-process fork-N-JVMs stress sits in `NativeLoaderForkStressSpec` so fast unit coverage stays on the mainline test pass; this
  * spec is pure FS helper plumbing and runs in milliseconds.
  */
class NativeLoaderConcurrencyTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    private def tempDir(): Path =
        Sync.Unsafe.evalOrThrow(Path.tempDir("kyo-ffi-f11-"))

    // NativeLoader's API, FileChannel, and the extracted-file registry are java.nio.file.Path infra (FFM, cross-process
    // advisory locks, atomic-move fallback); bridge the test's kyo.Path values to java only at those call sites.
    private def j(p: Path): java.nio.file.Path =
        java.nio.file.Path.of(p.toString)

    "tryCleanupStaleLock" - {
        "removes an abandoned .lck file with no live lock holder" in {
            val dir = tempDir()
            val lck = dir / "libabc-deadbeef.lck"
            lck.unsafe.mkFile().getOrThrow
            assert(lck.unsafe.exists() == true)

            val removed = NativeLoader.tryCleanupStaleLock(j(lck))

            assert(removed == true)
            assert(lck.unsafe.exists() == false)
        }

        "leaves a live-locked .lck file in place" in {
            val dir = tempDir()
            val lck = dir / "libxyz-cafef00d.lck"
            val ch  = FileChannel.open(j(lck), StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
            try
                val lk = ch.lock().nn
                try
                    val removed = NativeLoader.tryCleanupStaleLock(j(lck))
                    // Another in-JVM lock holder → tryLock throws OverlappingFileLockException, caller must NOT delete.
                    assert(removed == false)
                    assert(lck.unsafe.exists() == true)
                finally lk.release()
                end try
            finally ch.close()
            end try
        }

        "returns false for a missing lock file" in {
            val dir = tempDir()
            val lck = dir / "does-not-exist.lck"
            assert(NativeLoader.tryCleanupStaleLock(j(lck)) == false)
        }
    }

    "resolveExtractDir" - {
        "honours -Dkyo.ffi.extractDir= verbatim" in {
            val explicit = tempDir() / "f11-explicit"
            val prop     = "kyo.ffi.extractDir"
            val prior    = Option(java.lang.System.getProperty(prop))
            java.lang.System.setProperty(prop, explicit.toString): Unit
            try
                val resolved = NativeLoader.resolveExtractDir()
                assert(resolved.toString == explicit.toString)
                // Should NOT append /kyo-ffi, the override is literal. This is the point.
                assert(!resolved.toString.endsWith("kyo-ffi"))
            finally
                prior match
                    case Some(v) => java.lang.System.setProperty(prop, v): Unit
                    case None    => java.lang.System.clearProperty(prop): Unit
            end try
        }

        "falls back to java.io.tmpdir/kyo-ffi when no override is set" in {
            val prop  = "kyo.ffi.extractDir"
            val prior = Option(java.lang.System.getProperty(prop))
            java.lang.System.clearProperty(prop): Unit
            try
                val resolved = NativeLoader.resolveExtractDir()
                assert(resolved.toString.endsWith("kyo-ffi"))
            finally prior.foreach(v => java.lang.System.setProperty(prop, v): Unit)
            end try
        }
    }

    "writeAtomicRename" - {
        "atomically installs the full payload and leaves no .tmp-<uuid> residue on success" in {
            val dir  = tempDir()
            val out  = dir / "libpayload-cafebabe.so"
            val data = "kyo-ffi F11 atomic payload"

            NativeLoader.writeAtomicRename(j(dir), j(out), data.getBytes)

            assert(out.unsafe.exists() == true)
            assert(out.unsafe.read().getOrThrow == data)
            // No `.tmp-<uuid>` sibling should remain, the atomic rename consumed the temp file.
            // Path.Unsafe.list closes the dir stream as it collects (no leaked fd).
            val entries = dir.unsafe.list().getOrThrow
            entries.foreach(entry => assert(!entry.name.getOrElse("").contains(".tmp-")))
        }
    }

    "cleanupExtractedFiles" - {
        "removes files newer than install epoch" in {
            val dir   = tempDir()
            val fresh = dir / "libfresh-00112233.so"
            fresh.unsafe.write("fresh bytes").getOrThrow

            val reg =
                classOf[NativeLoader.type].nn.getDeclaredField("extractedThisJvm").nn
            reg.setAccessible(true)
            val set = reg.get(NativeLoader).asInstanceOf[java.util.Set[java.nio.file.Path]]
            set.add(j(fresh)): Unit
            try
                // Install epoch is BEFORE file creation, so fresh file's mtime ≥ install → deleted.
                NativeLoader.cleanupExtractedFiles(0L)
                assert(fresh.unsafe.exists() == false)
            finally set.remove(j(fresh)): Unit
            end try
        }

        "leaves files older than install epoch alone" in {
            val dir  = tempDir()
            val old_ = dir / "libold-44556677.so"
            old_.unsafe.write("old bytes").getOrThrow

            val reg =
                classOf[NativeLoader.type].nn.getDeclaredField("extractedThisJvm").nn
            reg.setAccessible(true)
            val set = reg.get(NativeLoader).asInstanceOf[java.util.Set[java.nio.file.Path]]
            set.add(j(old_)): Unit
            try
                // Install epoch is FAR in the future, every file is older → none deleted.
                val farFuture = java.lang.System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365)
                NativeLoader.cleanupExtractedFiles(farFuture)
                assert(old_.unsafe.exists() == true)
            finally
                set.remove(j(old_)): Unit
                discard(old_.unsafe.remove())
            end try
        }
    }
end NativeLoaderConcurrencyTest
