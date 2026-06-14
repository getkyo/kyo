package kyo

/** Behavioural tests for `Tasty.Uuid.parse` and the surrounding opaque-type contract.
  *
  * Verifies the canonical-lowercase normalisation, the round-trip via `asString`, and the
  * `TastyError.InvalidUuid` failure path for malformed input.
  */
class TastyUuidParseTest extends kyo.test.Test[Any]:

    "valid canonical-lowercase UUID parses and round-trips through asString" in {
        val input = "550e8400-e29b-41d4-a716-446655440000"
        Tasty.Uuid.parse(input) match
            case Result.Success(uuid) =>
                assert(uuid.asString == input, s"Expected asString == '$input' but got '${uuid.asString}'")
                assert(uuid.show == input, s"Expected show == '$input' but got '${uuid.show}'")
            case other =>
                fail(s"Expected Result.Success for '$input' but got $other")
        end match
        succeed
    }

    "valid uppercase UUID parses and is normalised to lowercase" in {
        val upper    = "550E8400-E29B-41D4-A716-446655440000"
        val expected = upper.toLowerCase
        Tasty.Uuid.parse(upper) match
            case Result.Success(uuid) =>
                assert(uuid.asString == expected, s"Expected '$expected' but got '${uuid.asString}'")
            case other =>
                fail(s"Expected Result.Success for '$upper' but got $other")
        end match
        succeed
    }

    "valid mixed-case UUID parses and is normalised to lowercase" in {
        val mixed    = "550e8400-E29B-41d4-A716-446655440000"
        val expected = mixed.toLowerCase
        Tasty.Uuid.parse(mixed) match
            case Result.Success(uuid) =>
                assert(uuid.asString == expected, s"Expected '$expected' but got '${uuid.asString}'")
            case other =>
                fail(s"Expected Result.Success for '$mixed' but got $other")
        end match
        succeed
    }

    "malformed inputs return Result.fail(TastyError.InvalidUuid(input))" in {
        val malformed = Seq(
            "",
            "not-a-uuid",
            "550e8400-e29b-41d4-a716",
            "550e8400-e29b-41d4-a716-4466554400001",
            "550e8400e29b41d4a716446655440000",
            "550e8400-e29b-41d4-a716-44665544000g"
        )
        malformed.foreach { input =>
            Tasty.Uuid.parse(input) match
                case Result.Failure(TastyError.InvalidUuid(payload)) =>
                    assert(payload == input, s"Expected InvalidUuid('$input') but got InvalidUuid('$payload')")
                case other =>
                    fail(s"Expected Result.Failure(InvalidUuid('$input')) but got $other")
            end match
        }
        succeed
    }

    "two equal Uuid values compare equal via ==" in {
        val a = Tasty.Uuid.parse("00000000-0000-0000-0000-000000000001")
        val b = Tasty.Uuid.parse("00000000-0000-0000-0000-000000000001")
        (a, b) match
            case (Result.Success(ua), Result.Success(ub)) =>
                assert(ua == ub, "Two Uuid values with the same canonical form must be equal")
            case other =>
                fail(s"Expected both parses to succeed; got $other")
        end match
        succeed
    }

    "Uuid equality is canonical-form: uppercase and lowercase inputs compare equal" in {
        val a = Tasty.Uuid.parse("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        val b = Tasty.Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        (a, b) match
            case (Result.Success(ua), Result.Success(ub)) =>
                assert(ua == ub, "Uppercase and lowercase inputs must produce equal Uuid values")
            case other =>
                fail(s"Expected both parses to succeed; got $other")
        end match
        succeed
    }

end TastyUuidParseTest
