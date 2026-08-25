package kyo.http.client

class defaultMaxConnectionsPerHostTest extends kyo.test.Test[Any]:

    "defaults to 100 when the property is unset" in {
        // Guards the default the process-lifetime HttpClient uses and that the flag object parses and
        // self-registers at class load. The override mechanism is exercised by kyo-config's flag tests;
        // it is not retestable here because a StaticFlag resolves once at class load and the default
        // client is a read-once singleton.
        assert(defaultMaxConnectionsPerHost() == 100)
    }

end defaultMaxConnectionsPerHostTest
