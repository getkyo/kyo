package kyo.internal.postgres.marshaller

import kyo.Test

/** Unit tests for [[Marshallers]], verifies that [[Marshallers.default]] exposes every marshaller singleton at its correct field.
  *
  * Guards against regressions in the explicit-construction path (e.g., removing default params changes which singleton is wired).
  */
class MarshallersTest extends Test:

    "Marshallers.default fields match the expected singleton objects" in {
        val m = Marshallers.default
        assert(m.startupMessage eq StartupMessageMarshaller)
        assert(m.sslRequest eq SSLRequestMarshaller)
        assert(m.cancelRequest eq CancelRequestMarshaller)
        assert(m.passwordMessage eq PasswordMessageMarshaller)
        assert(m.saslInitialResponse eq SASLInitialResponseMarshaller)
        assert(m.saslResponse eq SASLResponseMarshaller)
        assert(m.query eq QueryMarshaller)
        assert(m.parse eq ParseMarshaller)
        assert(m.bind eq BindMarshaller)
        assert(m.describe eq DescribeMarshaller)
        assert(m.execute eq ExecuteMarshaller)
        assert(m.sync eq SyncMarshaller)
        assert(m.flush eq FlushMarshaller)
        assert(m.close eq CloseMarshaller)
        assert(m.terminate eq TerminateMarshaller)
        assert(m.copyData eq CopyDataMarshaller)
        assert(m.copyDone eq CopyDoneMarshaller)
        assert(m.copyFail eq CopyFailMarshaller)
    }

end MarshallersTest
