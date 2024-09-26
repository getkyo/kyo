package kyoTest.internal

import kyo.internal.IntEncoder
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IntEncoderTest extends AnyFreeSpec with Matchers:

    "supports 0 to 88" in {
        for i <- 0 to 88 do
            assert(IntEncoder.decode(IntEncoder.encode(i)) == i)
    }

    "fails out of range" in {
        intercept[Exception](IntEncoder.encode(-1))
        intercept[Exception](IntEncoder.encode(89))
        intercept[Exception](IntEncoder.encode(Int.MaxValue))
        intercept[Exception](IntEncoder.encode(Int.MinValue))
    }

    "encodes hashes" in {
        for _ <- 0 until 100 do
            IntEncoder.encodeHash((new Object).hashCode())
    }

end IntEncoderTest
