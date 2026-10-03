package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramCallbackAnswerTest extends kyo.test.Test[Any]:

    "a text of 0 and of 200 characters is accepted" in {
        assert(Chunk("", "x" * 200).map(t => Telegram.CallbackAnswer.init(text = Present(t)).map(_.text)) ==
            Chunk(Result.succeed(Present("")), Result.succeed(Present("x" * 200))))
    }

    "a text of more than 200 characters fails with its length" in {
        import TelegramInvalidCallbackAnswerException.Problem
        assert(Telegram.CallbackAnswer.init(text = Present("x" * 201)) ==
            Result.fail(TelegramInvalidCallbackAnswerException(Problem.TextLength(201))))
    }

    "a cache time of zero and of whole seconds up to Int.MaxValue is accepted" in {
        val times = Chunk(Duration.Zero, 5.seconds, Int.MaxValue.toLong.seconds)
        assert(times.map(t => Telegram.CallbackAnswer.init(cacheTime = Present(t)).map(_.cacheTime)) ==
            times.map(t => Result.succeed(Present(t))))
    }

    "a cache time of a fraction of a second, infinite or beyond Int.MaxValue seconds fails rather than rounding" in {
        import TelegramInvalidCallbackAnswerException.Problem
        val times = Chunk(1500.millis, Duration.Infinity, (Int.MaxValue.toLong + 1).seconds)
        assert(times.map(t => Telegram.CallbackAnswer.init(cacheTime = Present(t))) ==
            times.map(t => Result.fail(TelegramInvalidCallbackAnswerException(Problem.CacheTime(t)))))
    }

    "the empty answer is the one init builds with no arguments" in {
        assert(Telegram.CallbackAnswer.init() == Result.succeed(Telegram.CallbackAnswer.empty))
    }

    "an answer is answerCallbackQuery's fields, with cache_time in seconds, and one Telegram would refuse fails the decode" in {
        val answer = Telegram.CallbackAnswer.init(
            text = Present("Recorded"),
            showAlert = true,
            url = Present(Telegram.Url.init("https://t.me/standup_bot?game=x").getOrThrow),
            cacheTime = Present(30.seconds)
        ).getOrThrow
        val json = """{"text":"Recorded","show_alert":true,"url":"https://t.me/standup_bot?game=x","cache_time":30}"""
        assert(wire(answer) == wire(json))
        assert(Json.decode[Telegram.CallbackAnswer](json) == Result.succeed(answer))
        assert(Json.decode[Telegram.CallbackAnswer](s"""{"text":"${"x" * 201}","show_alert":false}""").isFailure)
    }

end TelegramCallbackAnswerTest
