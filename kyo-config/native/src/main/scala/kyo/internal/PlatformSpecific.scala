package kyo.internal

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.resolvedAtLinktime

/** The Scala Native facts behind [[Platform]].
  *
  * The operating system and architecture flags are resolved at link time: a Native binary is linked for one target, so a plain `if` on
  * `Platform.isWindows` removes the untaken branch, and the native symbols it references, from the binary. Each flag is composed directly
  * from `LinktimeInfo`, the form the linker resolves.
  *
  * The compiler rejects a link-time flag combined with a runtime condition in one `&&` or `||` ("Mixing link-time and runtime conditions is
  * not allowed"), so a runtime test goes inside the branch: `if Platform.isWindows then segment.length == 2 else false`.
  */
abstract class PlatformSpecific extends PlatformStatic {
    final val isJVM           = false
    final val isJS            = false
    final val isNative        = true
    final val isWasm          = false
    final val canSplitModules = false
    final val isNodeLike      = false
    final val isBrowser       = false
    final val isDebugEnabled  = false

    val host: Platform.Host = Platform.Host.Native

    @resolvedAtLinktime def isWindows: Boolean = LinktimeInfo.isWindows
    @resolvedAtLinktime def isMac: Boolean     = LinktimeInfo.isMac
    @resolvedAtLinktime def isLinux: Boolean   = LinktimeInfo.isLinux
    @resolvedAtLinktime def isBsd: Boolean     = LinktimeInfo.isFreeBSD || LinktimeInfo.isOpenBSD || LinktimeInfo.isNetBSD
    @resolvedAtLinktime def isMacOrBsd: Boolean =
        LinktimeInfo.isMac || LinktimeInfo.isFreeBSD || LinktimeInfo.isOpenBSD || LinktimeInfo.isNetBSD
    @resolvedAtLinktime def isX86_64: Boolean  = LinktimeInfo.target.arch == "x86_64"
    @resolvedAtLinktime def isAarch64: Boolean = LinktimeInfo.target.arch == "aarch64" || LinktimeInfo.target.arch == "arm64"

    def os: Platform.Os =
        if (isWindows) Platform.Os.Windows
        else if (isMac) Platform.Os.MacOS
        else if (isLinux) Platform.Os.Linux
        else if (isBsd) Platform.Os.BSD
        else Platform.Os.Unknown

    def arch: Platform.Arch = Platform.Arch.fromToken(LinktimeInfo.target.arch)

    /** Terminates the process with `code`. */
    def exit(code: Int): Unit = java.lang.System.exit(code)
}
