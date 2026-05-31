package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kyo.*

/** JVM-only scenarios for [[BrowserLauncher]].
  *
  * Lives in the JVM test tree because it drives `BrowserLauncher.createTempDir`'s read-only-parent abort path via the JVM-only
  * `java.nio.file.Files` / `java.nio.file.attribute.PosixFilePermissions` APIs, which have no Scala.js shim.
  */
class BrowserLauncherJvmTest extends Test:

    // createTempDir failure path: point at a read-only parent directory; assert Abort shape.
    "createTempDir aborts with BrowserSetupFailedException when the temp parent is not writable" in run {
        val outerTmp = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val parent   = Files.createTempDirectory(outerTmp, s"kyo-browser-jvm-test-${UUID.randomUUID()}-")

        val readOnly = PosixFilePermissions.fromString("r-x------")
        val writable = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(parent, readOnly)

        Sync.ensure(
            Sync.defer {
                try Files.setPosixFilePermissions(parent, writable)
                catch case _: Throwable => ()
                try Files.deleteIfExists(parent)
                catch case _: Throwable => ()
            }
        ) {
            val kyoParent = Path(parent.toString)
            Abort.run[BrowserSetupException] {
                BrowserLauncher.createTempDir(kyoParent)
            }.map {
                case Result.Failure(_: BrowserSetupFailedException) => succeed
                case Result.Success(p) =>
                    fail(s"Expected BrowserSetupFailedException but createTempDir returned $p")
                case Result.Panic(ex) =>
                    fail(s"Expected Failure, got Panic: ${ex.getMessage}")
            }
        }
    }

end BrowserLauncherJvmTest
