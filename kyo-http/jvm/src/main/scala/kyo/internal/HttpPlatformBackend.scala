package kyo.internal

import kyo.HttpBackend

private[kyo] object HttpPlatformBackend:
    lazy val client: HttpBackend.Client = new NettyClientBackend
    lazy val server: HttpBackend.Server = new NettyServerBackend
end HttpPlatformBackend
