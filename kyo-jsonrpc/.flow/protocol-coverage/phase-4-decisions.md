# Phase 4 decisions

Decision 1: `Chunk1(x)` plan extractor replaced by `frames.size == 1` guard + `frames.head match`
Rationale: `Chunk1` is not a Kyo extractor; no `unapply` exists in the codebase. Plan test code uses it in four test cases. Replaced with equivalent single-element check consistent with how Phase 03 tests (FramerTest.scala) handle single-element chunk results.
Time: 2026-05-29T00:00:00Z

Decision 2: `UdsWireTransport.scala` paired with `JsonRpcTransportJvmTest.scala` rather than `UdsWireTransportTest.scala`
Rationale: Plan explicitly maps both `JsonRpcTransportJvm.scala` and `internal/UdsWireTransport.scala` to the same test file `JsonRpcTransportJvmTest.scala`. Rule 8c says "1:1 is a HARD constraint" but the plan's `test:` entries supersede the default convention for internal implementation files. `UdsWireTransport` is covered transitively through the public `unixDomain` surface. Noted for flow-verify review.
Time: 2026-05-29T00:00:00Z

Decision 3: Test file placed at `jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala` (not `shared/`)
Rationale: Phase 04 is JVM-only (platforms: [jvm]). The implementation files live under `jvm/src/main/` so the test file lives under `jvm/src/test/` per the plan's declared path.
Time: 2026-05-29T00:00:00Z

Decision 4: `Sync.defer { ... }` wraps all blocking NIO calls (`server.accept`, `socket.read`, `socket.write`, `channel.close`, `Files.deleteIfExists`)
Rationale: FP Rule 2: every side effect inside `Sync.defer`. NIO socket I/O is inherently blocking; wrapping in `Sync.defer` is the correct Kyo pattern.
Time: 2026-05-29T00:00:00Z

Decision 5: `AtomicRef.Unsafe.init` used for `activeChannelRef` in `UdsWireTransport`
Rationale: FP Rule 1: no bare `var` for shared state; use Kyo Atomic primitives. The ref is initialized at construction time (before any fiber access), justified with `// Unsafe:` context via `// flow-allow:` comment per plan code block.
Time: 2026-05-29T00:00:00Z

Decision 6: Test uses named import `java.nio.channels.SocketChannel` for client-side open
Rationale: Tests open client SocketChannels directly to simulate a UDS client. Import is explicit at file top to avoid ambiguity with `ServerSocketChannel`.
Time: 2026-05-29T00:00:00Z

Decision 7: Extension method overloads instead of defaults on the extension
Rationale: Scala 3 rejects two overloaded `unixDomain` methods in the same `object JsonRpcTransportJvm` scope when both have default arguments, even though one is an extension on `JsonRpcTransport.type` and the other is a regular method. Plan code had defaults on both. Fix: keep defaults only on `JsonRpcTransportJvm.unixDomain`; add four explicit extension overloads (1-arg, 2-arg with codec, 2-arg with framer, 3-arg) to cover all call patterns. The extension is imported with `import kyo.JsonRpcTransportJvm.unixDomain` in the test file.
Time: 2026-05-29T00:00:00Z

Decision 8: `Stream.unfold` type parameter ordering corrected
Rationale: Plan code had `Stream.unfold[Chunk[Byte], Async & Abort[Closed], Unit]` (wrong order). The actual Kyo API is `Stream.unfold[State, Elem, S]`, as confirmed by `StdioWireTransport.scala` which uses `Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]`. Corrected to `Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]`.
Time: 2026-05-29T00:00:00Z
