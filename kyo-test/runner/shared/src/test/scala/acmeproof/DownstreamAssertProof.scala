package acmeproof

import kyo.test.Test

/** Proof that the assert DSL compiles and runs correctly from a package outside the kyo hierarchy.
  *
  * `recordEvaluated` on `AssertScope` is `private[kyo]` in source but compiles to a public JVM method.
  * The assert/intercept/typeCheckFailure macros splice calls to it at the call site, which here is the
  * `acmeproof` package, not any `kyo.*` package. If qualified-private visibility were re-checked at the
  * macro splice site, this file would fail to compile. Successful compilation (and passing leaves) proves
  * that `@publicInBinary` is not required: plain `private[kyo]` is sufficient.
  *
  * This file is a permanent regression guard: if a refactor accidentally makes `recordEvaluated`
  * inaccessible to macro-generated code at non-kyo call sites, this file will fail to compile.
  */
class DownstreamAssertProof extends Test[Any]:

    "downstream assert compiles and passes" in {
        assert(1 + 1 == 2)
    }

    "downstream succeed opt-out compiles" in {
        succeed
    }

    "downstream intercept compiles and passes" in {
        val ex = intercept[IllegalArgumentException] {
            throw new IllegalArgumentException("downstream boom")
        }
        assert(ex.getMessage == "downstream boom")
    }

    "downstream typeCheckFailure compiles and passes" in {
        typeCheckFailure("val x: Int = \"s\"")("""Required: Int""")
    }

end DownstreamAssertProof
