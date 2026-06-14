package kyo.ffi

import kyo.ffi.internal.FfiUnsafe

/** Unit tests for [[kyo.ffi.internal.FfiUnsafe.expect]], the type-checked replacement for liberal `asInstanceOf` at cast sites. */
class FfiUnsafeTest extends Test:

    "FfiUnsafe.expect" - {

        "returns the value when the runtime class matches" in {
            val s: AnyRef = "hello"
            val out       = FfiUnsafe.expect[String](s, classOf[String], "String", "kyo.example.B", "m")
            assert(out == "hello")
        }

        "throws FfiInternalError with a message naming binding + method + expected + actual on mismatch" in {
            // Use a concrete Scala class for the actual argument so the reported class name is stable across JVM, Native, and Scala.js
            // (Scala.js boxes primitive integers to narrower Java types like `java.lang.Byte`, use a String subject instead).
            class Marker
            val other: AnyRef = new Marker
            val thrown = intercept[FfiInternalError](
                FfiUnsafe.expect[java.lang.String](
                    other,
                    classOf[String],
                    "String on test platform",
                    "kyo.example.Buffer",
                    "wrapBorrowed"
                )
            )
            assert(thrown.getMessage.contains("String on test platform"))
            assert(thrown.getMessage.contains("kyo.example.Buffer.wrapBorrowed"))
            // The actual class name should be surfaced; match on the class simple name to stay runtime-agnostic.
            assert(thrown.getMessage.contains("Marker"))
        }

        "throws FfiInternalError on null input (names 'null' as the actual class)" in {
            val thrown = intercept[FfiInternalError](
                FfiUnsafe.expect[java.lang.String](
                    null.asInstanceOf[AnyRef],
                    classOf[String],
                    "String",
                    "kyo.example.B",
                    "m"
                )
            )
            assert(thrown.getMessage.contains("kyo.example.B.m"))
            assert(thrown.getMessage.contains("null"))
        }
    }

end FfiUnsafeTest
