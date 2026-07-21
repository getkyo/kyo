package kyo.internal.postgres.unmarshaller

import kyo.Test

/** Unit tests for [[Unmarshallers]] — verifies that [[Unmarshallers.default]] exposes every unmarshaller singleton at its correct field.
  *
  * Guards against regressions in the explicit-construction path (e.g., removing default params changes which singleton is wired).
  */
class UnmarshallersTest extends Test:

    "Unmarshallers.default fields match the expected singleton objects" in {
        val u = Unmarshallers.default
        assert(u.authentication eq AuthenticationUnmarshaller)
        assert(u.parameterStatus eq ParameterStatusUnmarshaller)
        assert(u.backendKeyData eq BackendKeyDataUnmarshaller)
        assert(u.readyForQuery eq ReadyForQueryUnmarshaller)
        assert(u.rowDescription eq RowDescriptionUnmarshaller)
        assert(u.dataRow eq DataRowUnmarshaller)
        assert(u.commandComplete eq CommandCompleteUnmarshaller)
        assert(u.errorResponse eq ErrorResponseUnmarshaller)
        assert(u.noticeResponse eq NoticeResponseUnmarshaller)
        assert(u.notificationResponse eq NotificationResponseUnmarshaller)
        assert(u.parseComplete eq ParseCompleteUnmarshaller)
        assert(u.bindComplete eq BindCompleteUnmarshaller)
        assert(u.closeComplete eq CloseCompleteUnmarshaller)
        assert(u.parameterDescription eq ParameterDescriptionUnmarshaller)
        assert(u.noData eq NoDataUnmarshaller)
        assert(u.portalSuspended eq PortalSuspendedUnmarshaller)
        assert(u.copyInResponse eq CopyInResponseUnmarshaller)
        assert(u.copyOutResponse eq CopyOutResponseUnmarshaller)
        assert(u.copyData eq CopyDataUnmarshaller)
        assert(u.copyDone eq CopyDoneUnmarshaller)
    }

end UnmarshallersTest
