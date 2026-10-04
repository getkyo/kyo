package kyo

import kyo.test.prop.Gen
import kyo.test.prop.PropertyTest

class HttpCodecPropertyTest extends PropertyTest[Any]:

    /** Durations over the whole nanosecond range of a `Long`: a magnitude from 2^0 to 2^62 with random lower bits, so every scale is
      * sampled evenly rather than only the small values a size-bounded generator reaches, plus the counts where precision or the range
      * ends.
      */
    private val durations: Gen[Duration] =
        val spread = Gen.zipWith(Gen.oneOf((0 to 62)*), Gen.listOfN(8, Gen.int)) { (exponent, bytes) =>
            val bits = bytes.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff).toLong)
            val low  = if exponent == 0 then 0L else bits & ((1L << exponent) - 1)
            (1L << exponent) | low
        }
        val edges = Gen.oneOf(0L, 1L, (1L << 53) - 1, 1L << 53, (1L << 53) + 1, Long.MaxValue - 1, Long.MaxValue)
        Gen.frequency(9 -> spread, 1 -> edges).map(Duration.fromNanos)
    end durations

    "HttpCodec[Duration] decodes every duration it encodes" - {
        val codec = summon[HttpCodec[Duration]]
        forAll(durations) { d =>
            assert(codec.decode(codec.encode(d)) == Result.succeed(d), s"${d.toNanos}ns encoded as '${codec.encode(d)}'")
        }
    }

end HttpCodecPropertyTest
