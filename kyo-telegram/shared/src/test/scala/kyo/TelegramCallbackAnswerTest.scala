package kyo

class TelegramCallbackAnswerTest extends kyo.test.Test[Any]:

    "a text of 0 and of 200 characters is accepted" in {
        assert(Chunk("", "x" * 200).map(t => TelegramCallbackAnswer(text = Present(t)).text) == Chunk(Present(""), Present("x" * 200)))
    }

    "a text of more than 200 characters panics with its length" in {
        import TelegramInvalidCallbackAnswerException.Problem
        assert(intercept[TelegramInvalidCallbackAnswerException](TelegramCallbackAnswer(text = Present("x" * 201))) ==
            TelegramInvalidCallbackAnswerException(Problem.TextLength(201)))
    }

    "a cache time of zero and of whole seconds up to Int.MaxValue is accepted" in {
        val times = Chunk(Duration.Zero, 5.seconds, Int.MaxValue.toLong.seconds)
        assert(times.map(t => TelegramCallbackAnswer(cacheTime = Present(t)).cacheTime) == times.map(Present(_)))
    }

    "a cache time of a fraction of a second, infinite or beyond Int.MaxValue seconds panics rather than rounding" in {
        import TelegramInvalidCallbackAnswerException.Problem
        val times = Chunk(1500.millis, Duration.Infinity, (Int.MaxValue.toLong + 1).seconds)
        assert(times.map(t => intercept[TelegramInvalidCallbackAnswerException](TelegramCallbackAnswer(cacheTime = Present(t)))) ==
            times.map(t => TelegramInvalidCallbackAnswerException(Problem.CacheTime(t))))
    }

end TelegramCallbackAnswerTest
