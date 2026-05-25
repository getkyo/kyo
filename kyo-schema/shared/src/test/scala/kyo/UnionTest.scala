package kyo

// Top-level test fixtures (must live at package level so derivation macros can see them).
case class Foo(x: Int, y: String) derives Schema, CanEqual
case class Bar(z: Boolean) derives Schema, CanEqual
case class Holder(v: String | Int) derives Schema, CanEqual

class UnionTest extends Test:

    // Strict-equality safe comparison: routes through java.lang.Object.equals to bypass CanEqual.
    private def sameRef(actual: Any, expected: Any): Boolean =
        actual.asInstanceOf[AnyRef].equals(expected.asInstanceOf[AnyRef])

    "Schema[String | Int]" - {

        "round-trip String leg" in {
            val schema              = Schema.derived[String | Int]
            val value: String | Int = "hello"
            val json                = schema.encodeString[Json](value)
            val decoded             = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, "hello"))
        }

        "round-trip Int leg" in {
            val schema              = Schema.derived[String | Int]
            val value: String | Int = 42
            val json                = schema.encodeString[Json](value)
            val decoded             = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, 42))
        }
    }

    "Schema[String | Int | Boolean]" - {

        "round-trip Boolean leg (3-way union)" in {
            val schema                        = Schema.derived[String | Int | Boolean]
            val value: String | Int | Boolean = true
            val json                          = schema.encodeString[Json](value)
            val decoded                       = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, true))
        }
    }

    "Schema[Foo | Bar]" - {

        "round-trip Foo leg" in {
            val schema           = Schema.derived[Foo | Bar]
            val value: Foo | Bar = Foo(7, "abc")
            val json             = schema.encodeString[Json](value)
            val decoded          = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, Foo(7, "abc")))
        }

        "round-trip Bar leg" in {
            val schema           = Schema.derived[Foo | Bar]
            val value: Foo | Bar = Bar(true)
            val json             = schema.encodeString[Json](value)
            val decoded          = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, Bar(true)))
        }

        "round-trip with discriminator(\"kind\") - Foo" in {
            val schema           = Schema.derived[Foo | Bar].discriminator("kind")
            val value: Foo | Bar = Foo(7, "abc")
            val json             = schema.encodeString[Json](value)
            assert(json.contains("\"kind\""))
            assert(json.contains("\"Foo\""))
            // Flat discriminator format (NOT wrapper):
            assert(!json.contains("\"Foo\":{"))
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, Foo(7, "abc")))
        }
    }

    "degenerate unions" - {

        "Schema.derived[Foo | Foo] rejects degenerate / duplicate legs at compile time" in {
            // Writing `T | T` is almost certainly user error (the type already equals T). The macro detects
            // the duplicate legs via `UnionMacro.degenerate` and aborts with a clear message rather than
            // silently delegating to `Schema[T]`. This locks the contract that degenerate unions are rejected.
            val errs = scala.compiletime.testing.typeCheckErrors("kyo.Schema.derived[kyo.Foo | kyo.Foo]")
            assert(errs.nonEmpty, "Schema.derived[Foo | Foo] must fail to compile, not silently delegate to Schema[Foo].")
            val combined = errs.map(_.message).mkString("\n")
            assert(
                combined.contains("degenerate") || combined.contains("duplicate"),
                s"Compile error must name 'degenerate' / 'duplicate'. Got: $combined"
            )
        }

        "Schema.derived[Foo | Bar | Foo] rejects the duplicate leg even in a longer union" in {
            // Multi-leg degenerate unions: the OrType is `Foo | Bar | Foo`, with structurally-equal legs Foo
            // appearing twice. Same rejection path as the two-leg case.
            val errs = scala.compiletime.testing.typeCheckErrors("kyo.Schema.derived[kyo.Foo | kyo.Bar | kyo.Foo]")
            assert(errs.nonEmpty, "Schema.derived[Foo | Bar | Foo] must fail to compile.")
            val combined = errs.map(_.message).mkString("\n")
            assert(
                combined.contains("degenerate") || combined.contains("duplicate"),
                s"Compile error must name 'degenerate' / 'duplicate'. Got: $combined"
            )
        }
    }

    "Schema.derived[String | Nothing]" - {

        "reduces to Schema[String] and round-trips a String value" in {
            // Scala 3 typically reduces `String | Nothing` to `String` at the type level before the macro
            // sees it; on the off-chance it doesn't, the single-leg-after-Nothing-strip path in
            // UnionMacro.derive delegates to Schema[String]. Either way: round-trip must work.
            val schema  = Schema.derived[String | Nothing]
            val json    = schema.encodeString[Json]("hello")
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(sameRef(decoded, "hello"))
        }
    }

    "Schema.derived[(String | Int) | Boolean]" - {

        "flattens to 3-way union and round-trips each leaf" in {
            val schema = Schema.derived[(String | Int) | Boolean]
            // String leg
            val vS: (String | Int) | Boolean = "hi"
            val dS                           = schema.decodeString[Json](schema.encodeString[Json](vS)).getOrThrow
            assert(sameRef(dS, "hi"))
            // Int leg
            val vI: (String | Int) | Boolean = 99
            val dI                           = schema.decodeString[Json](schema.encodeString[Json](vI)).getOrThrow
            assert(sameRef(dI, 99))
            // Boolean leg
            val vB: (String | Int) | Boolean = false
            val dB                           = schema.decodeString[Json](schema.encodeString[Json](vB)).getOrThrow
            assert(sameRef(dB, false))
        }
    }

    "case class field of type String | Int" - {

        "round-trip String variant" in {
            val schema  = Schema.derived[Holder]
            val value   = Holder("hi")
            val json    = schema.encodeString[Json](value)
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(decoded == Holder("hi"))
        }

        "round-trip Int variant" in {
            val schema  = Schema.derived[Holder]
            val value   = Holder(42)
            val json    = schema.encodeString[Json](value)
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(decoded == Holder(42))
        }
    }

    "decode failure" - {

        "decoding 'false' as Schema[String | Int] yields a Failure naming both branches" in {
            val schema = Schema.derived[String | Int]
            val result = schema.decodeString[Json]("false")
            assert(result.isFailure, s"Expected Failure, got $result")
            result match
                case Result.Failure(e) =>
                    val msg = e.getMessage
                    assert(msg.contains("String"), s"Expected message to name 'String'. Got: $msg")
                    assert(msg.contains("Int"), s"Expected message to name 'Int'. Got: $msg")
                case other =>
                    fail(s"Expected Result.Failure, got: $other")
            end match
        }
    }

    "order-sensitivity" - {

        "round-trip 123L as Schema[Int | Long] preserves Long" in {
            val schema            = Schema.derived[Int | Long]
            val value: Int | Long = 123L
            val json              = schema.encodeString[Json](value)
            val decoded           = schema.decodeString[Json](json).getOrThrow
            // Runtime class test: decoded value must be a java.lang.Long, not narrowed to Integer.
            val cls = decoded.asInstanceOf[AnyRef].getClass.getName
            assert(cls == "java.lang.Long", s"Expected java.lang.Long, got $cls")
            assert(sameRef(decoded, 123L))
        }
    }

end UnionTest
