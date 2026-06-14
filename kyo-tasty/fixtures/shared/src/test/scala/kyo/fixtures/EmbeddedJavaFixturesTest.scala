package kyo.fixtures

/** EmbeddedJavaFixtures byte integrity tests: the embedded JavaSimpleFixture classfile bytes are non-empty and carry the classfile magic
  * 0xCAFEBABE. Runs on JVM, JS, and Native.
  */
class EmbeddedJavaFixturesTest extends kyo.test.Test[Any]:

    "javaSimpleFixtureClassfile is non-empty" in {
        val bytes = EmbeddedJavaFixtures.javaSimpleFixtureClassfile
        assert(bytes.length >= 100, s"classfile bytes must be non-empty (>= 100 bytes); got ${bytes.length}")
    }

    "javaSimpleFixtureClassfile carries classfile magic 0xCAFEBABE" in {
        val bytes = EmbeddedJavaFixtures.javaSimpleFixtureClassfile
        assert(bytes.length >= 4, "classfile must be at least 4 bytes long")
        assert(bytes(0) == 0xca.toByte, s"byte 0 must be 0xCA; got ${bytes(0)}")
        assert(bytes(1) == 0xfe.toByte, s"byte 1 must be 0xFE; got ${bytes(1)}")
        assert(bytes(2) == 0xba.toByte, s"byte 2 must be 0xBA; got ${bytes(2)}")
        assert(bytes(3) == 0xbe.toByte, s"byte 3 must be 0xBE; got ${bytes(3)}")
    }

end EmbeddedJavaFixturesTest
