package kyo.ffi.internal

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.discard
import kyo.ffi.Test

/** D5, NativeLoader advisory-lock contention: in-JVM isolation test.
  *
  * The [[NativeLoader.extractBytesToTemp]] code path uses `FileChannel.lock()` (a blocking advisory lock) on a `.lck` file to coordinate
  * concurrent extractions across JVMs. The multi-JVM fork-N-JVM stress is covered by [[NativeLoaderForkStressSpec]].
  *
  * **Why not two-thread in-JVM lock contention?** Java's `FileChannel.lock()` is a JVM-wide lock, not a per-thread lock. Attempting to
  * acquire a `FileLock` on a file that another thread in the same JVM already holds throws `OverlappingFileLockException`. This is a
  * fundamental JVM constraint: the advisory lock coordination mechanism is designed for cross-JVM (cross-process) coordination only. The
  * in-process thread-safety of the extraction path is handled by the `ConcurrentHashMap` cache and the `computeIfAbsent` call in
  * [[NativeLoader.load]], not by the `FileLock`.
  *
  * This spec tests the observable single-thread aspects of the advisory lock protocol:
  *   - A fresh lock can be acquired and released without error.
  *   - A held lock prevents `tryCleanupStaleLock` from removing the file (tested cross-thread: holder thread vs cleanup thread).
  *   - Releasing the lock allows subsequent re-acquisition.
  */
class NativeLoaderLockTest extends Test:

    private def tempLockFile(): Path =
        val dir = Files.createTempDirectory("kyo-ffi-lock-spec-").nn
        dir.resolve("libtest-deadbeef.lck").nn
    end tempLockFile

    "FileLock advisory coordination" - {

        "single-thread: lock can be acquired and released cleanly" in {
            val lockFile = tempLockFile()
            val ch       = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
            try
                val lk: FileLock = ch.lock().nn
                assert(lk.isValid == true)
                assert(lk.isShared == false)
                lk.release()
                assert(lk.isValid == false)
            finally ch.close()
            end try
        }

        "single-thread: released lock allows re-acquisition on same channel" in {
            val lockFile = tempLockFile()
            val ch       = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
            try
                val lk1: FileLock = ch.lock().nn
                lk1.release()
                // After release, we can acquire again on the same channel.
                val lk2: FileLock = ch.lock().nn
                assert(lk2.isValid == true)
                lk2.release()
            finally ch.close()
            end try
        }

        // Cross-thread: holder thread holds the lock, cleanup thread tests tryCleanupStaleLock.
        // This test is valid because tryCleanupStaleLock uses tryLock (non-blocking), which throws
        // OverlappingFileLockException when another thread in the same JVM holds the lock.
        "tryCleanupStaleLock does not remove a live-locked file even from another thread" in {
            val lockFile = tempLockFile()
            val lckHeld  = new CountDownLatch(1)
            val doClean  = new CountDownLatch(1)
            val holdErr  = new AtomicReference[Throwable](null)

            val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn

            val holder = new Thread(
                () =>
                    try
                        val lk: FileLock = ch.lock().nn
                        try
                            lckHeld.countDown(): Unit
                            discard(doClean.await(10L, TimeUnit.SECONDS))
                        finally lk.release()
                        end try
                    catch case t: Throwable => holdErr.set(t)
                    finally ch.close()
                    end try
                ,
                "test-staleclean-holder"
            )

            holder.start(): Unit
            discard(lckHeld.await(10L, TimeUnit.SECONDS))

            // tryCleanupStaleLock must NOT remove the file while another in-JVM thread holds the lock.
            val cleaned = NativeLoader.tryCleanupStaleLock(lockFile)

            doClean.countDown(): Unit
            holder.join(10_000L): Unit

            Option(holdErr.get()).foreach(throw _)
            // The live-locked file must not have been removed.
            assert(cleaned == false)
            // File still exists (holder did not delete it either).
            assert(Files.exists(lockFile) == true)
        }
    }
end NativeLoaderLockTest
