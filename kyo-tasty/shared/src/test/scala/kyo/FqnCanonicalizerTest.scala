package kyo

import kyo.internal.tasty.symbol.FqnCanonicalizer

/** Tests for FqnCanonicalizer binary-name canonicalization.
  *
  * Phase 21g (T2). Covers toFullName with an inner-class table entry that maps '$' nesting to dotted form.
  */
class FqnCanonicalizerTest extends Test:

    // Test 3 (T2, FqnCanonicalizer): named inner class resolves to dotted form.
    // Given: binaryName = "com/example/Foo$Inner"; innerClassTable with entry
    //        "com/example/Foo$Inner" -> ("com/example/Foo", "Inner").
    // When: FqnCanonicalizer.toFullName(binaryName, innerClassTable).
    // Then: returns "com.example.Foo.Inner".
    // Pins: T2.
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

    // Additional test: top-level class with empty table returns slash-replaced form.
    // Given: binaryName = "com/example/Foo"; empty innerClassTable.
    // When: FqnCanonicalizer.toFullName(binaryName, Map.empty).
    // Then: returns "com.example.Foo".
    "FqnCanonicalizer.toFullName top-level class returns slash-replaced dotted form" in {
        val result = FqnCanonicalizer.toFullName("com/example/Foo", Map.empty)
        assert(
            result == "com.example.Foo",
            s"Expected 'com.example.Foo' but got '$result'"
        )
    }

end FqnCanonicalizerTest
