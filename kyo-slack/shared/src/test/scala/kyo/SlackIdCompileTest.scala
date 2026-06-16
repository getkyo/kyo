package kyo

class SlackIdCompileTest extends kyo.test.Test[Any]:

    "ChannelId is not assignable where a TriggerId is required" in {
        typeCheckFailure("val _: SlackId.TriggerId = SlackId.ChannelId(\"C123\")")("Found:")
    }

    "ChannelId is not assignable where a UserId is required" in {
        typeCheckFailure("val _: SlackId.UserId = SlackId.ChannelId(\"C123\")")("Found:")
    }

    "a bare String is not assignable to a ChannelId" in {
        typeCheckFailure("val _: SlackId.ChannelId = \"C123\"")("Found:")
    }

end SlackIdCompileTest
