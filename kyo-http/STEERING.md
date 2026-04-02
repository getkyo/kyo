# STEERING

In HttpPlatformBackend, do NOT annotate the transport variable with `: Transport`. Let the compiler infer the concrete type so Tag derivation works:

```scala
// WRONG — erases Connection type
private val transport: Transport = if isLinux then new EpollNativeTransport else new KqueueNativeTransport

// CORRECT — concrete type inferred, separate vals
private val transport =
    if isLinux then new EpollNativeTransport else new KqueueNativeTransport
```

Actually even simpler — use separate lazy vals per platform since they have different Connection types that can't unify:

```scala
private lazy val (transportForClient, transportForServer, transportForWs) =
    if isLinux then
        val t = new EpollNativeTransport
        (t, t, t)
    else
        val t = new KqueueNativeTransport
        (t, t, t)
```

Or just use two separate code paths:
```scala
if isLinux then
    lazy val client = new HttpTransportClient(new EpollNativeTransport)
    ...
else
    lazy val client = new HttpTransportClient(new KqueueNativeTransport)
    ...
```
