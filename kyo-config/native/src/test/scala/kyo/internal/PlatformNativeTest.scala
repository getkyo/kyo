package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import scala.scalanative.meta.LinktimeInfo

class PlatformNativeTest extends AnyFreeSpec {

    "the Scala Native host" - {
        "is Native" in {
            assert(Platform.host eq Platform.Host.Native)
            assert(!Platform.isNodeLike && !Platform.isBrowser && !Platform.isWasm && !Platform.canSplitModules && !Platform.isDebugEnabled)
        }

        "classifies the link target" in {
            assert(Platform.isWindows == LinktimeInfo.isWindows)
            assert(Platform.isMac == LinktimeInfo.isMac)
            assert(Platform.isLinux == LinktimeInfo.isLinux)
            assert(Platform.arch eq Platform.Arch.fromToken(LinktimeInfo.target.arch))
        }

        "leaves the branch for another operating system out of the binary" in {
            // GetCurrentProcess exists only on Windows and getppid only on POSIX. This suite links on a given OS only because the flags
            // are resolved at link time: a value-typed flag keeps both calls, and the link fails on the missing symbol.
            val reached =
                if (Platform.isWindows) scala.scalanative.windows.ProcessThreadsApi.GetCurrentProcess() != null
                else scala.scalanative.posix.unistd.getppid() > 0
            assert(reached)
        }
    }
}
