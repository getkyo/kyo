package kyo

import kyo.schema.*

class SchemaTransformViaTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    import STVTypes.*

    "transformVia" - {

        "decodes through the smart constructor and encodes through the inverse" in {
            assert(Json.decode[STVPort]("80") == Result.succeed(STVPort.unsafe(80)))
            assert(Json.encode(STVPort.unsafe(80)) == "80")
        }

        "a rejection is ConstructorRejectedException naming the opaque type, from the given in its own companion" in {
            assert(Json.decode[STVPort]("-1") == Result.fail(ConstructorRejectedException(Nil, "STVPort", "port out of range: -1")))
        }

        "a rejection inside a record names the field" in {
            assert(
                Json.decode[STVServer]("""{"port":-1}""") ==
                    Result.fail(ConstructorRejectedException(List("port"), "STVPort", "port out of range: -1"))
            )
        }

        "a constructor returning Maybe rejects with the absence" in {
            val schema: Schema[STVPort] =
                summon[Schema[Int]].transformVia((i: Int) => if i > 0 then Maybe(STVPort.unsafe(i)) else Maybe.empty)(_.value)
            assert(schema.decodeString[Json]("0") ==
                Result.fail(ConstructorRejectedException(Nil, "STVPort", "the constructor returned Absent")))
        }

        "a total constructor never rejects" in {
            val schema = summon[Schema[Int]].transformVia((i: Int) => STVPort.unsafe(i))(_.value)
            assert(schema.decodeString[Json]("-1") == Result.succeed(STVPort.unsafe(-1)))
        }
    }

    "Transformer.Of reads and writes a field through a schema" - {

        "encodes and decodes" in {
            assert(Json.encode(STVRow(5)) == """{"n":"5"}""")
            assert(Json.decode[STVRow]("""{"n":"5"}""") == Result.succeed(STVRow(5)))
        }

        "a rejection is a decode failure with the field's path" in {
            assert(Json.decode[STVRow]("""{"n":"x"}""") == Result.fail(ConstructorRejectedException(List("n"), "Int", "not a number: x")))
        }

        "an absent optional field is written as the transform writes it, in its declared position" in {
            assert(Json.encode(STVOptionalRow(Absent, 1)) == """{"n":"","m":1}""")
            assert(Json.encode(STVOptionalRow(Present(5), 1)) == """{"n":"5","m":1}""")
            assert(Json.decode[STVOptionalRow]("""{"n":"","m":1}""") == Result.succeed(STVOptionalRow(Absent, 1)))
            assert(Json.decode[STVOptionalRow]("""{"n":"5","m":1}""") == Result.succeed(STVOptionalRow(Present(5), 1)))
        }

        "an absent optional field whose transform writes it as null is left out, as an absent optional is" in {
            assert(Json.encode(STVNullableRow(Absent, 1)) == """{"m":1}""")
            assert(Json.encode(STVNullableRow(Present(5), 1)) == """{"n":"5","m":1}""")
            assert(Json.decode[STVNullableRow]("""{"m":1}""") == Result.succeed(STVNullableRow(Absent, 1)))
        }

        "an optional field with a transform and @omit(WhenDefault) is omitted at its default, Absent or Present, and written away from it" in {
            assert(Json.encode(STVOptionalDefaults()) == """{"m":1}""")
            assert(Json.encode(STVOptionalDefaults(Present(5), Absent)) == """{"a":"5","b":"","m":1}""")
            assert(Json.decode[STVOptionalDefaults]("""{"m":1}""") == Result.succeed(STVOptionalDefaults()))
            assert(Json.decode[STVOptionalDefaults]("""{"a":"5","b":"","m":1}""") ==
                Result.succeed(STVOptionalDefaults(Present(5), Absent)))
        }
    }

    "derivedVia on a type with no case fields points to transformVia" in {
        typeCheckFailure("kyo.Schema.derivedVia((i: Int) => kyo.STVTypes.STVPort.unsafe(i))")("transformVia")
    }

end SchemaTransformViaTest

object STVTypes:
    opaque type STVPort = Int
    object STVPort:
        def parse(i: Int): Either[String, STVPort] = if i >= 0 && i <= 65535 then Right(i) else Left(s"port out of range: $i")
        def unsafe(i: Int): STVPort                = i
        given Schema[STVPort]                      = summon[Schema[Int]].transformVia(parse)(_.value)
    end STVPort
    extension (p: STVPort) def value: Int = p
end STVTypes

case class STVServer(port: STVTypes.STVPort) derives CanEqual, Schema

object STVDecimalText extends Transformer.Of[Int](
        summon[Schema[String]].transformVia((s: String) => s.toIntOption.toRight(s"not a number: $s"))(_.toString)
    )

case class STVRow(@transform(STVDecimalText) n: Int) derives CanEqual, Schema

object STVEmptyIsAbsent extends Transformer.Of[Maybe[Int]](
        summon[Schema[String]].transformVia((s: String) =>
            if s.isEmpty then Right(Maybe.empty[Int]) else s.toIntOption.map(Maybe(_)).toRight(s"not a number: $s")
        )(_.fold("")(_.toString))
    )

case class STVOptionalRow(@transform(STVEmptyIsAbsent) n: Maybe[Int], m: Int) derives CanEqual, Schema

object STVNullWhenAbsent extends Transformer.Of[Maybe[Int]](
        summon[Schema[Maybe[String]]].transformVia((s: Maybe[String]) =>
            s match
                case Present(t) => t.toIntOption.map(Maybe(_)).toRight(s"not a number: $t")
                case Absent     => Right(Maybe.empty[Int])
        )(_.map(_.toString))
    )

case class STVNullableRow(@transform(STVNullWhenAbsent) n: Maybe[Int], m: Int) derives CanEqual, Schema

case class STVOptionalDefaults(
    @transform(STVEmptyIsAbsent) @omit(omit.WhenDefault) a: Maybe[Int] = Absent,
    @transform(STVEmptyIsAbsent) @omit(omit.WhenDefault) b: Maybe[Int] = Present(7),
    m: Int = 1
) derives CanEqual, Schema
