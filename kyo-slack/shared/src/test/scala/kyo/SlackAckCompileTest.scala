package kyo

/** Compile-fail surface for the structural-acking contract: a handler that does not
  * return a `SlackAck` does not type-check (forgetting to ack is a compile error), and
  * there is no public ack/sendAck method to call (double-acking is unrepresentable).
  * These hold by the handler return type and the absence of any ack channel on the
  * public surface, so the assertions are compile-time, not runtime.
  */
class SlackAckCompileTest extends kyo.test.Test[Any]:

    "a handler whose body returns a non-SlackAck does not type-check" in {
        typeCheckFailure(
            "Slack.connect(SlackConfig(SlackToken.AppLevel(\"x\"), SlackToken.Bot(\"y\")))((_: SlackEnvelope) => Slack.chatPostMessage(SlackMessage(SlackId.ChannelId(\"C\"), \"x\")))"
        )
    }

    "there is no public ack method on Slack" in {
        typeCheckFailure("""Slack.ack(SlackId.EnvelopeId("E1"))""")
    }

    "there is no public ack method on SlackConnection" in {
        typeCheckFailure("""(c: SlackConnection) => c.ack(SlackId.EnvelopeId("E1"))""")
    }

    "there is no public sendAck method on SlackConnection" in {
        typeCheckFailure("""(c: SlackConnection) => c.sendAck""")
    }

end SlackAckCompileTest
