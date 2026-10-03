package kyo.internal.telegram

import kyo.*

class BotApiTest extends kyo.test.Test[Any]:

    private val now   = Instant.Epoch
    private val three = Schedule.fixed(Duration.Zero).take(3)
    private val cap   = 60.seconds

    private def flood(seconds: Long): String =
        s"""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after $seconds","parameters":{"retry_after":$seconds}}"""

    "a flood-control answer waits exactly the retry_after Telegram named, not the schedule's delay" in {
        val slow = Schedule.fixed(1.hour).take(3)
        assert(BotApi.nextAttempt(Present(slow), cap, flood(5), now).map(_._1) == Present(5.seconds))
        assert(BotApi.nextAttempt(Present(three), cap, flood(0), now).map(_._1) == Present(Duration.Zero))
    }

    "the wait is read from retry_after alone, whatever else the parameters name" in {
        val body = """{"ok":false,"error_code":429,"description":"x","parameters":{"retry_after":5,"migrate_to_chat_id":-1}}"""
        assert(BotApi.nextAttempt(Present(three), cap, body, now).map(_._1) == Present(5.seconds))
    }

    "a retry_after up to the max delay is waited, and a longer one is not retried" in {
        assert(BotApi.nextAttempt(Present(three), cap, flood(60), now).map(_._1) == Present(60.seconds))
        assert(BotApi.nextAttempt(Present(three), cap, flood(61), now) == Absent)
        assert(BotApi.nextAttempt(Present(three), cap, flood(3600), now) == Absent)
    }

    "nothing is retried without a retry schedule" in {
        assert(BotApi.nextAttempt(Absent, cap, flood(5), now) == Absent)
    }

    "nothing is retried once the schedule allows no more attempts" in {
        assert(BotApi.nextAttempt(Present(Schedule.done), cap, flood(5), now) == Absent)
    }

    "answers the Bot API does not document as repeatable are not retried" in {
        val answers = Chunk(
            """{"ok":true,"result":true}""",
            """{"ok":false,"error_code":429,"description":"Too Many Requests"}""",
            """{"ok":false,"error_code":500,"description":"Internal Server Error"}""",
            """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""",
            """{"ok":false,"error_code":400,"description":"Bad Request: group upgraded","parameters":{"migrate_to_chat_id":-100}}""",
            """{"ok":false,"error_code":429,"description":"x","parameters":{"retry_after":-1}}""",
            """{"ok":true,"result":true,"parameters":{"retry_after":5}}""",
            "<html>bad gateway</html>"
        )
        assert(answers.map(BotApi.nextAttempt(Present(three), cap, _, now)) == answers.map(_ => Absent))
    }

end BotApiTest
