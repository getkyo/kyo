# STEERING

The "client close while requests in-flight" failure is NOT flaky. It's a real issue with the shared event loop — when the client closes, the blocked selector.select() doesn't wake up.

Fix: when a connection is closed (closeNow), call selector.wakeup() to unblock the poller so it can notice the closed channel. Add a `wakeup()` call in the IoLoop's close/deregister path.

Do NOT spend time re-running to check if flaky. Fix the root cause.

Also run Native tests after JVM is green:
```
sbt 'kyo-httpNative/clean' && sbt 'kyo-httpNative/testOnly kyo.internal.KqueueTransportTest' 2>&1 | tail -5
```
