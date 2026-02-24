package kyo.http2.internal

object NettyPlatformBackend:
    lazy val client = new NettyClientBackend
    lazy val server = new NettyServerBackend()
end NettyPlatformBackend
