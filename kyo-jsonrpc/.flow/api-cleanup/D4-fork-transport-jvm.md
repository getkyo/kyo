# D4. Fork 4 Decision: `JsonRpcTransportJvm`. Leave as-is, fold cross-platform, or split out?

Source-grounded decision for Fork 4 raised in `C-cleanup-plan.md:322-326` and originally flagged at `A4-naming-and-nesting.md:288-289`. Inputs verified directly against source.

---

## 1. Current shape

`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:10` declares a top-level `object JsonRpcTransportJvm` in `package kyo` (publicly visible). It exposes:

- `JsonRpcTransportJvm.unixDomain(sockPath, framer, codec)` returning `JsonRpcTransport < (Async & Scope)` (`JsonRpcTransportJvm.scala:16-34`). Implementation opens a `ServerSocketChannel.open(StandardProtocolFamily.UNIX)`, binds `UnixDomainSocketAddress.of(sockPath)` (`JsonRpcTransportJvm.scala:22-24`), registers a `Scope.ensure` finalizer that closes the channel and `Files.deleteIfExists(sockPath)` (`JsonRpcTransportJvm.scala:25-29`), then wraps the channel in `internal.UdsWireTransport` and lifts it through `JsonRpcTransport.fromWire` (`JsonRpcTransportJvm.scala:30-32`).
- Four `extension (self: JsonRpcTransport.type) def unixDomain(...)` overloads (`JsonRpcTransportJvm.scala:36-45`) that forward to the object's `unixDomain`, so callers write `JsonRpcTransport.unixDomain(sock)` rather than `JsonRpcTransportJvm.unixDomain(sock)`. The test confirms this is the idiomatic call site: `JsonRpcTransport.unixDomain(sock)` at `kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala:15, 32, 57, 67`.

The internal `UdsWireTransport` (`kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala:8`) is a `single-client-MVP` (`UdsWireTransport.scala:10-12`) using `ServerSocketChannel.accept()` plus an `AtomicRef.Unsafe[Maybe[SocketChannel]]` (`UdsWireTransport.scala:15-16`) under `AllowUnsafe.embrace.danger` to handle the lazy accept-then-read loop in `Stream.unfold` (`UdsWireTransport.scala:29-49`). The implementation is honest about its single-connection scope and defers a per-conn map to the consumer-module roadmap.

JVM-only because: `java.net.UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, and `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` are JDK 16+ APIs (`JsonRpcTransportJvm.scala:4-6`). They do not exist on Scala.js (no `java.net` shim) and Scala Native must reach them via FFI to `sys/socket.h` + `AF_UNIX` rather than the `java.net.*` types.

Consumers of the `JsonRpcTransportJvm` name: zero outside the JVM test file (`grep` confirms only `JsonRpcTransportJvmTest.scala:7` imports it). All test bodies use the `JsonRpcTransport.unixDomain(...)` extension surface, not the bare object name. The `*Jvm` suffix is therefore documentation-only; users never type it.

---

## 2. kyo-http's UDS pattern: already fully cross-platform

Verified directly. Kyo-http does NOT carry a `HttpServerJvm` or `HttpClientJvm` type. Instead, UDS is a `Maybe[String]` field on `HttpServerConfig` (`kyo-http/shared/src/main/scala/kyo/HttpServerConfig.scala:64`) and a fluent setter `def unixSocket(path: String): HttpServerConfig = copy(unixSocket = Present(path))` (`HttpServerConfig.scala:78`). Callers write `HttpServerConfig.default.unixSocket(sockPath)` at `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17, 33, 49, 65, 90, 201` and `HttpClientUnixTest.scala:16, 438`.

The platform-specific implementation lives in three parallel files reachable through `HttpPlatformTransport.transport` (`kyo-http/jvm/src/main/scala/kyo/internal/HttpPlatformTransport.scala:7-15`, native at `:7-17`, js at `:7-15`). Each platform supplies:

- JVM: `NioTransport.connectUnix(path)` / `NioTransport.listenUnix(path, backlog)` using `java.net.UnixDomainSocketAddress` (`kyo-http/jvm/src/main/scala/kyo/internal/NioTransport.scala:726, 758`).
- Native: `NativeTransport.connectUnix(path)` / `NativeTransport.listenUnix(path, backlog)` calling into `kyo_tcp.c` which uses `socket(AF_UNIX, SOCK_STREAM, 0)` and `sun_family = AF_UNIX` (`kyo-http/native/src/main/resources/scala-native/kyo_tcp.c:179, 193, 226, 235`).
- JS: `JsTransport.connectUnix(path)` / `JsTransport.listenUnix(path, backlog)` using Node's `net.Socket` with `path` instead of `port` (`kyo-http/js/src/main/scala/kyo/internal/JsTransport.scala:165, 198`). Even Node-side JS has a working UDS path; only the browser is genuinely incapable, and kyo-http already ships browser-incompatible primitives elsewhere (TCP `listen` is Node-only too).

So the kyo-http pattern is: ONE public name, THREE platform implementations behind `HttpPlatformTransport`, all with real working UDS. JS-via-Node is not a "abort with NotSupported" shim; it is a real impl that calls `net.Socket({ path })`. There is no platform that gets a `NotSupported` error.

`A1-kyo-http-template.md:383-385` summarises the rule: "Zero platform-specific public types. Public surface identical across JVM / Native / JS. No `HttpClientJvm` / `HttpServerNative` / per-platform extension methods." `JsonRpcTransportJvm` is the only kyo-jsonrpc public type that breaks this rule.

---

## 3. Three options, weighed

### Option A. Leave as `JsonRpcTransportJvm` (status-quo)

Status: works, has tests, the extension method `JsonRpcTransport.unixDomain(sockPath)` already presents a clean call site even though the implementing object carries the `Jvm` suffix.

Pros: zero engineering work; the JVM-only constraint is documented by the suffix; no risk of regression.

Cons: violates the kyo-http "zero platform-specific public types" rule (`A1 §9` at `A1-kyo-http-template.md:383-385`). The `*Jvm` suffix is the ONLY suffix-typed public name across kyo-jsonrpc + kyo-jsonrpc-http; preserving it leaves a single ugly outlier. The `extension (self: JsonRpcTransport.type)` block (`JsonRpcTransportJvm.scala:36-45`) is already doing half the cross-platform-surface job: callers say `JsonRpcTransport.unixDomain(sock)` and the only reason `JsonRpcTransportJvm` is still a top-level name is that the source file owns the `def unixDomain` body. If we ever add Native or JS implementations, we must restructure anyway.

### Option B. Fold into `JsonRpcTransport.unixDomain` with per-platform shims

Move the `unixDomain` factory to `JsonRpcTransport`'s shared companion. The JVM implementation moves to `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala`. Native and JS get matching `UdsBackend.scala` files: Native via FFI to `kyo_tcp.c`-style AF_UNIX bindings (or reuse `kyo-http/native`'s once `kyo-net` extracts them); JS via Node's `net.Socket({ path })` matching `JsTransport.connectUnix` at `kyo-http/js/src/main/scala/kyo/internal/JsTransport.scala:165`.

This mirrors kyo-http exactly. After the fold there are no `*Jvm` public types in kyo-jsonrpc.

Pros: aligns with the canonical kyo-http template; eliminates the only suffix-typed name; gives Native and JS users real UDS without rewriting; preserves the existing `JsonRpcTransport.unixDomain(sockPath)` call site so zero consumer churn.

Cons: requires Native FFI work (or borrowing `kyo-http/native`'s `kyo_tcp.c` bindings) and JS work (Node `net.Socket` plumbing) before this campaign can claim "cross-platform parity". The current `UdsWireTransport` is a single-client MVP (`UdsWireTransport.scala:10-12`); the Native and JS shims would have to match its scope, which is reasonable. The work is bounded but not trivial: building UDS-capable `WireTransport`s on Native and JS, with the same `incoming: Stream` plus `send: Chunk[Byte] => Unit` shape, plus accept+close lifecycle. Native can largely lift code from `kyo-http/native/src/main/resources/scala-native/kyo_tcp.c:179-235`. JS lifts from `JsTransport.scala:165-253`.

### Option C. Move to `kyo-jsonrpc-uds` separate subproject

Mirror the `kyo-jsonrpc-http` separation: hoist `JsonRpcTransportJvm.unixDomain` and `UdsWireTransport` into a `kyo-jsonrpc-uds` module that depends on `kyo-jsonrpc` and on whatever low-level UDS abstraction we have. Users opt in by adding the dependency.

Pros: explicit module boundary; no JVM-specific cruft in the core kyo-jsonrpc surface; mirrors the kyo-jsonrpc-http pattern (`A4-naming-and-nesting.md:151-153`).

Cons: the kyo-jsonrpc-http split exists because HTTP is a heavyweight dependency; UDS is not. UDS is two files (`JsonRpcTransportJvm.scala` 47 lines and `UdsWireTransport.scala` 57 lines). Splitting a 100-line feature into its own sbt module is overkill and contradicts kyo-http's pattern, which keeps UDS inside `kyo-http` itself (no `kyo-http-uds`). Module proliferation costs more than it saves at this scale.

---

## 4. kyo-net relevance

The memory note `feedback_kyo_net_extraction` and `C-cleanup-plan.md` §10 mention a hypothetical `kyo-net` extraction that would unify byte-level transport primitives across kyo-http and kyo-jsonrpc. If that extraction happens, the kyo-http `NioTransport.connectUnix` / `listenUnix` / `kyo_tcp.c` UDS code (`NioTransport.scala:725, :751`; `kyo_tcp.c:179, 226`; `JsTransport.scala:165, 198`) becomes shared infrastructure, and `kyo-jsonrpc`'s UDS implementation collapses to a thin `JsonRpcTransport.fromWire(kyoNet.uds(path), framer, codec)` call.

That eventual collapse strengthens Option B (fold) and weakens Option C (split-into-subproject). If we split into `kyo-jsonrpc-uds` now, we then merge it back when `kyo-net` lands, doing the same work twice. If we fold now, the public surface (`JsonRpcTransport.unixDomain`) survives the kyo-net refactor untouched; only the internal backend changes.

`Framer` and `WireTransport` also stay in `kyo-jsonrpc` for the same reason: kyo-net is hypothetical, the current module boundary is authoritative. UDS is no different.

---

## 5. VERDICT

**FOLD-INTO-MULTI-PLATFORM (Option B). DEFER the Native and JS shim implementation to a separate task; in THIS campaign, only relocate the existing JVM impl into the multi-platform shape.**

Concretely: this campaign relocates `JsonRpcTransportJvm.unixDomain` into `JsonRpcTransport.unixDomain` (shared companion), with the JVM `UdsBackend` implementation living at `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala`. The JS and Native source trees get matching `UdsBackend.scala` files whose bodies `Abort.fail(new UnsupportedOperationException("UDS not yet implemented on this platform"))`. A follow-up task implements the Native and JS bodies properly (lifting from kyo-http's `kyo_tcp.c` and `JsTransport`).

Rationale: kyo-http already proves the multi-platform UDS pattern works on every supported platform (JVM via `UnixDomainSocketAddress`, Native via `kyo_tcp.c` AF_UNIX bindings, JS via Node `net.Socket({ path })`). The kyo-jsonrpc `*Jvm` suffix is the single outlier among 17 public top-level types and breaks the explicit "zero platform-specific public types" rule documented at `A1-kyo-http-template.md:383-385`. The relocation work itself is mechanical: move two files, add three single-file stubs, leave the user-facing call site `JsonRpcTransport.unixDomain(sockPath)` byte-identical to today. The Native and JS implementations can borrow heavily from kyo-http (`NioTransport.connectUnix` at `NioTransport.scala:725`, `kyo_tcp.c:179`, `JsTransport.connectUnix` at `JsTransport.scala:165`) once kyo-net extracts them, or sooner if a consumer needs it. Keeping the public name unified now avoids a second migration when kyo-net arrives. Option A's "leave it" preserves a known-ugly outlier that we know we will fix; Option C's "split module" doubles the work versus the inevitable kyo-net refactor. Option B is the only choice that aligns with the template AND doesn't waste work later.

### Concrete actions for THIS campaign

Files to add / modify:

1. **`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`**: add `def unixDomain` to the companion:

```scala
def unixDomain(
    sockPath: java.nio.file.Path,
    framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope) =
    internal.transport.UdsBackend.open(sockPath).map { wire =>
        fromWire(wire, framer, codec)
    }
```

(Note: `Framer` already gets prefix-renamed to `JsonRpcFramer` in Fork 2 / Phase 5 of `C-cleanup-plan.md`.)

2. **`kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new): JVM implementation. Body lifted verbatim from `JsonRpcTransportJvm.scala:21-33`:

```scala
package kyo.internal.transport
import kyo.*
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.nio.file.{Files, Path}

private[kyo] object UdsBackend:
    def open(sockPath: Path)(using Frame): JsonRpcWireTransport < (Async & Scope) =
        Sync.defer {
            val addr    = UnixDomainSocketAddress.of(sockPath)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(addr)
            Scope.ensure(Sync.defer {
                channel.close()
                Files.deleteIfExists(sockPath)
                ()
            }).andThen(Sync.defer(new UdsWireTransport(channel)))
        }
end UdsBackend
```

3. **`kyo-jsonrpc/jvm/src/main/scala/kyo/internal/transport/UdsWireTransport.scala`**: move from `kyo-jsonrpc/jvm/src/main/scala/kyo/internal/UdsWireTransport.scala` (already on the Phase 1 reorg list at `C-cleanup-plan.md:339`); no body change.

4. **`kyo-jsonrpc/native/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new): stub.

```scala
package kyo.internal.transport
import kyo.*
import java.nio.file.Path
private[kyo] object UdsBackend:
    def open(sockPath: Path)(using Frame): JsonRpcWireTransport < (Async & Scope) =
        Abort.fail(new UnsupportedOperationException(
            s"JsonRpcTransport.unixDomain: not yet implemented on Scala Native (sockPath=$sockPath)"
        ))
end UdsBackend
```

5. **`kyo-jsonrpc/js/src/main/scala/kyo/internal/transport/UdsBackend.scala`** (new): same stub shape as Native, swapping the platform name in the message.

6. **`kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala`**: DELETE.

7. **`kyo-jsonrpc/jvm/src/test/scala/kyo/JsonRpcTransportJvmTest.scala`**: rename to `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala` and gate body on JVM via the same mechanism kyo-http uses (`HttpServerUnixTest.scala` runs cross-platform; the test calls `cancel` on JS/Native when `UdsBackend.open` returns `Abort.fail`). Until Native/JS impls land, the test catches the `UnsupportedOperationException` and cancels, matching `kyo-http/shared/src/test/scala/kyo/HttpServerUnixTest.scala:17` style.

Total diff: 1 file delete, 1 file move (UdsWireTransport into `transport/` subpackage, already planned), 1 stub addition each for js/native, 1 test relocation. ~150 lines net.

### Phase placement

Add as new Phase 9 in `C-cleanup-plan.md` §12, executed after Phase 8 ("Final cross-platform green run"). The fold is independent of the renaming and config-alignment phases; it touches only the JVM `JsonRpcTransportJvm.scala` file and adds platform stubs. Bundle with Phase 5 (which renames `Framer` to `JsonRpcFramer`) so the signature in the new shared `JsonRpcTransport.unixDomain` already uses the renamed parameter type.

### Native and JS implementations: separate follow-up task

Implementing real UDS on Native and JS is a separate task tracked outside this cleanup campaign. The blocking work is: extract or duplicate kyo-http's UDS FFI bindings (`kyo-http/native/src/main/resources/scala-native/kyo_tcp.c:179, 226` for the C side; `NativeTransport.connectUnix` / `listenUnix` at `NativeTransport.scala:751, 773` for the Scala side; `JsTransport.connectUnix` / `listenUnix` at `JsTransport.scala:165, 198` for JS). The cleanest path is to wait for `kyo-net` extraction (hypothetical, see `A4 §12 medium 8`) and consume the unified UDS primitive. If a consumer needs Native/JS UDS before then, the stubs are the only thing blocking it; replacing each stub is bounded copy-work from kyo-http.

---

## 6. Recommendation

**Fold `JsonRpcTransportJvm.unixDomain` into `JsonRpcTransport.unixDomain` as a multi-platform method this campaign, with JVM-real and Native/JS-Abort-stub backends. Implementing real Native and JS UDS is deferred to a follow-up task that can borrow from kyo-http's existing UDS code or consume the future `kyo-net` extraction.**
