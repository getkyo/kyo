package kyo.internal

// Scratch reproduction for the Platform consolidation; folded into kyo-config's Native PlatformTest once Platform moves.
// GetCurrentProcess exists only on Windows and getppid only on POSIX, so this suite links on a given OS only if the
// branch for the other OS is removed at link time.
class PlatformLinkTimeReproTest extends kyo.test.Test[Any]:

    "the branch for another OS is not linked" in {
        val reached =
            if Platform.isWindows then scala.scalanative.windows.ProcessThreadsApi.GetCurrentProcess() != null
            else scala.scalanative.posix.unistd.getppid() > 0
        assert(reached)
    }

end PlatformLinkTimeReproTest
