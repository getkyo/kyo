package kyo.internal

// Scratch reproduction for the Platform consolidation; folded into kyo-config's Native PlatformTest once Platform moves.
// GetCurrentProcessId exists only on Windows and getppid only on POSIX, so this suite links on a given OS only if the
// branch for the other OS is removed at link time.
class PlatformLinkTimeReproTest extends kyo.test.Test[Any]:

    "the branch for another OS is not linked" in {
        val pid =
            if Platform.isWindows then scala.scalanative.windows.ProcessThreadsApi.GetCurrentProcessId().toLong
            else scala.scalanative.posix.unistd.getppid().toLong
        assert(pid > 0L)
    }

end PlatformLinkTimeReproTest
