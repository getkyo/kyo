# STEERING

1. Tests do NOT need to create or pass IoLoopGroup. They just use `new NioTransport(clientSslContext = ...)` — the default group is used automatically. Do NOT change test files to pass a group.

2. The default lazy group needs a JVM shutdown hook to close selectors:
```scala
object NioTransport:
    lazy val defaultGroup: IoLoopGroup[NioIoLoop] =
        val g = new IoLoopGroup(...)
        Runtime.getRuntime.addShutdownHook(new Thread(() => g.closeAll()))
        g
```

Same for KqueueNativeTransport and EpollNativeTransport on Native.
