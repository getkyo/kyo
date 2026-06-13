package kyo

class SlackAckTest extends kyo.test.Test[Any]:

    "ViewAction enumerates exactly Errors/Update/Push/Clear and the ack constructors hold" in {
        val errorsCase: SlackAck.ViewAction = SlackAck.ViewAction.Errors(Map("b1" -> "bad"))
        val updateCase: SlackAck.ViewAction = SlackAck.ViewAction.Update(
            SlackView(SlackView.Type.Modal, Absent, "[]", Absent)
        )
        val pushCase: SlackAck.ViewAction = SlackAck.ViewAction.Push(
            SlackView(SlackView.Type.Modal, Absent, "[]", Absent)
        )
        val clearCase: SlackAck.ViewAction = SlackAck.ViewAction.Clear

        assert(errorsCase != clearCase)
        assert(clearCase == SlackAck.ViewAction.Clear)
        assert(errorsCase.isInstanceOf[SlackAck.ViewAction])
        assert(updateCase.isInstanceOf[SlackAck.ViewAction])
        assert(pushCase.isInstanceOf[SlackAck.ViewAction])

        val ack: SlackAck       = SlackAck.Ack
        val viewResp: SlackAck  = SlackAck.ViewResponse(clearCase)
        val cmdResp: SlackAck   = SlackAck.CommandResponse(SlackMessage(SlackId.ChannelId("C1"), "hi"))
        val blockResp: SlackAck = SlackAck.BlockActionsResponse(SlackMessage(SlackId.ChannelId("C1"), "hi"))

        assert(ack.isInstanceOf[SlackAck])
        assert(viewResp.isInstanceOf[SlackAck])
        assert(cmdResp.isInstanceOf[SlackAck])
        assert(blockResp.isInstanceOf[SlackAck])
    }

end SlackAckTest
