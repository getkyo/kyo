package kyo

import kyo.internal.tasty.symbol.FqnCanonicalizer

/** Tests for FqnCanonicalizer binary-name canonicalization.
  *
  * Covers toFullName with an inner-class table entry that maps '$' nesting to dotted form.
  */
class FqnCanonicalizerTest extends kyo.test.Test[Any]:

    "FqnCanonicalizer.toFullName resolves named inner class to dotted form" in {
        val innerClassTable: Map[String, (String, String)] = Map(
            "com/example/Foo$Inner" -> ("com/example/Foo", "Inner")
        )
        val result = FqnCanonicalizer.toFullName("com/example/Foo$Inner", innerClassTable)
        assert(
            result == "com.example.Foo.Inner",
            s"Expected 'com.example.Foo.Inner' but got '$result'"
        )
    }

    "FqnCanonicalizer.toFullName top-level class returns slash-replaced dotted form" in {
        val result = FqnCanonicalizer.toFullName("com/example/Foo", Map.empty)
        assert(
            result == "com.example.Foo",
            s"Expected 'com.example.Foo' but got '$result'"
        )
    }

end FqnCanonicalizerTest
