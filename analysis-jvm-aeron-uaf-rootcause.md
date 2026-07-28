# JVM kyo-aeron use-after-free: root cause

## Symptom
`AeronTransportTest "UAF-saturated"` crashes the JVM with a native memory fault during a
publish `offer`. Reproduced with an identical stack on **Windows CI** (`EXCEPTION_ACCESS_VIOLATION`
in `~StubRoutines::jint_disjoint_arraycopy`) and **locally on macOS** (`SIGSEGV` in
`org.agrona.concurrent.UnsafeBuffer.putLongRelease`). Same call chain on both:

```
kyo.internal.JvmAeronTransport.offer
  io.aeron.Publication.offer
    io.aeron.ConcurrentPublication.offer
      io.aeron.ConcurrentPublication.appendFragmentedMessage
        io.aeron.logbuffer.HeaderWriter.write
          org.agrona.concurrent.UnsafeBuffer.putLong* -> write to UNMAPPED term buffer
```

So this is **not Windows-specific**; it is a cross-platform race in `JvmAeronTransport`.
Windows CI merely surfaced it first. It reproduces under the parallel full suite (parallelism 24),
not in the isolated test: contention widens the window (the first published chunk is a ~4.2 MB
MsgPack `Envelope` of `UafSaturatedMsg`, so `publication.offer` spends real time fragmenting it into
the term buffer exactly when the client close lands).

## The race
Close path (`kyo-aeron/jvm/src/main/scala/kyo/internal/AeronPlatformTransport.scala`):

```
runtime.close() = jvmTransport.closeAll(); aeron.close(); driver.close()
```

`closeAll` (`JvmAeronTransport.scala:266`) drains handles by iterating a `ConcurrentHashMap` keyset:

```
livePubs.forEach(pub => closePublication(pub))   // each: pub.gate.close() drains in-flight offer
liveSubs.forEach(sub => closeSubscription(sub))
```

`closePublication` (`JvmAeronTransport.scala:201`) seals+drains that pub's `OpsGate`, then closes it.
The design intent (`JvmAeronTransport.scala:42-52`): after `closeAll`, no offer/poll is in flight,
so `aeron.close()` can unmap term buffers safely.

The hole: publications are **added** to `livePubs` inside `pollAddPublication`
(`JvmAeronTransport.scala:127-128`), which runs concurrently on a publish fiber. `ConcurrentHashMap.forEach`
is only weakly consistent, so a publication that becomes fully live (obtained from `getPublication`,
returned to `Topic.publish`, and already inside `publication.offer`) can be **missed** by
`closeAll`'s iteration when its `livePubs.add` races the `forEach`. A missed publication's `OpsGate`
is never sealed, so `closeAll` returns believing the drain is complete, and `aeron.close()` /
`driver.close()` unmap the term buffer while that publication's `offer` is still writing into it.

The per-handle `OpsGate` is correct in isolation (double-checked enter, drain-on-close). What is wrong
is the **granularity**: correctness depends on the drain covering every live handle, but the set it
iterates can miss a concurrently-registered handle. The gate protects `offer` against
`closePublication` for a handle the drain sees; it does nothing for a handle the drain never sees.

## Why the C shim does not have this bug
`kyo-aeron/shared/src/main/c/kyo_aeron.c` gates **all** operations through one client-level
`close_mutex` + a `closing` flag + refcounted bundles. There is no per-handle set that an iteration
can miss: an offer/poll and a close are mutually exclusive at the client level, and any operation that
starts after `closing` is set bails before touching mapped memory. `FfiAeronTransport` (used by
Native/JS today) inherits that model.

## Fix shape (if kept in-place)
Mirror the C shim's global barrier on the JVM: a single transport-level (client-level) gate that
**every** `offer`/`poll`/connectivity/maxLen call enters, sealed and drained by `close()` before
`aeron.close()`/`driver.close()`. Then a handle that races registration is irrelevant: its `offer`
enters the transport gate, which is either open (drain will wait for it) or sealed (offer bails). The
existing per-handle `OpsGate` becomes redundant with, or subordinate to, the transport-level gate.

This is the same correctness model the C shim already implements, which is also the argument for the
alternative direction: consolidate the JVM onto `FfiAeronTransport` + the C shim so there is one
teardown model to prove. That trade is analyzed separately in `analysis-aeron-single-impl.md`.

## Status
- Root cause: identified and evidenced (dual-platform identical stack + code trace).
- Reproduction: full `kyo-aeronJVM/testOnly kyo.AeronTransportTest` under default parallelism; the
  isolated `--filter=**UAF-saturated**` run does not reproduce (needs contention).
- Fix: pending the keep-in-place vs consolidate decision.
