package kyo.ffi.internal

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
        Files.createTempDirectory("kyo-ffi-f11-").nn

    "tryCleanupStaleLock" - {
        "removes an abandoned .lck file with no live lock holder" in {
            val dir = tempDir()
            val lck = dir.resolve("libabc-deadbeef.lck").nn
            Files.createFile(lck): Unit
            assert(Files.exists(lck) == true)

            val removed = NativeLoader.tryCleanupStaleLock(lck)

            assert(removed == true)
            assert(Files.exists(lck) == false)
        }

        "leaves a live-locked .lck file in place" in {
            val dir = tempDir()
            val lck = dir.resolve("libxyz-cafef00d.lck").nn
            val ch  = FileChannel.open(lck, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
            try
                val lk = ch.lock().nn
                try
                    val removed = NativeLoader.tryCleanupStaleLock(lck)
                    // Another in-JVM lock holder → tryLock throws OverlappingFileLockException, caller must NOT delete.
                    assert(removed == false)
                    assert(Files.exists(lck) == true)
                finally lk.release()
                end try
            finally ch.close()
            end try
        }

        "returns false for a missing lock file" in {
            val dir = tempDir()
            val lck = dir.resolve("does-not-exist.lck").nn
            assert(NativeLoader.tryCleanupStaleLock(lck) == false)
        }
    }

    "resolveExtractDir" - {
        "honours -Dkyo.ffi.extractDir= verbatim" in {
            val explicit = tempDir().resolve("f11-explicit").nn
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
            val out  = dir.resolve("libpayload-cafebabe.so").nn
            val data = "kyo-ffi F11 atomic payload".getBytes

            NativeLoader.writeAtomicRename(dir, out, data)

            assert(Files.exists(out) == true)
            assert(Files.readAllBytes(out).nn.toSeq == data.toSeq)
            // No `.tmp-<uuid>` sibling should remain, the atomic rename consumed the temp file.
            val entries = Files.list(dir).nn.iterator().nn
            while entries.hasNext do
                val name = entries.next().nn.getFileName.nn.toString
                assert(!name.contains(".tmp-"))
            end while
        }
    }

    "cleanupExtractedFiles" - {
        "removes files newer than install epoch" in {
            val dir   = tempDir()
            val fresh = dir.resolve("libfresh-00112233.so").nn
            Files.write(fresh, "fresh bytes".getBytes): Unit

            val reg =
                classOf[NativeLoader.type].nn.getDeclaredField("extractedThisJvm").nn
            reg.setAccessible(true)
            val set = reg.get(NativeLoader).asInstanceOf[java.util.Set[Path]]
            set.add(fresh): Unit
            try
                // Install epoch is BEFORE file creation, so fresh file's mtime ≥ install → deleted.
                NativeLoader.cleanupExtractedFiles(0L)
                assert(Files.exists(fresh) == false)
            finally set.remove(fresh): Unit
            end try
        }

        "leaves files older than install epoch alone" in {
            val dir  = tempDir()
            val old_ = dir.resolve("libold-44556677.so").nn
            Files.write(old_, "old bytes".getBytes): Unit

            val reg =
                classOf[NativeLoader.type].nn.getDeclaredField("extractedThisJvm").nn
            reg.setAccessible(true)
            val set = reg.get(NativeLoader).asInstanceOf[java.util.Set[Path]]
            set.add(old_): Unit
            try
                // Install epoch is FAR in the future, every file is older → none deleted.
                val farFuture = java.lang.System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365)
                NativeLoader.cleanupExtractedFiles(farFuture)
                assert(Files.exists(old_) == true)
            finally
                set.remove(old_): Unit
                Files.deleteIfExists(old_): Unit
            end try
        }
    }
end NativeLoaderConcurrencyTest
