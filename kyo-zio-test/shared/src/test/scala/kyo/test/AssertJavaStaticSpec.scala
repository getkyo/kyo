package kyo.test

import zio.test.*

object AssertJavaStaticSpec extends KyoSpecDefault:

    def spec = suite("AssertJavaStaticSpec")(
        test("assertTrue with java.lang.Math static method"):
            assertTrue(java.lang.Math.abs(-1) == 1)
        ,
        test("assertTrue with java.lang.Integer static method"):
            assertTrue(java.lang.Integer.parseInt("42") == 42)
        ,
        test("assertTrue with Java static nested in boolean operators"):
            assertTrue(java.lang.Math.abs(-1) == 1 && java.lang.Math.max(1, 2) == 2)
    )
end AssertJavaStaticSpec
