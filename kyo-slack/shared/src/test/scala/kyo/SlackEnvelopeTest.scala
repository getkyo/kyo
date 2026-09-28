package kyo

class SlackEnvelopeTest extends kyo.test.Test[Any]:

    "an envelope case has no Schema, and asking for one names why: the module decodes Slack's frames itself" in {
        val reason = "has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        typeCheckFailure("summon[Schema[SlackEnvelope.SlashCommand]]")(s"SlackEnvelope $reason")
    }

end SlackEnvelopeTest
