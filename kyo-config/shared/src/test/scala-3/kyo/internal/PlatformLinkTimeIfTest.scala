package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

/** Pins when [[Platform.linkTimeIf]] resolves its condition.
  *
  * A condition built from `isJVM`, `isJS`, and `isNative` is a compile-time constant on every platform, and `linkTimeIf` resolves it when
  * compiling, so the untaken branch is never emitted. Only that reduction narrows the result to the taken branch's type: a value typed as the
  * taken branch compiles only when the branch was chosen by the compiler, which makes each check a compile-time proof as well as an assertion.
  */
class PlatformLinkTimeIfTest extends AnyFreeSpec:

    "resolves a constant condition when compiling" in {
        val taken: String = Platform.linkTimeIf[Any](Platform.isJVM || Platform.isJS || Platform.isNative)("taken")(0)
        val other: String = Platform.linkTimeIf[Any](Platform.isJVM && Platform.isJS)(0)("other")
        assert(taken == "taken")
        assert(other == "other")
    }

    "resolves a condition on isWasm to the branch of the current link" in {
        val linked: String = Platform.linkTimeIf(!Platform.isNative && Platform.isWasm)("wasm")("not wasm")
        assert(linked == (if Platform.isWasm then "wasm" else "not wasm"))
    }

    "resolves a condition on canSplitModules to the branch of the current link" in {
        val linked: String = Platform.linkTimeIf(!Platform.isNative && Platform.canSplitModules)("splits")("one module")
        assert(linked == (if Platform.canSplitModules then "splits" else "one module"))
    }
end PlatformLinkTimeIfTest
