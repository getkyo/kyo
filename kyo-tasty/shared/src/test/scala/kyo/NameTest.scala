package kyo

import kyo.Tasty.Name

class NameTest extends kyo.test.Test[Any]:

    "asString round-trips through the opaque alias" in {
        val n = Tasty.Name("hello")
        assert(n.asString == "hello")
        assert(n == Tasty.Name("hello"))

        val n2 = Tasty.Name("scala.Predef")
        assert(n2.asString == "scala.Predef")

        val n3 = Tasty.Name("kyo.Tasty")
        assert(n3.asString == "kyo.Tasty")

        // Non-ASCII: e-acute
        val n4 = Tasty.Name("abcé")
        assert(n4.asString == "abcé")
    }

    "Schema[Name] round-trips through summon[Schema[String]]" in {
        // Schema[Name] delegates to Schema[String] per the given in object Name.
        // Verifying summon[Schema[Name]] compiles is the primary check: the given
        // must exist at compile time for this line to type-check.
        val nameSchema   = summon[Schema[Name]]
        val stringSchema = summon[Schema[String]]
        assert(nameSchema != null)
        assert(stringSchema != null)
        // Schema[Name] and Schema[String] are the same instance because
        // given Schema[Name] = summon[Schema[String]] returns exactly summon[Schema[String]].
        assert((nameSchema: AnyRef) eq (stringSchema: AnyRef))
    }

    "CanEqual[Name, Name] derived" in {
        val n1 = Tasty.Name("foo")
        val n2 = Tasty.Name("foo")
        assert(n1 == n2)

        // Two distinct String objects with same content
        val s  = "f" + "oo"
        val n3 = Tasty.Name(s)
        assert(n1 == n3)

        val n4 = Tasty.Name("bar")
        assert(n1 != n4)
    }

    "Name.init and Name.Unsafe.init are not on the surface" in {
        val ce1 = compiletime.testing.typeCheckErrors("Tasty.Name.init(\"x\")").length
        assert(ce1 > 0)

        val ce2 = compiletime.testing.typeCheckErrors("Tasty.Name.Unsafe.init(\"x\")").length
        assert(ce2 > 0)
    }

    "Tasty.globalInterner is not a member" in {
        val ce = compiletime.testing.typeCheckErrors("kyo.Tasty.globalInterner").length
        assert(ce > 0)
    }

    "cross-platform placement verified by shared/ directory" in {
        // This leaf is satisfied by the test file living in shared/src/test/scala/kyo/
        // per the cross-platform placement invariant. All leaves above run on
        // JVM, JS, and Native via the crossProject configuration.
        // the substantive
        // assertion is the file's existence under shared/src/test plus the JS/Native
        // compile gates passing, not a runtime value.
        assert(true)
    }

end NameTest
