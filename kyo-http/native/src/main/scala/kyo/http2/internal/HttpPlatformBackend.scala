package kyo.http2.internal

import kyo.http2.HttpBackend

private[http2] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = throw new UnsupportedOperationException("http2 client not yet supported on Native")
    lazy val server: HttpBackend.Server = throw new UnsupportedOperationException("http2 server not yet supported on Native")
end HttpPlatformBackend
