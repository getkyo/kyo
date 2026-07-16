# Contributing to kyo-data

The root [CONTRIBUTING.md](../CONTRIBUTING.md) is the comprehensive reference for conventions,
patterns, and design decisions across the codebase. It governs here too: naming, scaladoc, the
`using` clause order, the inline guidelines, and the Kyo primitives mandate are all defined there
and are not restated in this file.

This file carries only what is true of kyo-data and is not derivable from the root guide.

## What kyo-data is

kyo-data is the bottom of the stack. It depends on `kyo-stats-registry` and nothing else
[build.sbt:589], while `kyo-kernel` depends on it [build.sbt:606] and `kyo-prelude` builds on
`kyo-kernel` in turn [build.sbt:625]. There is no effect system here: no `Sync`, no `Async`, no
`Abort`; those names do not appear anywhere in the module's main sources. The
module supplies the data types those effects are written in terms of (`Maybe`, `Result`, `Chunk`,
`Span`, `Dict`, `OrderedMap`, `Duration`, `Instant`, `TypeMap`, `Tag`, `Record`, ...).

A consequence worth internalizing: a change here is a change under every other module. Adding a
dependency to kyo-data is a stack-wide decision, not a module-local one.

### Four platforms, not three

kyo-data cross-builds for JS, JVM, Native, and Wasm [build.sbt:587]. The root guide's
cross-platform rule mentions JVM, JS, and Native; in this module Wasm is a fourth target that a
change must also satisfy.

The public data types live in `shared/src/main/scala/kyo/` and are shared across all four
platforms. Per-platform sources exist, but they are confined to `kyo/internal/`: the queue
implementations and `UnsafeBuffer` / `Platform` bridges under `jvm/`, `js/`, `native/`, `wasm/`,
and the shared-by-pairs `jvm-native/` and `js-wasm/` directories. Adding a per-platform source
outside `kyo/internal/` is not an established pattern here.

### The module compiles at `-release 25`

kyo-data is a foreign-API module: it and `kyo-ffi`, `kyo-offheap`, and `kyo-tasty` compile at
`-release 25` [build.sbt:594, build.sbt:112-115] while the rest of the build stays at
`-release 17` [build.sbt:172-173]. Java APIs newer than 17 are available in this module and are
not available to most callers of it.

## The headline invariant: a dual representation that switches at 8 entries

`Dict` and `OrderedMap` are opaque types whose runtime representation is one of two things,
chosen by size:

```scala
opaque type Dict[K, V]       = Span[K | V] | HashMap[K, V]        // Dict.scala:66
opaque type OrderedMap[K, V] = Span[K | V] | TreeSeqMap[K, V]     // OrderedMap.scala:70
```

For up to 8 entries, keys and values live in a flat `Span` and lookup is a linear scan with no
hashing. Above 8, the map promotes to a `HashMap` (`Dict`) or a `TreeSeqMap` ordered by insertion
(`OrderedMap`) [kyo-data/shared/src/main/scala/kyo/Dict.scala:8-12,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:7-13]. The threshold is a single named
constant in each companion, not a literal at use sites
[kyo-data/shared/src/main/scala/kyo/Dict.scala:70,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:74]:

```scala
private[kyo] val threshold = 8
```

`Dict.scala` and `OrderedMap.scala` are structurally parallel. When you change one, read the
other: the same operation usually exists in both with the same shape, and a fix to a scan or a
promotion boundary in one is usually owed to the other.

### The small representation is keys-first, not interleaved

In the `Span` representation of size `n`, keys occupy indices `[0, n)` and values occupy
`[n, 2n)`. A value is read at `n + i`, never at `2i + 1`
[kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:150,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:175,
kyo-data/shared/src/main/scala/kyo/Dict.scala:149,
kyo-data/shared/src/main/scala/kyo/Dict.scala:174]. Insertion writes the value at `n + idx`
[kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:214], and removal closes the gap with an
`arraycopy` over the value half [kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:252].

### `reduce` is how an operation dispatches on the representation

Neither type branches on `size`. Each has one private inline dispatcher that tests the runtime
class and hands the caller the representation it matched
[kyo-data/shared/src/main/scala/kyo/Dict.scala:612-617,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:621-626]:

```scala
private inline def reduce[B](
    inline small: Span[K | V] => B,
    inline large: TreeSeqMap[K, V] => B
): B =
    if self.isInstanceOf[TreeSeqMap[?, ?]] then large(self.asInstanceOf[TreeSeqMap[K, V]])
    else small(self.asInstanceOf[Span[K | V]])
```

### Adding an operation to `Dict` or `OrderedMap`

- Write it as an extension method that goes through `reduce`, supplying both arms. An operation
  that handles only the small arm is a bug that appears at the 9th entry.
- Respect the threshold in both directions. A result of size `<= threshold` should be in the
  small representation and a result above it in the large one; the builders make this decision by
  comparing against the named constant rather than a literal
  [kyo-data/shared/src/main/scala/kyo/OrderedMapBuilder.scala:60-64].
- Keep the tuple-free shape. Iteration, transformation, and lookup take separate key and value
  parameters rather than `(K, V)`, to avoid tuple allocation
  [kyo-data/shared/src/main/scala/kyo/Dict.scala:17-18,
  kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:20-21]. A new method that takes or returns
  `(K, V)` in a hot path is against the grain of both types.
- Test both representations. See the order-test trap below.

## The opaque containers have no `CanEqual`: use `is`, not `==`

`Dict` and `OrderedMap` deliberately provide no `CanEqual` instance. Structural equality is the
`is` method [kyo-data/shared/src/main/scala/kyo/Dict.scala:55,
kyo-data/shared/src/main/scala/kyo/Dict.scala:586,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:58,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:595]:

```scala
def is(other: Dict[K, V])(using CanEqual[K, K], CanEqual[V, V]): Boolean
```

The build does not enable `strictEquality`, so the absence of a `CanEqual` instance does not make
`==` a compile error. `==` compiles and silently compares the erased representations. In the small
representation that is `Array` reference equality, since `Span` is an opaque alias for `Array`
[kyo-data/shared/src/main/scala/kyo/Span.scala:36], so two maps holding identical entries are not
`==`. Across the threshold it is always false: a `Span`-backed map is never `==` to a map-backed
one. Reach for `is` in tests as much as in source. `Span` follows the same convention and has its
own `is` [kyo-data/shared/src/main/scala/kyo/Span.scala:420].

`Record` is the counter-example, and it is deliberate: `Record` `==` requires
`Fields.Comparable[F]` evidence that every field type has `CanEqual`, so a record with a
non-comparable value type fails to compile rather than comparing silently
[kyo-data/README.md:491].

## `OrderedMap` order is a contract

The order semantics are specified, not incidental to the backing collection
[kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:15-18]:

- Order is insertion order at every size.
- A new key appends at the end.
- Updating an existing key replaces its value and keeps the key at its existing position.
- Removing a key preserves the relative order of the remaining keys.
- Re-adding a previously removed key appends it at the end as a fresh insertion.

`Dict` makes no such promise: its hash-backed large representation leaves iteration order
unspecified [kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:17-18]. That difference is the
reason both types exist. A change to `OrderedMap` that preserves entries but not their order is a
breaking change even though every `is` comparison still passes.

### `is` does not compare order

`OrderedMap.is` is order-independent by specification: it checks size and then per-key value
equality [kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:592-600]. So `is` cannot witness the
contract above. An assertion about order must go through an order-bearing projection: `toChunk`,
`keys`, or `values` [kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:236,
kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:291]. `foreach`, `foreachKey`, and
`foreachValue` likewise traverse in insertion order
[kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:275,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:289,
kyo-data/shared/src/main/scala/kyo/OrderedMap.scala:303].

## Builders pool their buffers in a `ThreadLocal`

`ChunkBuilder`, `DictBuilder`, and `OrderedMapBuilder` each cache their scratch buffer in a
`ThreadLocal` `ArrayDeque` and return it on `result()`
[kyo-data/shared/src/main/scala/kyo/ChunkBuilder.scala:66,
kyo-data/shared/src/main/scala/kyo/DictBuilder.scala:73,
kyo-data/shared/src/main/scala/kyo/OrderedMapBuilder.scala:77]:

```scala
private val bufferCache =
    new ThreadLocal[ArrayDeque[ArrayList[?]]]:
        override def initialValue() = new ArrayDeque[ArrayList[?]]
```

This is the module's pooling idiom and a new builder should follow it rather than invent another.
Three properties are load-bearing:

- It lives in `shared/src/main`, not in a per-platform source. `ThreadLocal` is available on all
  four targets, so the idiom does not need a platform split.
- The buffer is cleared on release, not on acquire
  [kyo-data/shared/src/main/scala/kyo/OrderedMapBuilder.scala:83-85]. A builder that releases a
  buffer it still references hands live data to the next acquirer.
- `result()` releases the buffer and drops the reference
  [kyo-data/shared/src/main/scala/kyo/OrderedMapBuilder.scala:67-69]. A builder is not reusable
  after `result()`, and a new builder method must preserve that.

## Tests

Tests extend the module's test base, `kyo.test.Test[Any]`
[kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:3]. Tests for the public data types live
in `shared/src/test` and must pass on all four platforms. The one test outside it covers a
JVM-only internal bridge [kyo-data/jvm/src/test/scala/kyo/internal/UnsafeBufferTest.scala], which
is the only reason to put a test in a platform tree here.

### The order-test trap

`scala.collection.immutable.Map` preserves insertion order at sizes 1 to 4, where it is backed by
`Map1` through `Map4`, and only hash-orders at 5 entries and above. Combined with the dual
representation, this makes it easy to write an order assertion that cannot fail:

- A fixture of 4 or fewer entries passes whether or not order is preserved, because an unordered
  `Map` would preserve it at that size anyway.
- A fixture whose insertion order equals the sorted order of its keys passes whether the map
  preserves insertion order or sorts. Keys `0` to `9` inserted in ascending order are the worst
  case: that sequence is at once the insertion order, the sorted order, and the hash iteration
  order.

Two rules follow, and the existing order tests are written to them:

1. **Insert each fixture in an order that differs from both the sorted order and the hash order of
   its keys**, so losing the order fails the assertion. The large-path fixture inserts
   `Seq(3, 1, 4, 0, 9, 2, 6, 5, 8, 7)` [kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:5],
   which is above the promotion threshold and matches neither sorted nor hash order.
2. **Let the assertion follow the fixture rather than repeat it**, so the two cannot drift apart:
   assert against `Chunk.from(largeMapInsertOrder)`, not against a copied literal
   [kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:236,
   kyo-data/shared/src/test/scala/kyo/OrderedMapTest.scala:239].

The same reasoning applies to size assertions. `assert(m.size <= 8)` is satisfied by any behavior
the fixture can produce; assert the exact size the test's own data implies
[kyo-data/shared/src/test/scala/kyo/OrderedMapBuilderTest.scala:72].

### Cover both representations

A test that exercises only a fixture of 8 or fewer entries has not tested the type. Any operation
on `Dict` or `OrderedMap` needs coverage at both sides of the threshold, and the promotion and
demotion boundaries are where the bugs are
[kyo-data/shared/src/test/scala/kyo/OrderedMapBuilderTest.scala:63-75].

### README doctests

The module README (`kyo-data/README.md`) is doctest-validated: every fenced `scala` block must
compile and produce the expected output, with `import kyo.*` as the predef [build.sbt:144].
`CONTRIBUTING.md` (this file) is not doctest-validated.

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on the JVM
sbt 'kyo-dataJVM/test'

# A single test class
sbt 'kyo-dataJVM/testOnly kyo.OrderedMapTest'

# The other three platforms
sbt 'kyo-dataJS/test'
sbt 'kyo-dataNative/test'
sbt 'kyo-dataWasm/test'
```

The JVM build runs MiMa against the previous stable artifact, reporting binary-compatibility
problems without failing the build [build.sbt:598, build.sbt:2159-2164]. A report is a signal to
check that a public-surface change is intended, not noise to skip past.

## Pre-submission checklist (kyo-data-specific)

- [ ] A new `Dict` / `OrderedMap` operation dispatches through `reduce` and handles both arms.
- [ ] Both sides of the 8-entry threshold are tested, including the promotion and demotion
      boundaries.
- [ ] An order assertion uses an order-bearing projection (`toChunk`, `keys`, `values`), never
      `is`, which is order-independent.
- [ ] An order-test fixture's insertion order differs from both the sorted order and the hash
      order of its keys, and the assertion derives from the fixture.
- [ ] No `==` on `Dict`, `OrderedMap`, or `Span`; use `is`.
- [ ] A structural change to `Dict.scala` or `OrderedMap.scala` was considered for its parallel.
- [ ] A new builder pools its buffer with the `ThreadLocal` idiom, clears on release, and drops
      the buffer in `result()`.
- [ ] The change compiles and tests pass on all four platforms, not only the JVM.
- [ ] No new dependency was added to the module without weighing the stack-wide cost.
