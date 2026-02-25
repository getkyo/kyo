package kyo.http2.internal

import kyo.http2.HttpBackend

private[http2] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = new FetchClientBackend
    lazy val server: HttpBackend.Server = new NodeServerBackend
end HttpPlatformBackend
