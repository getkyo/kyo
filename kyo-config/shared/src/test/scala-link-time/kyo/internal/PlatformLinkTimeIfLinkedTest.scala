package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

/** Pins [[Platform.linkTimeIf]] on a condition the linker resolves on Scala.js, `isWasm` or `canSplitModules`, which the JVM and Scala Native
  * know when compiling and Scala 2.13 evaluates at run time.
  *
  * The build compiles this suite on every platform and Scala line except one: Scala.js on the Scala 3.3 LTS line, whose backend does not
  * resolve the `LinkingInfo.linkTimeIf` such a condition expands to, so a test compiled there would not link.
  */
class PlatformLinkTimeIfLinkedTest extends AnyFreeSpec {

    "resolves a condition on isWasm to the branch of the current link" in {
        val linked: String = Platform.linkTimeIf(!Platform.isNative && Platform.isWasm)("wasm")("not wasm")
        assert(linked == (if (Platform.isWasm) "wasm" else "not wasm"))
    }

    "resolves a condition on canSplitModules to the branch of the current link" in {
        val linked: String = Platform.linkTimeIf(!Platform.isNative && Platform.canSplitModules)("splits")("one module")
        assert(linked == (if (Platform.canSplitModules) "splits" else "one module"))
    }

    "resolves a condition on isWasm alone, typed as its branches" in {
        val linked: Int = Platform.linkTimeIf(Platform.isWasm)(1)(2)
        assert(linked == (if (Platform.isWasm) 1 else 2))
    }
}
