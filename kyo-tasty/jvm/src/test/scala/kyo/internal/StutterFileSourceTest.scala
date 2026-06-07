package kyo.internal

import kyo.*
import kyo.internal.tasty.query.JvmFileSource

/** Tests for the StutterFileSource primitive.
  *
  * Verifies that wrapping(delegate).read blocks until the semaphore is released, then completes promptly. This validates the latch primitive
  * before it is used in the concurrent-reader-writer test.
  */
class StutterFileSourceTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "StutterFileSource.wrapping blocks read until semaphore is released" in {
        // Write a temp file to read
        val tmpDir  = java.nio.file.Files.createTempDirectory("kyo-stutter-test")
        val tmpFile = tmpDir.resolve("stutter.dat")
        java.nio.file.Files.write(tmpFile, Array[Byte](1, 2, 3, 4, 5))
        val filePath             = tmpFile.toString
        val (stutter, semaphore) = StutterFileSource.wrapping(JvmFileSource)
        // Launch the read in a background fiber; it will block at semaphore.acquire
        Fiber.init:
            Abort.run[TastyError](stutter.read(filePath))
        .map: readFiber =>
            // Give the fiber time to start and reach semaphore.acquire
            Async.sleep(50.millis).andThen:
                // Verify the fiber has not yet completed (it's waiting for the semaphore)
                readFiber.done.map: isDone =>
                    assert(!isDone, "Fiber should still be blocked at semaphore.acquire before release")
                    // Release the semaphore: the fiber should now proceed
                    Sync.defer(semaphore.release()).andThen:
                        // Wait for the fiber to complete
                        readFiber.get.map: result =>
                            result match
                                case Result.Success(bytes) =>
                                    assert(bytes.length == 5, s"Expected 5 bytes; got ${bytes.length}")
                                    succeed
                                case Result.Failure(err) =>
                                    fail(s"Read failed with TastyError: $err")
    }

end StutterFileSourceTest
