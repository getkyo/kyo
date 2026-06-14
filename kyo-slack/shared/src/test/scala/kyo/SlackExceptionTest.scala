package kyo

class SlackExceptionTest extends kyo.test.Test[Any]:

    "every leaf is a SlackException and a KyoException" in {
        val leaves: List[SlackException] = List(
            new SlackHandshakeException("h"),
            new SlackTransportException("t"),
            new SlackDecodeException("d"),
            new SlackWebApiException("code", "w"),
            new SlackRateLimitException(30.seconds, "r"),
            new SlackTerminalException("term")
        )
        leaves.foreach { ex =>
            assert(ex.isInstanceOf[SlackException])
            assert(ex.isInstanceOf[KyoException])
        }
        assert(leaves.size == 6)
    }

    "SlackWebApiException carries the typed error code" in {
        val ex = new SlackWebApiException(error = "channel_not_found", message = "msg")
        assert(ex.error == "channel_not_found")
        assert(ex.getMessage.contains("msg"))
    }

    "SlackRateLimitException carries the typed backoff" in {
        val ex = new SlackRateLimitException(retryAfter = 30.seconds, message = "rate limited")
        assert(ex.retryAfter == 30.seconds)
    }

    "a leaf message rendered from a token-bearing context omits the token" in {
        val tokenValue = "xoxb-SECRET"
        val ex         = makeAuthException()
        val msg        = ex.getMessage
        assert(msg.contains("invalid_auth"))
        assert(!msg.contains(tokenValue))
    }

    private def makeAuthException(): SlackWebApiException =
        new SlackWebApiException("invalid_auth", "auth.test failed: invalid_auth")

end SlackExceptionTest
