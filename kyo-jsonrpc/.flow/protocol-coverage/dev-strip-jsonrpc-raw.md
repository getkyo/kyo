# DEV-marker strip report

Run id: protocol-coverage-jsonrpc
Root: /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc

## Orphan check

SKIPPED (--no-orphan-check). Pass-1 will run without verifying that
every annotation traces to a VALIDATED_EXCEPTION verdict. This flag
is discouraged outside early-state campaigns.

## Per-file changes

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC JVM-only UDS transport extension on the shared JsonRpcTransport companion
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala

allow=4 dev=0

```
13:    // flow-allow: class construction is always wrapped in Sync.defer at the call site
20:            // flow-allow: AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
32:                // flow-allow: AtomicRef.Unsafe access for accept-then-read MVP
53:            // flow-allow: AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala

allow=5 dev=0

```
1:// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.cancellation field
26:    // flow-allow: annotation pins the public ParamsEncoder type alias so the lambda matches the case-class constructor field type
32:    // flow-allow: annotation pins the public ParamsEncoder type alias so the lambda matches the case-class constructor field type
38:    // flow-allow: annotation pins the public ParamsDecoder type alias so the lambda matches the case-class constructor field type
48:    // flow-allow: annotation pins the public ParamsDecoder type alias so the lambda matches the case-class constructor field type
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala

allow=2 dev=0

```
1:// flow-allow: PUBLIC opaque-type for the JsonRpcEndpoint.call/notify extras parameter
14:    // flow-allow: opaque-type companion carve-out (FLOW Decision #30 (b))
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC framer preset library for byte-stream transports (line-delimited stdio, Content-Length envelopes)
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala

allow=3 dev=0

```
1:// flow-allow: PUBLIC handler-context receiver consumed by user JsonRpcMethod handlers
13:// flow-allow: Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl
27:    // flow-allow: test-only construction escape hatch consumed by JsonRpcMethodTest
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC config-strategy sum type referenced by JsonRpcEndpoint.Config.idStrategy field
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC codec interface referenced by JsonRpcEndpoint.Config.codec field
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala

allow=3 dev=0

```
1:// flow-allow: PUBLIC primary user-facing endpoint surface
6:// flow-allow: Hub.scala:22 smart-constructor pattern; init through JsonRpcEndpoint.init
81:    // flow-allow: Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC wire-shape ADT exposed through JsonRpcTransport and MessageGate user implementations
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC error-channel ADT appearing in JsonRpcEndpoint Abort rows and user error matching
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala

allow=2 dev=0

```
1:// flow-allow: PUBLIC id ADT referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId
23:                // flow-allow: word in string literal describes the JSON absent-value type, not a reference
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala

allow=7 dev=0

```
1:// flow-allow: PUBLIC method-binding surface built by user and passed to JsonRpcEndpoint.init
17:    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
19:    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
21:    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
72:        // flow-allow: Map.get returns scala.Option; match arms are interop, not kyo code
74:            // flow-allow: scala.Option arm; interop with Map.get
76:            // flow-allow: scala.Option arm; interop with Map.get
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala

allow=2 dev=0

```
1:// flow-allow: PUBLIC response wire-shape with success/failure smart constructors and Schema derivation
11:// flow-allow: Hub.scala:22 smart-constructor pattern; users construct JsonRpcResponse through .success / .failure factories
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala

allow=3 dev=0

```
1:// flow-allow: PUBLIC transport interface implemented by users and consumed by JsonRpcEndpoint.init
25:            // flow-allow: type-widening from internal subtype to public supertype required for the returned tuple element type
27:            // flow-allow: type-widening from internal subtype to public supertype required for the returned tuple element type
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC gate trait implemented by users and consumed via JsonRpcEndpoint.Config.gate
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.progress field
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala

allow=2 dev=0

```
1:// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.unknownMethod field with three documented presets
4:// flow-allow: Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala

allow=1 dev=0

```
1:// flow-allow: PUBLIC byte-level user-facing transport seam consumed by JsonRpcTransport.fromWire and the byte-stream adapter set
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala

allow=8 dev=0

```
4:// flow-allow: ConcurrentHashMap is the structural concurrent pending-map; no Kyo-safe equivalent for CAS-based inbound tracking
42:                            // flow-allow: Log.live unsafe-warn inside deferred-sync block; no safe Log equivalent within Sync
49:                                // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
52:                                    // flow-allow: interrupt monitor/cleanup fiber from outside its scheduler; no safe equivalent in Fiber public API
61:                                        // flow-allow: suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
68:                            // flow-allow: suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
111:                            // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
120:                        // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/FramerImpl.scala

allow=6 dev=0

```
10:        // flow-allow: AtomicRef for stateful leftover buffer across mapChunk calls; Sync effect threaded via mapChunk S2
15:                        // flow-allow: AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption
29:        // flow-allow: AtomicRef for stateful leftover buffer across mapChunk calls; Sync effect threaded via mapChunk S2
34:                        // flow-allow: AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption
116:                        // flow-allow: scala.Option arm; interop with stdlib Try.toOption
118:                        // flow-allow: scala.Option arm; interop with stdlib Try.toOption
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala

allow=4 dev=0

```
11:                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
13:                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
17:                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
19:                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala

allow=7 dev=0

```
63:                            // flow-allow: stdlib Map.get() returns scala.Option; match arms are interop, not kyo code
65:                                // flow-allow: scala.Option arm; interop with stdlib Map.get (covered by line above)
67:                                // flow-allow: scala.Option arm; interop with stdlib Map.get (covered by comment above match)
69:                                // flow-allow: scala.Option arm; interop with stdlib Map.get (covered by comment above match)
162:                    // flow-allow: Iterator.find() returns scala.Option; match arms are interop, not kyo code
164:                        // flow-allow: scala.Option arm; interop with Iterator.find (covered by comment above match)
167:                        // flow-allow: scala.Option arm; interop with Iterator.find (covered by comment above match)
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala

allow=116 dev=0

```
4:// flow-allow: ConcurrentHashMap follows kyo.Exchange pending-map precedent (kyo-core/shared Exchange.scala:3); cross-platform via JS/Native JDK shim
20:    // flow-allow: AtomicReference is kyo.AtomicRef's underlying type (Atomic.scala:354); cross-platform via JS/Native JDK shim
64:    // flow-allow: AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); ConcurrentHashMap follows Exchange precedent; cross-platform via JS/Native JDK shim
76:                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
91:                                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
131:                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
166:        // flow-allow: AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); used as a mutable deadline cell shared between call and monitor fibers
170:        // flow-allow: Promise Unsafe init constructs a state cell readable from Sync-only Exchange callbacks; no safe Promise equivalent
186:                                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
235:                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
237:                                                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
238:                                                    // flow-allow: wall-clock read inside Sync.Unsafe.defer suspension boundary
246:                                                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
248:                                                            // flow-allow: CAS-won path completes unit promise from outside originating fiber; no safe equivalent in Promise public API
261:                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
263:                                                    // flow-allow: fiber interrupt cleans up monitor or writer or handler fiber from outside its scheduler; no safe equivalent in Fiber public API
266:                                                        // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
275:                                                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
313:                                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
379:                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
382:                        // flow-allow: Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
386:                        // flow-allow: AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); used as deadline cell shared between call and monitor fibers
389:                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
390:                                // flow-allow: wall-clock read inside enclosing Sync.Unsafe.defer suspension boundary
392:                                // flow-allow: AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); per-request deadline cell
400:                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
418:                                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
420:                                            // flow-allow: fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API
424:                                                // flow-allow: channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
426:                                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
461:                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
464:                            // flow-allow: Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
467:                            // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
481:                                                    // flow-allow: word appears in comment only; Structure.Value.Null is a kyo ADT case, not a reference
487:                                                        // flow-allow: AtomicX setter from Sync-only Exchange callback; no safe Atomic equivalent within Sync
490:                                                        // flow-allow: channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
497:                                                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
500:                                                        // flow-allow: channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
515:                                                                // flow-allow: typed stream emit after per-item decode; no canonical alternative
526:                                                // flow-allow: word appears in comment only; kyo ADT case, not a reference
532:                                                            // flow-allow: final item stream emit after decode; no canonical alternative
553:                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
555:                    // flow-allow: Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
568:        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
575:                    // flow-allow: channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
588:                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
590:                            // flow-allow: promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
593:                                // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
607:                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
629:                                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
631:                                        // flow-allow: promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
634:                                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
659:            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
661:            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
669:                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
672:                        // flow-allow: Exchange bulk-fail of pending promises from finalizer; no safe equivalent in Exchange public API
675:                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
678:                            // flow-allow: promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
681:                                // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
690:                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
693:                                    // flow-allow: channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
700:                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
705:                                                // flow-allow: fiber interrupt cleans up monitor or writer or handler fiber from outside its scheduler; no safe equivalent in Fiber public API
745:        // flow-allow: AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); ConcurrentHashMap follows Exchange precedent; cross-platform via JS/Native JDK shim
758:                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
760:                    // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
765:                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
767:                    // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
771:                    // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
782:                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
784:                                    // flow-allow: AtomicReference is kyo.AtomicRef's underlying type (Atomic.scala:354); per-request pending-cancel cell mirroring Exchange pattern
790:                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
870:                                                                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
876:                                                                                        // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
878:                                                                                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
887:                                                                                            // flow-allow: wall-clock read inside enclosing unsafe deferred block suspension boundary
900:                                                                    // flow-allow: stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
902:                                                                        // flow-allow: scala.Option arm; interop with methodMap.get (covered by comment above match)
907:                                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
927:                                                                        // flow-allow: scala.Option arm; interop with methodMap.get (covered by comment above match)
945:                                                                                            // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
948:                                                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
984:                                                    // flow-allow: stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
986:                                                        // flow-allow: scala.Option arm; interop with methodMap.get (covered by comment above match)
990:                                                                // flow-allow: Promise Unsafe init constructs a state cell readable from Sync-only Exchange callbacks; no safe Promise equivalent
992:                                                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1002:                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1010:                                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1015:                                                                    // flow-allow: fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API
1044:                                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1051:                                                                                    // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
1054:                                                                                        // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1066:                                                                                    // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
1069:                                                                                        // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1075:                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1079:                                                        // flow-allow: scala.Option arm; interop with methodMap.get (covered by comment above match)
1091:                                                                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1095:                                                                        // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
1110:                                                                    // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1114:                                                                        // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
1119:                                                                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1121:                                                                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1140:                                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1144:                                                                    // flow-allow: channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
1157:                                                        // flow-allow: word appears in comment only; no absent-reference in code
1161:                                                                // flow-allow: promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
1163:                                                                    // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1171:                                                        // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1179:                                                                            // flow-allow: promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
1182:                                                                                // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1198:                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1202:                                                            // flow-allow: CAS-won path completes pending caller promise from outside originating fiber
1205:                                                                // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
1242:                                                                    // flow-allow: AtomicX getter from Sync-only Exchange callback or monitor fiber; no safe Atomic equivalent within Sync
1247:                                                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
1278:                            // flow-allow: embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala

allow=2 dev=0

```
4:// flow-allow: ConcurrentHashMap is the structural concurrent pending-map; no Kyo-safe equivalent for CAS-based inbound tracking
27:                    // flow-allow: putIfAbsent returns Java reference; Absent means null (insert succeeded), Present means collision
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/RawJsonParser.scala

allow=1 dev=0

```
26:        // flow-allow: Result.catching converts JSON parse errors to typed ParseException
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/StdioWireTransport.scala

allow=1 dev=0

```
16:            // flow-allow: EOFException from Console.readLine signals stream end; absorbed into Absent to close the stream
```

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/internal/WireTransportAdapter.scala

allow=2 dev=0

```
14:                // flow-allow: RawJsonParser.encode converts Structure.Value to standard JSON-RPC wire bytes;
28:            // flow-allow: RawJsonParser.parse converts arbitrary JSON-RPC wire bytes into Structure.Value;
```

## Final residual scan

PASS. Zero `// flow-allow:` or `// DEV:` markers remain.

## Totals

- allow conversions: 189
- DEV lines/blocks removed: 0
- files touched: 28

