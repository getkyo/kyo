package kyo.internal

import kyo.*

class CdpWireTest extends kyo.BaseBrowserTest:

    "exception diagnostics retain a thrown primitive without a description" in {
        val details = ExceptionDetails(text = Present("Uncaught"), exception = Present(RemoteObject.`number`(42)))
        assert(ExceptionDetailsFormat.format(details) == "Uncaught: 42")
    }

    "eval numbers at the Long upper boundary do not saturate" in {
        CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`number`(9223372036854775808.0)))
            .map { value =>
                assert(BigDecimal(value).toDouble == 9223372036854775808.0)
                // Long.MaxValue rounds to the same Double, so a round-trip alone would accept saturation.
                assert(BigDecimal(value) > BigDecimal(Long.MaxValue))
            }
    }

    for literal <- Chunk("NaN", "-0", "Infinity", "-Infinity", "9007199254740993n") do
        s"eval preserves the CDP unserializable primitive $literal" in {
            val kind = if literal.endsWith("n") then "bigint" else "number"
            val json = s"""{"result":{"type":"$kind","unserializableValue":"$literal"}}"""
            Abort.get(Json.decode[EvalResult](json)).map(CdpEvalDecoder.extractEvalValue)
                .map(value => assert(value == literal))
        }
    end for

end CdpWireTest
