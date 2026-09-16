package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

class PlatformJvmTest extends AnyFreeSpec {

    "the JVM host" - {
        "is the JVM" in {
            assert(Platform.host eq Platform.Host.Jvm)
            assert(!Platform.isNodeLike && !Platform.isBrowser && !Platform.isWasm && !Platform.canSplitModules)
        }

        "classifies os.name and os.arch" in {
            assert(Platform.os eq Platform.Os.fromJavaName(System.getProperty("os.name", "")))
            assert(Platform.arch eq Platform.Arch.fromToken(System.getProperty("os.arch", "")))
        }

        "derives the same separators the JDK reports" in {
            assert(Platform.fileSeparator == java.io.File.separator)
            assert(Platform.pathSeparator == java.io.File.pathSeparator)
            assert(Platform.lineSeparator == System.lineSeparator())
        }
    }
}
