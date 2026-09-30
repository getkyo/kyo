package kyo

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
