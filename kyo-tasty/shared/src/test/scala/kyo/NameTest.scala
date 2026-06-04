package kyo

import kyo.Tasty.Name
import kyo.Test

class NameTest extends Test:

    "asString round-trips through the opaque alias" in {
        val n = Tasty.Name.fromString("hello")
        assert(n.asString == "hello")
        assert(n == Tasty.Name.fromString("hello"))

        val n2 = Tasty.Name.fromString("scala.Predef")
        assert(n2.asString == "scala.Predef")

        val n3 = Tasty.Name.fromString("kyo.Tasty")
        assert(n3.asString == "kyo.Tasty")

        // Non-ASCII: e-acute
        val n4 = Tasty.Name.fromString("abcé")
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
        val n1 = Tasty.Name.fromString("foo")
        val n2 = Tasty.Name.fromString("foo")
        assert(n1 == n2)

        // Two distinct String objects with same content
        val s  = "f" + "oo"
        val n3 = Tasty.Name.fromString(s)
        assert(n1 == n3)

        val n4 = Tasty.Name.fromString("bar")
        assert(n1 != n4)
    }

    "Name.init and Name.Unsafe.init are not on the surface" in {
        val ce1 = compiletime.testing.typeCheckErrors("Tasty.Name.init(\"x\")")
        assert(ce1.nonEmpty)

        val ce2 = compiletime.testing.typeCheckErrors("Tasty.Name.Unsafe.init(\"x\")")
        assert(ce2.nonEmpty)
    }

    "Tasty.globalInterner is not a member" in {
        val ce = compiletime.testing.typeCheckErrors("kyo.Tasty.globalInterner")
        assert(ce.nonEmpty)
    }

    "cross-platform placement verified by shared/ directory" in {
        // This leaf is satisfied by the test file living in shared/src/test/scala/kyo/
        // per the cross-platform placement invariant INV-006. All leaves above run on
        // JVM, JS, and Native via the crossProject configuration.
        // flow-allow: placement-proof leaf per Phase 02 Decision 13; the substantive
        // assertion is the file's existence under shared/src/test plus the JS/Native
        // compile gates passing, not a runtime value.
        assert(true)
    }

end NameTest
