package kyo.internal

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kyo.*

class PathPlatformSpecificLockJVMTest extends kyo.test.Test[Any]:

    "native release failure remains typed and retryable" in {
        Sync.Unsafe.defer {
            val path     = Files.createTempFile("kyo-lock-release-", ".bin")
            val key      = path.toString
            val channel  = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
            var attempts = 0
            val lock = new FileLock(channel, 0L, Long.MaxValue, false):
                private var valid    = true
                def isValid: Boolean = valid
                def release(): Unit =
                    attempts += 1
                    if attempts == 1 then throw new IOException("injected release failure")
                    valid = false
                end release
            try
                assert(NioPathLockRegistry.reserve(key, true) == NioPathLockRegistry.Reservation.First)
                NioPathLockRegistry.install(key, lock, channel)
                val raw = new NioRawLock(key, true)
                raw.release() match
                    case Result.Failure(_: FileIOException) => assert(true)
                    case other                              => assert(false, s"expected typed release failure, got $other")
                assert(NioPathLockRegistry.isOwned(key, true))
                assert(raw.release().isSuccess)
                assert(attempts == 2)
                assert(!NioPathLockRegistry.isOwned(key, true))
            finally
                NioPathLockRegistry.abort(key)
                if channel.isOpen then channel.close()
                discard(Files.deleteIfExists(path))
            end try
        }
    }

    "registry ownership loss reaches check and release" in {
        Sync.Unsafe.defer {
            val key = Files.createTempFile("kyo-lock-loss-", ".bin").toString
            assert(NioPathLockRegistry.reserve(key, true) == NioPathLockRegistry.Reservation.First)
            NioPathLockRegistry.abort(key)
            val raw = new NioRawLock(key, true)
            assert(raw.check().isFailure)
            raw.release() match
                case Result.Failure(_: FileLockOwnershipLostException) => assert(true)
                case other => assert(false, s"expected ownership loss from absent registry entry, got $other")
            discard(Files.deleteIfExists(java.nio.file.Path.of(key)))
        }
    }

    "channel close failure remains typed and retries only unfinished cleanup" in {
        Sync.Unsafe.defer {
            val path     = Files.createTempFile("kyo-lock-close-", ".bin")
            val key      = path.toString
            val channel  = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
            var releases = 0
            var closes   = 0
            val lock = new FileLock(channel, 0L, Long.MaxValue, false):
                private var valid    = true
                def isValid: Boolean = valid
                def release(): Unit =
                    releases += 1
                    valid = false
            try
                assert(NioPathLockRegistry.reserve(key, true) == NioPathLockRegistry.Reservation.First)
                NioPathLockRegistry.install(
                    key,
                    lock,
                    channel,
                    () => lock.release(),
                    () =>
                        closes += 1
                        if closes == 1 then throw new IOException("injected close failure")
                        channel.close()
                )
                val raw = new NioRawLock(key, true)
                raw.release() match
                    case Result.Failure(_: FileIOException) => assert(true)
                    case other                              => assert(false, s"expected typed close failure, got $other")
                assert(releases == 1)
                assert(closes == 1)
                assert(raw.release().isSuccess)
                assert(releases == 1)
                assert(closes == 2)
            finally
                NioPathLockRegistry.abort(key)
                if channel.isOpen then channel.close()
                discard(Files.deleteIfExists(path))
            end try
        }
    }

end PathPlatformSpecificLockJVMTest
