package kyo

import java.nio.file.StandardOpenOption
import java.time.DayOfWeek

class JavaEnumTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    given Schema[DayOfWeek]          = Schema.derived[DayOfWeek]
    given Schema[StandardOpenOption] = Schema.derived[StandardOpenOption]

    "round-trip DayOfWeek" in {
        val value: DayOfWeek = DayOfWeek.WEDNESDAY
        val encoded          = Json.encode[DayOfWeek](value)
        val decoded          = Json.decode[DayOfWeek](encoded)
        assert(decoded == Result.succeed(DayOfWeek.WEDNESDAY))
    }

    "round-trip StandardOpenOption" in {
        val value: StandardOpenOption = StandardOpenOption.READ
        val encoded                   = Json.encode[StandardOpenOption](value)
        val decoded                   = Json.decode[StandardOpenOption](encoded)
        assert(decoded == Result.succeed(StandardOpenOption.READ))
    }

    "decode of unknown constant fails with UnknownVariantException" in {
        val raw    = "\"NOT_A_DAY\""
        val result = Json.decode[DayOfWeek](raw)
        assert(result.isFailure)
        result match
            case Result.Failure(e: UnknownVariantException) =>
                assert(e.variantName == "NOT_A_DAY")
                assert(e.getMessage.contains("NOT_A_DAY"))
            case other => fail(s"Expected Failure with UnknownVariantException, got $other")
        end match
    }
end JavaEnumTest
