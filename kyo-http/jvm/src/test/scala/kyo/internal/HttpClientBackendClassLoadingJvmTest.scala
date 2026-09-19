package kyo.internal

class HttpClientBackendClassLoadingJvmTest extends kyo.BaseHttpTest:

    "HTTP client backend methods have resolvable JVM signatures" in {
        val backend = Class.forName("kyo.internal.client.HttpClientBackend", false, getClass.getClassLoader)
        val methods =
            try backend.getDeclaredMethods
            catch case error: LinkageError => fail(s"HTTP client backend method signatures reference an unavailable class: $error")
        val capacity = methods.filter(_.getName == "maxConnectionsPerHost")
        assert(capacity.map(_.getReturnType).sameElements(Array(java.lang.Integer.TYPE)))
    }

end HttpClientBackendClassLoadingJvmTest
