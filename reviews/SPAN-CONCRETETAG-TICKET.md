# Span: allocation-free empty spans and ConcreteTag element evidence

## Motivation

`Span.empty` allocates on every call for any non-primitive element type. Allocation profiling of http benchmark workloads surfaced it as a measurable allocation source (`ManifestFactory$ObjectManifest.newArray` stacks under `Span$.empty`), and the cause is fully visible in the implementation: the empty-array cache covers only the eight primitive ClassTags, so every reference element type falls through to `Array.empty` and allocates a fresh zero-length array. Code that builds empty spans on hot paths pays an allocation per call for a value that is a constant.

The same audit showed that Span's element evidence is broader than it needs to be: about 45 public operations demand `using ClassTag[A]`, but most of them only copy out of an existing array whose runtime class already carries the component type, so the evidence is pure call-site noise.

## Current state

`Span.empty` (kyo-data/shared/src/main/scala/kyo/Span.scala):

```scala
def empty[A: ClassTag as ct]: Span[A] =
    if cachedEmpty.contains(ct) then
        cachedEmpty(ct).asInstanceOf[Array[A]]
    else
        Array.empty
```

`cachedEmpty` is an eight-entry `Map[ClassTag[?], Array[?]]` holding only the primitive tags, and even the hit path pays two hash lookups (`contains` then `apply`). Any reference element type misses and allocates via `ct.newArray(0)`.

Beyond `empty`, the public operations take `using ClassTag[A]` (or `ClassTag[B]` for the mapping ones) and allocate internally with `new Array[A](n)`.

## Proposal

### 1. Allocation-free `Span.empty` for every element type

Replace the eight-entry map with a per-component-class empty-array cache:

- primitives: a direct switch returning the canonical empties (`Array.emptyIntArray` and friends), no map lookup
- reference types: a cache keyed on the component `Class[?]` with allocation-free hits. `ClassValue` is JVM-only, so the shared implementation is likely a `ConcurrentHashMap` with a shared compute function; JS can use a plain map, Native needs the concurrent variant.

Correctness constraint: the cached empty for component class `C` must have runtime class `Array[C]`, never a shared `Array[AnyRef]`. The array class is observable through `toArrayUnsafe` and through growth (`Arrays.copyOf` of an `Array[Object]` yields `Array[Object]`, silently dropping store-check semantics for the typed result).

### 2. Migrate element evidence from ClassTag to ConcreteTag

Why: the summon is a compile-time constant (no scala-reflect Manifest machinery on any call path), it keeps kyo on a single evidence type instead of mixing ClassTag in, the array APIs already exist on ConcreteTag (`newArray`, `copyOf`, `fromClass`, `fromArray`), and it handles unions, intersections, `AnyVal`, and `Nothing` properly instead of collapsing to `Object`.

Prerequisite, and the real work in this item: `ConcreteTagMacro` refuses exactly the element types Span is routinely used with. Applied types hard-error on `tpe.typeArgs.nonEmpty` ("has type parameters"), and opaque or abstract types fail the earlier `isClassDef` check ("is not a class"), so `ConcreteTag[Maybe[Any]]` is refused twice over while `Span[Maybe[Any]]` is an ordinary element type. ClassTag accepts all of these by erasure, which is why Span works today. The work needs to consider opening ConcreteTag to types with type parameters that cannot be fully checked at runtime, or providing a less constrained version of it.

Compatibility: source-compatible at concrete call sites (the given re-summons), breaking for code that abstracts over `ClassTag[A]` (it must carry `ConcreteTag[A]` instead).

### 3. Drop evidence entirely from same-type derived operations

Most operations that require `ClassTag[A]` today only copy out of an existing array whose runtime class already carries the component type: `slice`, `take`, `drop`, `takeWhile`, `dropWhile`, `takeRight`, `dropRight`, `reverse`, `update`, `updated`, `splitAt`, `span`, `partition`, `filter`, `filterNot`, `distinct`, `distinctBy`, `sliding`, `scan`, `padTo`, `append`, `prepend`, `:+`, `+:`, `concat`, `++`, `toArray`, `tail`. These can allocate from the source array (a `newArrayLike(src, n)` helper reading `getClass.getComponentType`, with a primitive switch to avoid reflective `Array.newInstance` on primitive spans) and stop demanding evidence altogether.

Evidence then remains only where a Span is created from nothing (`empty`, `apply`, `fill`, `tabulate`, `iterate`, `from`) or where the element type changes (`map`, `flatMap`, `collect`, `scanLeft`, `scanRight`, `flatten`).

This shrinks the public surface, removes a whole class of summons from user code, and makes the empty-result branches of these operations (`filter` matching nothing, `slice` of length 0) hit the per-class empty cache with no evidence in scope.

## Acceptance

- `Span.empty[A]` is allocation-free for every `A` on JVM, JS, and Native (pinned by an allocation test on the JVM, behavior tests elsewhere)
- runtime array class preserved: `Span.empty[String].toArray.getClass` is `Array[String]`, and growth from a cached empty produces correctly typed arrays
- kyo-data suite green on all platforms
- benchmark allocation rates (`gc.alloc.rate.norm`) do not regress on Span-heavy rows, and the inline expansion sites are checked since the evidence change lands inside `inline def` bodies

## Non-goals

- `Chunk` (List-backed, takes no element evidence)
- boxing of primitive spans in non-inlined polymorphic contexts: the operations are `inline def`s, so concrete call sites already specialize; the polymorphic case is inherent to erased generics and unrelated to the evidence type
