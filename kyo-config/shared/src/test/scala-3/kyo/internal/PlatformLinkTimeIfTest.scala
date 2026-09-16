package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

/** Pins when [[Platform.linkTimeIf]] resolves its condition.
  *
  * A condition built from `isJVM`, `isJS`, and `isNative` is a compile-time constant on every platform, and `linkTimeIf` resolves it when
  * compiling, so the untaken branch is never emitted. Only that reduction narrows the result to the taken branch's type: a value typed as the
  * taken branch compiles only when the branch was chosen by the compiler, which makes each check a compile-time proof as well as an assertion.
  *
  * A condition the linker resolves on Scala.js is pinned by `PlatformLinkTimeIfLinkedTest`, which one build cannot link.
  */
class PlatformLinkTimeIfTest extends AnyFreeSpec:

    "resolves a constant condition when compiling" in {
        val taken: String = Platform.linkTimeIf[Any](Platform.isJVM || Platform.isJS || Platform.isNative)("taken")(0)
        val other: String = Platform.linkTimeIf[Any](Platform.isJVM && Platform.isJS)(0)("other")
        assert(taken == "taken")
        assert(other == "other")
    }
end PlatformLinkTimeIfTest
