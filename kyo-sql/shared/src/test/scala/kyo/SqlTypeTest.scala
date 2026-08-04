package kyo

import kyo.Maybe.Absent

class SqlTypeTest extends Test:

    "parameterlessCasesAreSingletons" in {
        assert(SqlType.Type.Text == SqlType.Type.Text)
        assert(SqlType.Type.Timestamp != SqlType.Type.DateTime)
        assert(SqlType.Type.Int != SqlType.Type.BigInt)
    }

    "numericDefaultsToNoPrecisionAndNoScale" in {
        val n = SqlType.Type.Numeric()
        n match
            case SqlType.Type.Numeric(precision, scale) =>
                assert(precision == Absent)
                assert(scale == Absent)
            case other => fail(s"expected SqlType.Type.Numeric, got: $other")
        end match
        assert(n == SqlType.Type.Numeric(Absent, Absent))
    }

    "numericCarriesPrecisionAndScale" in {
        val n = SqlType.Type.Numeric(Maybe(10), Maybe(2))
        n match
            case SqlType.Type.Numeric(precision, scale) =>
                assert(precision == Maybe(10))
                assert(scale == Maybe(2))
            case other => fail(s"expected SqlType.Type.Numeric, got: $other")
        end match
        assert(n == SqlType.Type.Numeric(Maybe(10), Maybe(2)))
        assert(n != SqlType.Type.Numeric(Maybe(10), Maybe(4)))
        assert(n != SqlType.Type.Numeric())
    }

    "arrayNestsItsElementType" in {
        val a = SqlType.Type.Array(SqlType.Type.Text)
        a match
            case SqlType.Type.Array(element) => assert(element == SqlType.Type.Text)
            case other                       => fail(s"expected SqlType.Type.Array, got: $other")
        assert(a == SqlType.Type.Array(SqlType.Type.Text))
        assert(a != SqlType.Type.Array(SqlType.Type.BigInt))
        SqlType.Type.Array(a) match
            case SqlType.Type.Array(element) => assert(element == SqlType.Type.Array(SqlType.Type.Text))
            case other                       => fail(s"expected a nested SqlType.Type.Array, got: $other")
    }

    "extensionCarriesTheBackendTypeName" in {
        val e = SqlType.Type.Extension("hstore")
        e match
            case SqlType.Type.Extension(typeName) => assert(typeName == "hstore")
            case other                            => fail(s"expected SqlType.Type.Extension, got: $other")
        assert(e == SqlType.Type.Extension("hstore"))
        assert(e != SqlType.Type.Extension("citext"))
    }

    "temporalCasesAreFourDistinctValues" in {
        assert(SqlType.Type.DateTime != SqlType.Type.Timestamp)
        assert(SqlType.Type.Timestamp != SqlType.Type.CalendarInterval)
        assert(SqlType.Type.DateTime != SqlType.Type.CalendarInterval)
        assert(SqlType.Type.Date != SqlType.Type.DateTime)
    }

    // The built-in cast targets: `.cast[A]` reads a `SqlType[A]`, so every type the module can cast to has one,
    // and a type with no portable SQL type has none rather than a stand-in.
    "the built-in cast targets name each type's portable SQL type" in {
        assert(summon[SqlType[Int]].columnType == SqlType.Type.Int)
        assert(summon[SqlType[String]].columnType == SqlType.Type.Text)
        assert(summon[SqlType[BigDecimal]].columnType == SqlType.Type.Numeric())
        assert(summon[SqlType[kyo.Instant]].columnType == SqlType.Type.Timestamp)
        assert(summon[SqlType[java.util.UUID]].columnType == SqlType.Type.Uuid)
        assert(summon[SqlType[Chunk[Int]]].columnType == SqlType.Type.Array(SqlType.Type.Int))
    }

    // Byte has no one-byte integer type on Postgres, so the narrowest cast target both flavors accept is the
    // two-byte one. A TINYINT here would render SQL Postgres rejects outright.
    "Byte casts to SmallInt, the narrowest target both flavors accept" in {
        assert(summon[SqlType[Byte]].columnType == SqlType.Type.SmallInt)
    }

    // A fixed count of seconds is a type neither backend casts to, so the cast is a compile error rather than a
    // render-time failure naming a type the caller never asked for.
    "a type with no portable SQL type has no cast target at all" in {
        typeCheckFailure("summon[SqlType[java.time.Duration]]")
    }

    "SqlType.of declares a cast target for a backend-owned type" in {
        assert(SqlType.of[Span[Byte]](SqlType.Type.Extension("geometry")).columnType == SqlType.Type.Extension("geometry"))
    }

end SqlTypeTest
