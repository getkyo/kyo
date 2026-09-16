package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

/** Pins the shape of [[Platform]]: every member with its type, compiled on every platform. A platform that lacks a member or declares it with
  * another type fails to compile this suite, so the interface cannot silently drift between JVM, Scala Native, and Scala.js.
  */
class PlatformInterfaceTest extends AnyFreeSpec {

    "Platform declares the same members with the same types on every platform" in {
        val isJVM: Boolean          = Platform.isJVM
        val isJS: Boolean           = Platform.isJS
        val isNative: Boolean       = Platform.isNative
        val isWasm: Boolean         = Platform.isWasm
        val canSplit: Boolean       = Platform.canSplitModules
        val linked: Int             = Platform.linkTimeIf(Platform.isWasm)(1)(2)
        val maxStackDepth: Int      = Platform.maxStackDepth
        val isDebugEnabled: Boolean = Platform.isDebugEnabled
        val isNodeLike: Boolean     = Platform.isNodeLike
        val isBrowser: Boolean      = Platform.isBrowser
        val host: Platform.Host     = Platform.host
        val isWindows: Boolean      = Platform.isWindows
        val isMac: Boolean          = Platform.isMac
        val isLinux: Boolean        = Platform.isLinux
        val isBsd: Boolean          = Platform.isBsd
        val isMacOrBsd: Boolean     = Platform.isMacOrBsd
        val isX86_64: Boolean       = Platform.isX86_64
        val isAarch64: Boolean      = Platform.isAarch64
        val os: Platform.Os         = Platform.os
        val arch: Platform.Arch     = Platform.arch
        val fileSeparator: String   = Platform.fileSeparator
        val pathSeparator: String   = Platform.pathSeparator
        val lineSeparator: String   = Platform.lineSeparator
        val exit: Int => Unit       = Platform.exit
        assert(linked == (if (isWasm) 1 else 2))
        assert(List(isJVM, isJS, isNative).count(identity) == 1)
        assert(!canSplit || (isJS && !isWasm))
        assert(maxStackDepth > 0)
        assert(!(isNodeLike && isBrowser))
        assert(host != null && os != null && arch != null && exit != null)
        assert(!(isWindows && isMacOrBsd) && !(isLinux && isMacOrBsd) && (isMacOrBsd == (isMac || isBsd)))
        assert(!(isX86_64 && isAarch64))
        assert(fileSeparator.nonEmpty && pathSeparator.nonEmpty && lineSeparator.nonEmpty)
        assert(!isDebugEnabled || isJVM)
    }
}
