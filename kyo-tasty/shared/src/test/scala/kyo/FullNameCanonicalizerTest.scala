package kyo

import kyo.internal.tasty.symbol.FullNameCanonicalizer

/** Tests for FullNameCanonicalizer binary-name canonicalization.
  *
  * Covers toFullName with an inner-class table entry that maps '$' nesting to dotted form.
  */
class FullNameCanonicalizerTest extends kyo.test.Test[Any]:

    "FullNameCanonicalizer.toFullName resolves named inner class to dotted form" in {
        val innerClassTable: Map[String, (String, String)] = Map(
            "com/example/Foo$Inner" -> ("com/example/Foo", "Inner")
        )
        val result = FullNameCanonicalizer.toFullName("com/example/Foo$Inner", innerClassTable)
        assert(
            result == "com.example.Foo.Inner",
            s"Expected 'com.example.Foo.Inner' but got '$result'"
        )
    }

    "FullNameCanonicalizer.toFullName top-level class returns slash-replaced dotted form" in {
        val result = FullNameCanonicalizer.toFullName("com/example/Foo", Map.empty)
        assert(
            result == "com.example.Foo",
            s"Expected 'com.example.Foo' but got '$result'"
        )
    }

end FullNameCanonicalizerTest
