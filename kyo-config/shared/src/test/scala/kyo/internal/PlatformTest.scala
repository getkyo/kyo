package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

class PlatformTest extends AnyFreeSpec {

    import Platform.Arch
    import Platform.Os

    "Os.fromJavaName" - {
        "classifies every os.name family" in {
            assert(Os.fromJavaName("Linux") eq Os.Linux)
            assert(Os.fromJavaName("Mac OS X") eq Os.MacOS)
            assert(Os.fromJavaName("Darwin") eq Os.MacOS)
            assert(Os.fromJavaName("Windows 11") eq Os.Windows)
            assert(Os.fromJavaName("Windows Server 2022") eq Os.Windows)
            assert(Os.fromJavaName("FreeBSD") eq Os.BSD)
            assert(Os.fromJavaName("OpenBSD") eq Os.BSD)
            assert(Os.fromJavaName("NetBSD") eq Os.BSD)
            assert(Os.fromJavaName("SunOS") eq Os.Solaris)
            assert(Os.fromJavaName("OS/400") eq Os.IBMI)
            assert(Os.fromJavaName("AIX") eq Os.AIX)
            assert(Os.fromJavaName("DragonFly") eq Os.Unknown)
            assert(Os.fromJavaName("") eq Os.Unknown)
        }

        "classifies Darwin as macOS, never as a BSD or as Windows" in {
            // "darwin" contains "win": a substring test for Windows that ran before the macOS test would misclassify it.
            assert(Os.fromJavaName("darwin") eq Os.MacOS)
        }
    }

    "Os.fromNodePlatform" - {
        "classifies every process.platform token" in {
            assert(Os.fromNodePlatform("linux") eq Os.Linux)
            assert(Os.fromNodePlatform("darwin") eq Os.MacOS)
            assert(Os.fromNodePlatform("win32") eq Os.Windows)
            assert(Os.fromNodePlatform("freebsd") eq Os.BSD)
            assert(Os.fromNodePlatform("openbsd") eq Os.BSD)
            assert(Os.fromNodePlatform("netbsd") eq Os.BSD)
            assert(Os.fromNodePlatform("sunos") eq Os.Solaris)
            assert(Os.fromNodePlatform("os400") eq Os.IBMI)
            assert(Os.fromNodePlatform("aix") eq Os.AIX)
            assert(Os.fromNodePlatform("android") eq Os.Unknown)
            assert(Os.fromNodePlatform("") eq Os.Unknown)
        }
    }

    "Arch.fromToken" - {
        "classifies the Java, Node, and Scala Native architecture tokens" in {
            assert(Arch.fromToken("amd64") eq Arch.X86_64)
            assert(Arch.fromToken("x86_64") eq Arch.X86_64)
            assert(Arch.fromToken("x64") eq Arch.X86_64)
            assert(Arch.fromToken("aarch64") eq Arch.Aarch64)
            assert(Arch.fromToken("arm64") eq Arch.Aarch64)
            assert(Arch.fromToken("x86") eq Arch.X86)
            assert(Arch.fromToken("i386") eq Arch.X86)
            assert(Arch.fromToken("i686") eq Arch.X86)
            assert(Arch.fromToken("ia32") eq Arch.X86)
            assert(Arch.fromToken("arm") eq Arch.Arm)
            assert(Arch.fromToken("armv7l") eq Arch.Arm)
            assert(Arch.fromToken("riscv64") eq Arch.Unknown)
            assert(Arch.fromToken("") eq Arch.Unknown)
        }
    }

    "separators" - {
        "follow the Windows convention only on Windows" in {
            assert(Platform.fileSeparatorFor(windows = true) == "\\")
            assert(Platform.pathSeparatorFor(windows = true) == ";")
            assert(Platform.lineSeparatorFor(windows = true) == "\r\n")
            assert(Platform.fileSeparatorFor(windows = false) == "/")
            assert(Platform.pathSeparatorFor(windows = false) == ":")
            assert(Platform.lineSeparatorFor(windows = false) == "\n")
        }

        "match the host's operating system" in {
            assert(Platform.fileSeparator == Platform.fileSeparatorFor(Platform.isWindows))
            assert(Platform.pathSeparator == Platform.pathSeparatorFor(Platform.isWindows))
            assert(Platform.lineSeparator == Platform.lineSeparatorFor(Platform.isWindows))
        }
    }

    "the running platform" - {
        "is exactly one of JVM, JS, and Native" in {
            assert(List(Platform.isJVM, Platform.isJS, Platform.isNative).count(identity) == 1)
        }

        "reports WebAssembly only on JS" in {
            assert(!Platform.isWasm || Platform.isJS)
        }

        "derives the operating system flags from the same classification as os" in {
            assert(Platform.isWindows == (Platform.os eq Os.Windows))
            assert(Platform.isMac == (Platform.os eq Os.MacOS))
            assert(Platform.isLinux == (Platform.os eq Os.Linux))
            assert(Platform.isBsd == (Platform.os eq Os.BSD))
            assert(Platform.isMacOrBsd == (Platform.isMac || Platform.isBsd))
            assert(Platform.isX86_64 == (Platform.arch eq Arch.X86_64))
            assert(Platform.isAarch64 == (Platform.arch eq Arch.Aarch64))
        }

        "is one operating system family and one architecture on every host that exposes them" in {
            assume(!Platform.isBrowser, "a browser exposes no operating system or architecture")
            assert(List(Platform.isMacOrBsd, Platform.isLinux, Platform.isWindows).count(identity) == 1)
            assert(!(Platform.arch eq Arch.Unknown), s"architecture not classified on ${Platform.host}")
        }

        "keeps the stack depth bound" in {
            assert(Platform.maxStackDepth == 256)
        }
    }
}
