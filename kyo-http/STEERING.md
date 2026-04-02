# STEERING

Run these targeted tests (NOT full suite):
```
sbt 'kyo-http/testOnly kyo.HttpServerTest' 2>&1 | grep 'Tests:' | tail -1
sbt 'kyo-http/testOnly kyo.HttpClientTest' 2>&1 | grep 'Tests:' | tail -1
sbt 'kyo-http/testOnly kyo.WebSocketTest kyo.WebSocketLocalTest' 2>&1 | grep 'Tests:' | tail -1
```

Then Native:
```
sbt 'kyo-httpNative/clean' 2>&1 | tail -1
sbt 'kyo-httpNative/testOnly kyo.internal.KqueueTransportTest' 2>&1 | grep 'Tests:' | tail -1
sbt 'kyo-httpNative/testOnly kyo.HttpServerTest' 2>&1 | grep 'Tests:' | tail -1
```

Report all counts.
