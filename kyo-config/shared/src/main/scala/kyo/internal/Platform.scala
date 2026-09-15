package kyo.internal

/** What kyo is running on: the platform, the host, the operating system and architecture, and the conventions that follow from them.
  *
  * Every member has the same name and type on the JVM, Scala Native, and Scala.js, so shared code can use any member on any platform. What
  * differs per platform is only when a member's value becomes known:
  *
  *   - Compile time: a scalac constant. `isJVM`, `isJS`, `isNative`, and `maxStackDepth` everywhere, plus the answers that are fixed on a
  *     platform (`isWasm`, `isNodeLike`, and `isBrowser` are `false` on the JVM and Native). `inline if` on one of these emits only the
  *     taken branch.
  *   - Link time: fixed when the application is linked. `isWasm` on Scala.js, where one published artifact is linked as JS or as WasmGC,
  *     and the operating system and architecture flags on Scala Native. The untaken branch is compiled into the published artifact but left
  *     out of the application's output: through [[linkTimeIf]] on Scala.js, and through a plain `if` on Scala Native.
  *   - Run time: read when the program runs. The host on Scala.js, and the operating system and architecture on the JVM and Scala.js, where
  *     one jar or one bundle runs on any machine.
  *
  * To keep code for one platform out of another, branch with `inline if` on `isJVM`, `isJS`, or `isNative`. To keep code out of a JS or a
  * WasmGC link, branch with `Platform.linkTimeIf(Platform.isWasm)(...)(...)`: `inline if Platform.isWasm` compiles on the JVM and Native,
  * where `isWasm` is a constant, but not on Scala.js. `linkTimeIf` is available on Scala 3; the Scala 2.13 builds declare it as a plain
  * run-time `if`.
  *
  * The capabilities that exist only on Scala.js (reading a global, loading a Node built-in) live in `PlatformJs`, which keeps this interface
  * identical everywhere.
  */
object Platform extends PlatformSpecific {

    /** Frames a synchronous chain runs before the kernel suspends through a safepoint to stay stack-safe. */
    final val maxStackDepth = 256

    /** The host's file name separator: `\` on Windows, `/` everywhere else, including hosts that expose no operating system. */
    val fileSeparator: String = fileSeparatorFor(isWindows)

    /** The host's path list separator: `;` on Windows, `:` everywhere else, including hosts that expose no operating system. */
    val pathSeparator: String = pathSeparatorFor(isWindows)

    /** The host's line separator: `\r\n` on Windows, `\n` everywhere else, including hosts that expose no operating system. */
    val lineSeparator: String = lineSeparatorFor(isWindows)

    private[kyo] def fileSeparatorFor(windows: Boolean): String = if (windows) "\\" else "/"
    private[kyo] def pathSeparatorFor(windows: Boolean): String = if (windows) ";" else ":"
    private[kyo] def lineSeparatorFor(windows: Boolean): String = if (windows) "\r\n" else "\n"

    /** An operating system family. The same vocabulary as the public `kyo.System.OS`. */
    sealed abstract class Os
    object Os {
        case object Linux   extends Os
        case object MacOS   extends Os
        case object Windows extends Os
        case object BSD     extends Os
        case object Solaris extends Os
        case object IBMI    extends Os
        case object AIX     extends Os
        case object Unknown extends Os

        /** Classifies a Java `os.name` ("Linux", "Mac OS X", "Windows 11", "FreeBSD", ...). Darwin is macOS, never a BSD. */
        def fromJavaName(name: String): Os = {
            val n = name.toLowerCase
            if (n.isEmpty) Unknown
            else if (n.contains("linux")) Linux
            else if (n.contains("mac") || n.contains("darwin")) MacOS
            else if (n.contains("windows")) Windows
            else if (n.contains("bsd")) BSD
            else if (n.contains("sunos") || n.contains("solaris")) Solaris
            else if (n.contains("os/400") || n.contains("os400")) IBMI
            else if (n.contains("aix")) AIX
            else Unknown
        }

        /** Classifies a Node `process.platform` token (also reported by Bun and Deno). */
        def fromNodePlatform(token: String): Os =
            token match {
                case "linux"                          => Linux
                case "darwin"                         => MacOS
                case "win32"                          => Windows
                case "freebsd" | "openbsd" | "netbsd" => BSD
                case "sunos"                          => Solaris
                case "os400"                          => IBMI
                case "aix"                            => AIX
                case _                                => Unknown
            }
    }

    /** A CPU architecture. The same vocabulary as the public `kyo.System.Arch`. */
    sealed abstract class Arch
    object Arch {
        case object X86     extends Arch
        case object X86_64  extends Arch
        case object Arm     extends Arch
        case object Aarch64 extends Arch
        case object Unknown extends Arch

        /** Classifies a Java `os.arch`, a Node `process.arch`, or a Scala Native target architecture. */
        def fromToken(token: String): Arch = {
            val t = token.toLowerCase
            if (t == "x86_64" || t == "amd64" || t == "x64") X86_64
            else if (t == "aarch64" || t == "arm64") Aarch64
            else if (t == "x86" || t == "i386" || t == "i686" || t == "ia32") X86
            else if (t.startsWith("arm")) Arm
            else Unknown
        }
    }

    /** The runtime hosting the program. `Jvm` and `Native` are fixed per platform; on Scala.js the host is detected from the global object. */
    sealed abstract class Host
    object Host {
        case object Jvm           extends Host
        case object Native        extends Host
        case object Node          extends Host
        case object Bun           extends Host
        case object Deno          extends Host
        case object BrowserMain   extends Host
        case object BrowserWorker extends Host

        /** A JS host that is none of the above: an edge runtime, an embedded engine, a WasmGC host without a Node shim. */
        case object OtherJs extends Host
    }
}
