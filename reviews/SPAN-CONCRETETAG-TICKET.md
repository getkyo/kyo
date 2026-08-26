# Span: allocation-free empty spans and ConcreteTag element evidence

## Motivation

Allocation profiling of the http benchmarks (kqueue transport, JMH with async-profiler
alloc recordings) surfaced Span's element-evidence machinery on the kernel's hot paths:

- `Span.empty` allocates for every non-primitive element type. Its empty-array cache
  covers only the eight primitive ClassTags, so reference element types fall through to
  `Array.empty`, allocating a fresh zero-length array per call. In the http client
  recording this shows as `ManifestFactory$ObjectManifest.newArray` stacks under
  `Span$.empty`, about 2% of sampled allocation.
- `scala.Tuple2` was 2% of sampled allocation in the http server contention bench, and
  81% of that weight was empty `(Span.empty, Span.empty)` pairs built once per fork
  crossing in the kernel (`Stack.bindings` and the `Contextual` isolate's capture). Those
  two sites are now spot-fixed with cached vals, but the kernel has six more per-crossing
  `Span.empty` sites (the `Contextual` anons' `def updates`, the empty cases of `carried`
  and `cross`, the `Park` constructor) that only a Span-level fix makes allocation-free.

## Current state

`Span.empty` (kyo-data/shared/src/main/scala/kyo/Span.scala):

```scala
def empty[A: ClassTag as ct]: Span[A] =
    if cachedEmpty.contains(ct) then
        cachedEmpty(ct).asInstanceOf[Array[A]]
    else
        Array.empty
```

`cachedEmpty` is an eight-entry `Map[ClassTag[?], Array[?]]` holding only the primitive
tags, and the hit path pays two hash lookups (`contains` then `apply`). Any reference
element type misses and allocates via `ct.newArray(0)`.

Beyond `empty`, about 45 public operations take `using ClassTag[A]` (or `ClassTag[B]`
for the mapping ones) and allocate internally with `new Array[A](n)`.

## Proposal

### 1. Allocation-free `Span.empty` for every element type

Replace the eight-entry map with a per-component-class empty-array cache:

- primitives: a direct switch returning the canonical empties (`Array.emptyIntArray`
  and friends), no map lookup
- reference types: a cache keyed on the component `Class[?]` with allocation-free hits.
  `ClassValue` is JVM-only, so the shared implementation is likely a `ConcurrentHashMap`
  with a shared compute function (`Ffi.load` in kyo-ffi sets the precedent); JS can use
  a plain map, Native needs the concurrent variant.

Correctness constraint: the cached empty for component class `C` must have runtime class
`Array[C]`, never a shared `Array[AnyRef]`. The array class is observable through
`toArrayUnsafe` and through growth (`Arrays.copyOf` of an `Array[Object]` yields
`Array[Object]`, silently dropping store-check semantics for the typed result).

### 2. Migrate element evidence from ClassTag to ConcreteTag

Why: the summon is a compile-time constant (no scala-reflect Manifest machinery on any
call path), it is the evidence type the rest of kyo is converging on (kyo-ffi's `Buffer`
and `Ffi.load` already moved), the array APIs already exist (`newArray`, `copyOf`,
`fromClass`, `fromArray`), and it handles unions, intersections, `AnyVal`, and `Nothing`
properly instead of collapsing to `Object`.

Prerequisite, and the real work in this item: `ConcreteTagMacro` hard-errors on applied
types (`tpe.typeArgs.nonEmpty` aborts with "has type parameters"). Span is routinely
instantiated at applied element types (`Span[Maybe[Any]]`, `Span[Binding[?, ?, ?, ?]]`,
tuples), so the migration needs an erasure arm: for an applied type, derive
`fromClass(<erased class>)`. That makes `accepts` erasure-based for applied types, which
is exactly `ClassTag`/`isInstanceOf` semantics; the current refusal exists to keep type
checks honest, so lifting it is an explicit design decision (either accept erasure
semantics for applied types, or keep `accepts` strict and route only array allocation
through the erased class).

Compatibility: source-compatible at concrete call sites (the given re-summons), breaking
for code that abstracts over `ClassTag[A]` (it must carry `ConcreteTag[A]` instead).

### 3. Drop evidence entirely from same-type derived operations

Most operations that require `ClassTag[A]` today only copy out of an existing array
whose runtime class already carries the component type: `slice`, `take`, `drop`,
`takeWhile`, `dropWhile`, `takeRight`, `dropRight`, `reverse`, `update`, `updated`,
`splitAt`, `span`, `partition`, `filter`, `filterNot`, `distinct`, `distinctBy`,
`sliding`, `scan`, `padTo`, `append`, `prepend`, `:+`, `+:`, `concat`, `++`, `toArray`,
`tail`. These can allocate from the source array (a `newArrayLike(src, n)` helper
reading `getClass.getComponentType`, with a primitive switch to avoid reflective
`Array.newInstance` on primitive spans) and stop demanding evidence altogether.

Evidence then remains only where a Span is created from nothing (`empty`, `apply`,
`fill`, `tabulate`, `iterate`, `from`) or where the element type changes (`map`,
`flatMap`, `collect`, `scanLeft`, `scanRight`, `flatten`).

This shrinks the public surface, removes a whole class of summons from user code, and
makes the empty-result branches of these operations (`filter` matching nothing, `slice`
of length 0) hit the per-class empty cache with no evidence in scope.

## Acceptance

- `Span.empty[A]` is allocation-free for every `A` on JVM, JS, and Native (pinned by an
  allocation test on the JVM, behavior tests elsewhere)
- runtime array class preserved: `Span.empty[String].toArray.getClass` is
  `Array[String]`, and growth from a cached empty produces correctly typed arrays
- kyo-data and kyo-kernel suites green on all platforms
- http bench `gc.alloc.rate.norm` does not regress on any of the four kyo rows, and the
  kernel's remaining `Span.empty` sites become cache hits with no further callsite
  caching
- KernelBench parity (the evidence change touches inline expansion sites)

## Non-goals

- `Chunk` (List-backed, takes no element evidence)
- boxing of primitive spans in non-inlined polymorphic contexts: the operations are
  `inline def`s, so concrete call sites already specialize; the polymorphic case is
  inherent to erased generics and unrelated to the evidence type
