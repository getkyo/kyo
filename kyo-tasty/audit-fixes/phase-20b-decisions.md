# Phase 20b Decisions

## BitStream field visibility: private[scala2] -> private[kyo]

The plan specifies `private[scala2]` for `BitStream`. However, the matching test lives in
`package kyo` (file `kyo/PortableInflateTest.scala`), which is outside the
`kyo.internal.tasty.scala2` package subtree. `private[scala2]` blocks access from `package kyo`.

Decision: widen to `private[kyo]`. This keeps `BitStream` inaccessible from user-facing code
outside the `kyo` root package while allowing the shared test suite to construct instances
directly. Subsequent phases (20c-20f) also live within `kyo.internal.tasty.scala2` so they
continue to have full access.

## var bitOffset rationale

`var bitOffset: Long` is a mutable constructor field (not a default-param). `BitStream` is a
single-threaded, per-call instance: each inflate invocation constructs a fresh `BitStream` and
drives it to completion on one thread. Internal mutation via `bitOffset` is therefore safe and
idiomatic; a bare `var` in a shared (multi-threaded) context would be forbidden, but this is
single-threaded by construction.

## readBytes: alignToByte() return value

The plan's pseudocode calls `alignToByte()` and then recomputes `(bitOffset >> 3).toInt` for the
start index. The compiler (with `-Wvalue-discard`) rejects a discarded `Int` return. Fix: capture
the return of `alignToByte()` directly as `val start`, eliminating the redundant recomputation.
No behaviour change.

## No deviations from plan pseudocode logic

All bit-manipulation expressions (`>> 3`, `& 7`, `& 0xff`, `& 1`, `<< i`) match the plan
verbatim and satisfy the LSB-first verification table in the task description.
