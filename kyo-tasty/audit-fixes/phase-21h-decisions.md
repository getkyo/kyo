# Phase 21h Decisions

## API Discovered

`PlatformHashingState` (`private[type_]` in `kyo.internal.tasty.type_`) is NOT a byte-array hasher.
It exposes a single method:

```scala
def get(): mutable.HashSet[kyo.Tasty.Type]
```

The returned set is the per-call cycle-detection guard used by `TypeKey.computeHash`. Before hashing
a type, `computeHash` checks whether the type is already in the set (cycle detected), adds it, calls
`hashOf`, then removes it in a `finally` block. Platform variants differ only in storage strategy:

- JVM: `ThreadLocal[mutable.HashSet[Tasty.Type]]` (one set per thread)
- JS, Native: object-level `var` (single-threaded)

The set is never intended to hold state across calls; it is ephemeral per hash-computation chain.

## Hashing Algorithm

The type-key hashing algorithm is prime-multiplication mixing (`31 * sub-hash + tag-constant`), the
same shape as Java's `Object.hashCode` conventions. It is entirely in `TypeKey.hashOf`. The result
is a Scala `Int`.

FNV-1a 64-bit is used only by `DigestComputer` for classpath cache invalidation and produces a
`Long`. The plan description referencing FNV-1a and a Long golden value applied to the wrong
component.

## Golden-Value Capture Method

Hardcoding a specific integer constant was avoided for two reasons:
1. `TypeKey.hash` is an `Int` derived from Scala case-class `hashCode` mixing, which is
   deterministic but not trivially hand-computable without running the JVM.
2. The plan itself anticipated this: "if unclear, write the test to compute hash twice and assert
   equality."

The stability test calls `TypeKey.of(ConstantType(IntConst(42))).hash` twice and asserts the two
calls return equal values. This exercises `PlatformHashingState.get()` on both invocations and
confirms the state does not corrupt the result between calls.

The discrimination test uses `ConstantType(IntConst(1))` vs `ConstantType(IntConst(2))`. Both map
to their `hashCode` via `hashOf`, which for distinct `IntConst` values produces distinct results.

## Test Count

Two tests were written (plan YAML records 1; the task instructions require 2):
1. Stability: same type hashes to the same value on repeated calls.
2. Discrimination: two structurally different types hash to different values.
