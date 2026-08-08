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

/** The interval between a reservation being taken and its underlying lock being installed.
  *
  * `reserve` returns `First` and the caller then opens a channel and takes the platform lock, so
  * for that span the entry exists with no resource behind it. A shared request arriving in that
  * span is compatible with the shared acquisition already under way, and the contract grants it.
  */
class PathPlatformSpecificLockInstallWindowJVMTest extends kyo.test.Test[Any]:

    "a shared request during another shared acquisition's install window is not denied" in {
        Sync.Unsafe.defer {
            val key = Files.createTempFile("kyo-lock-window-", ".bin").toString
            try
                // The first shared acquisition reserves and has not installed yet, which is exactly
                // the state the caller is in while it opens its channel.
                assert(NioPathLockRegistry.reserve(key, false) == NioPathLockRegistry.Reservation.First)

                // A second shared request lands here. It is compatible with the first, so denying it
                // refuses a lock the contract grants. Pending says the answer is not settled yet.
                val second = NioPathLockRegistry.reserve(key, false)
                assert(
                    second == NioPathLockRegistry.Reservation.Pending,
                    s"a compatible shared request in the install window got $second"
                )

                // An exclusive request in the same window genuinely conflicts with the shared
                // acquisition under way, so it stays denied rather than becoming pending.
                assert(
                    NioPathLockRegistry.reserve(key, true) == NioPathLockRegistry.Reservation.Denied,
                    "an exclusive request was told to wait for a shared acquisition it conflicts with"
                )
            finally
                NioPathLockRegistry.abort(key)
                discard(Files.deleteIfExists(java.nio.file.Path.of(key)))
            end try
        }
    }

    "a shared request during an exclusive acquisition's install window stays denied" in {
        Sync.Unsafe.defer {
            val key = Files.createTempFile("kyo-lock-window-excl-", ".bin").toString
            try
                assert(NioPathLockRegistry.reserve(key, true) == NioPathLockRegistry.Reservation.First)
                assert(
                    NioPathLockRegistry.reserve(key, false) == NioPathLockRegistry.Reservation.Denied,
                    "a shared request was told to wait for an exclusive acquisition it conflicts with"
                )
            finally
                NioPathLockRegistry.abort(key)
                discard(Files.deleteIfExists(java.nio.file.Path.of(key)))
            end try
        }
    }
    "a pending reservation is waited out rather than reported as a conflict" in {
        Sync.Unsafe.defer {
            val file = Files.createTempFile("kyo-lock-pending-", ".bin")
            // Matches the key tryLock derives, which resolves the path. The file exists, so the
            // resolved form is its real path.
            (file, file.toRealPath().toString, Path.of(file))
        }.map { (file, key, path) =>
            // Stands in for an acquisition that has reserved its entry and is still opening its
            // channel. Driving it from the registry rather than from a second fiber keeps the window
            // open for as long as the assertions need, with nothing parked on a thread to hold it.
            Sync.Unsafe.defer(NioPathLockRegistry.reserve(key, false)).map { reserved =>
                assert(reserved == NioPathLockRegistry.Reservation.First)
                Fiber.initUnscoped(Scope.run(FileSystem.host.tryLock(path, Path.LockMode.Shared).map(_.isDefined))).map { fiber =>
                    Async.sleep(20.millis).andThen(fiber.poll).map { answered =>
                        // Answering here is the defect: the claim under way is shared, this request
                        // is shared, and the two are compatible. Absent would be a conflict reported
                        // where none exists.
                        assert(answered.isEmpty, "tryLock answered while a compatible claim was still installing")
                        Sync.Unsafe.defer {
                            val channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
                            NioPathLockRegistry.install(key, channel.tryLock(0L, Long.MaxValue, true), channel)
                        }.andThen(fiber.get).map { acquired =>
                            assert(acquired, "the retry did not pick up the claim once it was installed")
                        }
                    }
                }
            }.map { result =>
                // The manual reservation above is still counted; drop it so the entry does not
                // outlive the test.
                Sync.Unsafe.defer {
                    discard(NioPathLockRegistry.release(key, false))
                    discard(Files.deleteIfExists(file))
                }.andThen(result)
            }
        }
    }
end PathPlatformSpecificLockInstallWindowJVMTest
