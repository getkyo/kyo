# kyo-data

`kyo-data` is the foundational data layer that the rest of Kyo is built on. It supplies a small set of immutable, opaque-type-backed values that replace `Option`, `Either`, `Try`, `Map`, `IArray`, and `java.time` with versions that allocate less, encode richer information in types, and compose better across Kyo effects. The user's first call constructs one of these types directly (`Maybe(x)`, `Chunk.from(xs)`, `Result.succeed(v)`, `1.second`, `Dict("a" -> 1)`), so type names appear immediately in any code that uses the module.

Three patterns run through the design. First, opaque types over existing values: `Maybe` is `Absent | Present[A]` with no wrapper allocation, `Result` is `Success[A] | Error[E]` unboxed, `Dict` is a `Span` for small sizes and a `HashMap` above the threshold, `Duration` is a `Long`, `Instant` is a `java.time.Instant`. Second, type-level encoding of structure: `Record[F]` encodes its schema as an intersection of `Name ~ Value` pairs in `F`, `TypeMap[+A]` keys by type via `Tag[A]`, `ConcreteTag[A]` represents unions and intersections that `ClassTag` cannot. Third, kyo-package conventions the rest of the ecosystem inherits: `Frame` carries call-site position implicitly, `KyoException` renders with frame context, `Render[A]` is the printable-text type class, `Tag[A]` is the cross-platform runtime type identity used by effect handlers.

The module is cross-platform and ships identical public surface on JVM, Scala.js, and Scala Native.

```scala
import kyo.*
import java.lang.NumberFormatException

// Parse user input: typed expected failure, no exceptions leaking
val result: Result[NumberFormatException, Int] =
    Result.catching[NumberFormatException]("42".toInt)

val message: String = result match
    case Result.Success(n)  => s"parsed: $n"
    case Result.Failure(e)  => s"bad input: ${e.getMessage}"
    case Result.Panic(t)    => s"unexpected: ${t.getMessage}"
```

## Optional and fallible values

Code that loads, parses, or fetches things produces values that may be absent or may fail. The standard library spreads this across `Option`, `Either`, and `Try`. `kyo-data` replaces all three with two opaque types that allocate nothing for the happy path and distinguish expected failure from unexpected panic.

### Working with optional values

When a value may be present or absent, reach for `Maybe`. It is an opaque type over `Absent | Present[A]`, so `Maybe[A]` carries no wrapper allocation: a `Present(user)` is the `user` value itself at runtime, unboxed. Construction goes through `Maybe.apply` (null-aware), `Maybe.empty`, or pattern-matchable `Present`/`Absent`.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)

val some: Maybe[User] = Maybe(alice)
val none: Maybe[User] = Maybe.empty
val nullCheck: Maybe[String] = Maybe(null)  // Absent
```

> **Note:** `Maybe(v)` returns `Absent` whenever `v` is `null`. To wrap a value that may itself be `null` without that collapse, call `Present(v)` directly.

Transform with the same names you know from `Option`: `map`, `flatMap`, `filter`, `fold`, `getOrElse`, `orElse`, `exists`, `contains`. Conversion to other shapes goes through `toChunk`, `toList`, `toOption`, `toRight`, `toLeft`, `toResult`.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)
val found: Maybe[User] = Maybe(alice)

val emails: Maybe[String] = found.map(_.email)
val domain: Maybe[String] = found.flatMap(u => Maybe.when(u.email.contains("@"))(u.email.split("@")(1)))
val name: String          = found.fold("guest")(_.name)
val list: List[User]      = found.toList
```

Because `Maybe` is an opaque type that erases to the underlying value, `Maybe[Maybe[A]]` cannot be represented by collapse alone. `Present(Absent)` differs from `Absent`. The library represents that case via a small internal nesting wrapper so nesting roundtrips faithfully.

> **Note:** `value.show` for `Maybe` requires a `Render` instance for the inner type in scope; the underlying `toString` can produce wrong output. For example, `Present(Absent)` prints as `Absent` via `toString` but `Present(Absent)` via `Render`.

### Modeling success and failure

When code can succeed or fail, use `Result`. Like `Either`, `Result` carries an error type parameter; unlike `Either`, it distinguishes expected failures (`Failure[E]`) from unexpected exceptions (`Panic`). Both are subtypes of `Error[E]`, so handlers can target either layer.

```scala
import kyo.*

case class NotFound(id: Int)

def lookup(id: Int): Result[NotFound, String] =
    if id == 1 then Result.succeed("Alice")
    else Result.fail(NotFound(id))

val ok: Result[NotFound, String]   = lookup(1)
val no: Result[NotFound, String]   = lookup(42)
val boom: Result[Nothing, Int]     = Result[Int](throw new RuntimeException("kaboom"))  // Panic
```

`Result.apply` wraps any expression that might throw, catching all throwables as `Panic`. `Result.catching[E <: Throwable]` narrows: it converts the named exception type to `Failure[E]` and lets everything else surface as `Panic`. Use `succeed` and `fail` for direct construction, `fromEither` and `fromTry` to bridge from the standard library, and `Result.collect` to turn a `Seq[Result[E, A]]` into a `Result[E, Seq[A]]` (first error wins).

```scala
import kyo.*
import java.lang.NumberFormatException

val parsed: Result[NumberFormatException, Int] =
    Result.catching[NumberFormatException]("42".toInt)

val gathered: Result[NumberFormatException, Seq[Int]] =
    Result.collect(Seq(
        Result.catching[NumberFormatException]("1".toInt),
        Result.catching[NumberFormatException]("2".toInt)
    ))
```

> **Note:** `Result` stores a successful value unboxed: a `Success(42)` is `42` at runtime. To distinguish `Success(error)` from a direct `Error`, the implementation introduces internal boxing only when a `Success` would otherwise collide with an `Error` shape.

### Pattern matching on the result

For inspection by case, `Result` exposes three matchable variants: `Success[A]`, `Failure[E]`, and `Panic`. `Error[E]` is the common parent of `Failure` and `Panic` for handlers that want both.

```scala
import kyo.*
import Result.*

case class NotFound(id: Int)

def describe(r: Result[NotFound, String]): String = r match
    case Success(value)   => s"got $value"
    case Failure(e)       => s"expected error: $e"
    case Panic(throwable) => s"panic: ${throwable.getMessage}"

val s = describe(Result.succeed("hello"))
val f = describe(Result.fail(NotFound(7)))
```

For non-match transforms, `Result` has the operations you expect plus several that target specific error variants. `fold` takes three handlers; `foldError` collapses `Failure` and `Panic` together; `foldOrThrow` returns success or rethrows.

```scala
import kyo.*

case class NotFound(id: Int)

val r: Result[NotFound, Int] = Result.succeed(42)

val folded: String = r.fold(
    onSuccess = v => s"value=$v",
    onFailure = e => s"failure=$e",
    onPanic   = t => s"panic=${t.getMessage}"
)

val asEither: Either[NotFound | Throwable, Int] = r.toEither
val asMaybe:  Maybe[Int]                        = r.toMaybe
```

### Recovering from specific errors

For straight-line transformation, `map` and `flatMap` operate on success; `mapError`, `mapFailure`, and `mapPanic` operate on error variants. The `flatMap*` family does the same with results that may themselves fail.

`flatMapError` is unusual: it accepts a `ConcreteTag[E2 <: E]` so it can recover from a specific subtype of the error union while leaving other error types unchanged. This is how Kyo code handles "only the network errors, not the validation errors" without losing the rest of the union.

```scala
import kyo.*

case class Timeout(after: Duration)
case class BadCredentials(user: String)

def query(): Result[Timeout | BadCredentials, Int] = Result.fail(Timeout(5.seconds))

// Recover only Timeout; BadCredentials would remain in the result type
val recovered: Result[BadCredentials, Int] =
    query().flatMapError[Timeout]((e: Timeout | Throwable) => Result.succeed(0))
```

### Narrowing to expected failures only

When code models only expected errors (no panics), `Result.Partial[E, A]` is a subtype of `Result` that excludes `Panic`. It has a stricter `foldPartial` (two cases, no panic handler), `toEitherPartial`, and `flattenPartial`.

```scala
import kyo.*

case class NotFound(id: Int)

val r: Result.Partial[NotFound, Int] = Result.Success(42)

val s: String = r.foldPartial(
    onSuccess = v => s"value=$v",
    onFailure = e => s"failure=$e"
)

val asEither: Either[NotFound, Int] = r.toEitherPartial
```

## Sequences and maps

Different collection cost models matter at different points in a program. Slicing a large buffer should not copy the buffer. Storing primitive bytes should not box every byte to `java.lang.Byte`. Small lookup tables should not pay the hashing overhead of a full hash map. `kyo-data` offers three sequence/map types tuned to those cost models and two builders for incremental construction.

### Slicing without copying

When you want a `Seq`-compatible collection that supports cheap slicing, use `Chunk`. `Chunk` is a `Seq[A]` whose `take`, `drop`, `slice`, and `concat` are O(1): each returns a view that shares the underlying buffer with the original. The iteration cost is paid lazily on traversal, not at construction.

```scala
import kyo.*

val items: Chunk[String] = Chunk("a", "b", "c", "d", "e")
val tail: Chunk[String]  = items.drop(2)              // O(1)
val pair: Chunk[String]  = items.slice(1, 3)          // O(1)
val joined: Chunk[String] = items ++ Chunk("f", "g")  // O(1)
```

`Chunk` extends `Seq`, so anything taking a Scala collection works. Its companion adds factories for `Array`, `IterableOnce`, `Maybe`, and `Option` sources, plus `Chunk.empty`. `Chunk.Indexed` is a subtype that guarantees O(1) indexed access; `chunk.toIndexed` converts. Extras beyond `Seq` include `append`, `headMaybe`/`lastMaybe`, `changes` (drop consecutive duplicates), `flattenChunk`, and `dropLeft`/`dropRight`/`dropLeftAndRight`.

```scala
import kyo.*

val xs: Chunk[Int]              = Chunk.from(Array(1, 1, 2, 2, 3))
val uniq: Chunk[Int]            = xs.changes
val first: Maybe[Int]           = xs.headMaybe
val withTwo: Chunk[Int]         = xs.append(99)
```

> **Note:** `Chunk` boxes primitive types. If you need primitive-friendly storage, use `Span`.

### Storing primitives without boxing

When you have a sequence of primitives or want minimum allocation per element, use `Span`. `Span[A]` is an opaque type over `Array[? <: A]`, so it stores `Int`/`Long`/`Double`/`Byte` directly without boxing to `java.lang.Integer` and friends.

```scala
import kyo.*

case class HttpResponse(status: Int, body: Span[Byte])

val bytes: Span[Byte]    = Span.from(IArray[Byte](72, 101, 108, 108, 111))
val empty: Span[Int]     = Span.empty[Int]
val mapped: Span[Int]    = bytes.map(b => b.toInt * 2)
```

> **Caution:** `Span` is NOT a `Seq` and does not extend Scala's collection hierarchy. You cannot pass a `Span[A]` where a `Seq[A]` is expected. When you need `Seq` compatibility, use `Chunk`.

`Span` deliberately omits operations that would box primitives. There is no `zip` that returns tuples, for instance. Where pairwise operations are needed, `Span` offers specialized variants that take multi-parameter functions and avoid the intermediate tuple.

```scala
import kyo.*

val a: Span[Int] = Span[Int](1, 2, 3)
val b: Span[Int] = Span[Int](10, 20, 30)

val anyGreater: Boolean = Span.existsZip(a, b)((x, y) => x > y)
val allLess: Boolean    = Span.forallZip(a, b)((x, y) => x < y)
```

For interop with existing arrays, `Span.fromUnsafe(array)` wraps an array without copying, and `span.toArrayUnsafe` returns the backing array. Both share state.

> **Caution:** `Span.fromUnsafe` and `span.toArrayUnsafe` share the underlying array (no defensive copy). Mutating the array mutates the `Span`. Combined with `Span`'s covariance, an unsafe wrap of a `Array[String]` viewed as `Span[Any]` and then mutated with a non-`String` value can produce a `ClassCastException` at runtime.

### Chunk vs Span: when to use which

`Chunk` and `Span` both store immutable sequences. Use `Chunk` when you need Scala-collection compatibility (passing to methods expecting `Seq`, using `for` comprehensions across multiple library shapes, structural sharing across many slices). Use `Span` when the element type is a primitive and allocation per element matters, or when you want the smallest possible representation for a fixed-size buffer.

### Mapping keys to values

When you need an immutable map, `Dict[K, V]` adapts its representation to the size. For 8 or fewer entries, it stores keys and values in a flat `Span`, looking them up by linear scan (no hashing, cache-friendly). For more than 8 entries, it switches to a `HashMap` with O(1) amortized lookups.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)
val bob   = User(2, "Bob",   "bob@example.com",   Instant.Epoch)

val byId: Dict[Int, User] = Dict(1 -> alice, 2 -> bob)

val a: User           = byId(1)             // throws if missing
val maybeB: Maybe[User] = byId.get(2)       // Absent if missing
val has: Boolean      = byId.contains(3)
val updated: Dict[Int, User] = byId.update(1, alice.copy(name = "Alicia"))
val removed: Dict[Int, User] = byId.remove(2)
val merged: Dict[Int, User]  = byId ++ Dict(3 -> bob)
```

> **Note:** `Dict` is opaque and provides no `CanEqual` instance. Use `dict.is(other)` for structural equality, not `==`.

> **Note:** `Dict` switches representations at 8 entries. Lookup semantics are stable across the switch; performance characteristics differ.

Iteration and transformation take key and value as separate parameters rather than a `(K, V)` tuple, avoiding tuple allocation in hot paths.

```scala
import kyo.*

val byCount: Dict[String, Int] = Dict("a" -> 1, "b" -> 2, "c" -> 3)

byCount.foreach((k, v) => println(s"$k=$v"))
val doubled: Dict[String, Int] = byCount.map((k, v) => (k.toUpperCase, v * 2))
val onlyBig: Dict[String, Int] = byCount.filter((k, v) => v >= 2)
val total: Int                 = byCount.foldLeft(0)((acc, _, v) => acc + v)
```

### Building incrementally

When constructing a large `Chunk` or `Dict` element by element, use the dedicated builders. Both expose `add`, `result`, `clear`, and `size`. `ChunkBuilder` reuses a thread-local buffer pool to amortize allocation across calls.

```scala
import kyo.*

val cb = ChunkBuilder.init[Int]
(1 to 5).foreach(cb.addOne)
val chunk: Chunk[Int] = cb.result()

val db = DictBuilder.init[String, Int]
db.add("a", 1)
db.add("b", 2)
val dict: Dict[String, Int] = db.result()
```

## Time and scheduling

`kyo-data` provides one set of time primitives the rest of the ecosystem shares: a `Duration` for time spans, an `Instant` for points in time, and a `Schedule` for retry and periodic policies. Arithmetic on `Duration` and `Instant` saturates rather than wrapping or throwing.

### Time spans

When code talks about a length of time, reach for `Duration`. It is an opaque type over `Long` (representing nanoseconds). Construct it through the unit extensions (`1.second`, `5.minutes`, `2.hours`), the factory methods (`Duration.fromNanos`, `Duration.fromUnits`), or parse it from text.

```scala
import kyo.*

val short: Duration = 30.seconds
val medium: Duration = 5.minutes
val long: Duration   = 1.hour

val z: Duration  = Duration.Zero
val i: Duration  = Duration.Infinity

val parsed: Result[Duration.InvalidDuration, Duration] = Duration.parse("30s")
```

> **Note:** Singular unit names (`1.second`, `1.hour`) are only available on the literal `1`. For other values, use the plural form (`5.seconds`, `2.hours`). The compiler produces an error directing you to the plural form if you try `5.second`.

Arithmetic saturates on overflow rather than wrapping. `Duration.Infinity` is encoded as `Long.MaxValue`, and any operation that would overflow clamps to `Infinity`.

```scala
import kyo.*

val a: Duration = 5.seconds + 30.seconds   // 35 seconds
val b: Duration = 1.hour - 30.minutes      // 30 minutes
val c: Duration = 1.second * 60            // 60 seconds
val clamped: Duration = Duration.Infinity + 1.day  // still Infinity
```

`Duration` also offers unit accessors (`toNanos`, `toMillis`, `toSeconds`, ...) and conversion to `java.time.Duration` and `scala.concurrent.duration.Duration`.

### Points in time

For timestamps (a moment, not a span), use `Instant`. It is an opaque wrapper over `java.time.Instant`. Construct one through `Instant.parse` (ISO-8601), `Instant.of(seconds, nanos)`, or `Instant.fromJava`. The constants `Instant.Min`, `Instant.Max`, and `Instant.Epoch` are the obvious bounds.

```scala
import kyo.*

val now: Instant      = Instant.parse("2024-01-15T10:00:00Z").getOrThrow
val later: Instant    = now + 1.hour
val earlier: Instant  = now - 30.minutes
val gap: Duration     = later - earlier   // 1 hour 30 minutes
```

> **Note:** `instant + Duration.Infinity` returns `Instant.Max` (saturating); the inverse subtraction returns `Instant.Min`. Arithmetic does not throw on overflow.

`Instant` has an `Ordering`, so it works with sort/min/max from the standard library.

### Retry and periodic policies

When code retries a failed call or runs work on a cadence, the policy itself becomes a value. `Schedule` is a sealed, immutable description of a sequence of delays. Its core operation is `next(now: Instant): Maybe[(Duration, Schedule)]`, returning the next delay and the policy to use afterward (or `Absent` when the schedule is done). Combine schedules with `max`, `min`, `andThen`, `take`, `repeat`, `maxDuration`, `delay`, `jitter`, and `forever`.

```scala
import kyo.*

val immediate: Schedule = Schedule.immediate
val never: Schedule     = Schedule.never
val every5s: Schedule   = Schedule.fixed(5.seconds)
val backoff: Schedule   = Schedule.exponentialBackoff(
    initial    = 100.millis,
    factor     = 2.0,
    maxBackoff = 10.seconds
)
val retry5: Schedule    = backoff.take(5)
val firstTen: Schedule  = backoff.maxDuration(10.seconds)
val jittery: Schedule   = backoff.jitter(0.5)
```

> **Note:** `Schedule.jitter`'s randomness is deterministic, derived from an XOR shift on a hash of the current `Instant` and duration, not a PRNG. Repeat runs with the same starting `Instant` produce identical schedules.

Other factories include `Schedule.linear(base)`, `Schedule.fibonacci(a, b)`, `Schedule.exponential(initial, factor)`, and `Schedule.anchored(period, offset)` (executes at fixed time points aligned to a period, catching up on missed periods).

```scala
import kyo.*

val hourly: Schedule  = Schedule.anchored(1.hour)
val daily2am: Schedule = Schedule.anchored(1.day, 2.hours)

// Read the first three delays from a schedule
def take3(s: Schedule, now: Instant): List[Duration] =
    s.next(now) match
        case Absent => Nil
        case Present((d1, s1)) =>
            s1.next(now + d1) match
                case Absent => List(d1)
                case Present((d2, s2)) =>
                    s2.next(now + d1 + d2) match
                        case Absent            => List(d1, d2)
                        case Present((d3, _))  => List(d1, d2, d3)
```

`schedule.show` returns a string that resembles the source-level constructor call; `toString` delegates to `show`. The result is suitable for logs and debug output.

## Records and named fields

A record is a named collection of typed fields whose shape is known at compile time but is not declared as a case class. `kyo-data` offers `Record[F]`, where `F` is an intersection of `Name ~ Value` pairs encoding the schema directly in the type. The macro-derived `Fields[F]` companion provides runtime metadata; `Field[Name, Value]` is the reified per-field descriptor.

### Building a record

Records are built from string literals via the `~` extension and combined with `&`. The empty record is the identity element for `&`.

```scala
import kyo.*

val person: Record["name" ~ String & "age" ~ Int] =
    "name" ~ "Alice" & "age" ~ 30

val name: String = person.name   // selectDynamic, return type inferred
val age: Int     = person.age

val withEmail = person & "email" ~ "alice@example.com"
```

Field access goes through `selectDynamic`, which requires `Fields.Have[F, Name]` evidence that the field exists in `F`. The return type is inferred from the field's declared type, so no annotation is needed. For field names that are not valid Scala identifiers (e.g., `"user-name"`, `"&"`), use `getField`.

```scala
import kyo.*

val r: Record["user-name" ~ String] = "user-name" ~ "alice"
val name: String = r.getField["user-name", String]("user-name")
```

> **Note:** Record's type parameter `F` is invariant, but an implicit `widen` conversion provides structural subtyping. After widening (`Record["a" ~ Int & "b" ~ String]` used where `Record["a" ~ Int]` is expected), the underlying dict still holds the original fields. Call `compact` if you want to actually drop them.

> **Note:** Duplicate field names with different types merge into a union at the type level: `"f" ~ Int & "f" ~ String =:= "f" ~ (Int | String)`. This is because `~` is contravariant in `Value`.

### Records from case classes

For records derived from existing case classes, use `Record.fromProduct`. The transparent macro produces a `Record` whose field intersection matches the case class's element labels and types.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)
val rec   = Record.fromProduct(alice)

val id: Int       = rec.id
val email: String = rec.email
```

### Updating, mapping, and zipping

To modify or transform a record, four operations cover the common cases. `update(name, value)` returns a new record with one field replaced. `map` applies a polymorphic function to every value type, lifting it through `G[_]`. `mapFields` is `map` plus access to the `Field` descriptor (name and `Tag`). `zip` pairs values between two records with the same field names.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)
val rec   = Record.fromProduct(alice)

val renamed = rec.update("name", "Alicia")

// Wrap every value in Maybe
val optional = rec.map[Maybe]([t] => (v: t) => Maybe(v))

val sameShape = Record.fromProduct(alice.copy(name = "Alicia"))
val paired    = rec.zip(sameShape)  // each field becomes (a, b)
```

> **Note:** Record `==` requires `Fields.Comparable[F]` evidence that every field type has `CanEqual`. Records with non-comparable values fail to compile rather than silently allowing comparison.

### Reified field descriptors

For code that operates on a record's schema as data (serializers, validators, debuggers), the per-field descriptor is `Field[Name, Value]`. It carries the field's singleton name, value `Tag`, optional nested fields (populated for nested records), and an optional default. Obtain them through `Fields.fields[F]` (a list) or `Field.apply[Name, Value]` (summons from implicit scope).

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val all: List[Field[?, ?]] = Fields.fields[User]
val names: Set[String]     = Fields.names[User]

val nameField = Field["name", String]
```

### Compile-time staging per field

When derivation needs to visit every field at compile time (to build per-field metadata, default values, or type class instances), use `Record.stage`. It iterates over a field set at compile time, applying a polymorphic function to each. Chain `.using[TC]` to require a type-class instance for every field's value type.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

// For each field, build a Maybe[v] from its default
val staged = Record.stage[User]([v] => (f: Field[?, v]) => f.default)

// With a type-class constraint: every field needs a Render instance.
// The result wraps each value type in Maybe, but the body has access to
// the field's Render for any per-field-typed work.
val rendered =
    Record.stage[User]
        .using[Render]
        ([v] => (f: Field[?, v], r: Render[v]) => Maybe.empty[v])
```

### Library-author tooling

When building DSLs around records, you may need to prevent Scala from merging field names during inference (since `~` is contravariant in the name parameter). `Fields.Pin[N]` and `Fields.Exact[F, R]` exist for that purpose. End users typically do not need them; they are surfaced here for library authors building Record-shaped DSLs.

`Fields.SameNames[F, F2]` is the evidence `zip` requires that two record types have identical field names. `Fields.SummonAll[F, TC]` is the per-field type-class instance map used by `stage.using[TC]` and the derived `Render`/`Flag.Reader` instances.

## Type identity at runtime

Effect handlers, heterogeneous maps, and runtime introspection all need a way to identify types after erasure. `kyo-data` provides two complementary tags: `Tag[A]` (full generic-type identity, used by every Kyo effect) and `ConcreteTag[A]` (union/intersection-aware but no generics). The companion `TypeMap[+A]` is a heterogeneous map keyed by `Tag`.

### Compile-time-derived tags

When you need a type's identity at runtime (for an effect handler, a heterogeneous map, runtime dispatch), `Tag[A]` is the primary tool. The macro encodes the type structure as a string constant in the bytecode when it can; otherwise it constructs a dynamic tag at runtime. Operations include `=:=`, `=!=`, `<:<`, `>:>`, and composition with `&` / `|`.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val a: Tag[User]          = Tag[User]
val b: Tag[String | Int]  = Tag[String | Int]

val sameType: Boolean = a =:= Tag[User]                // true
val isSub: Boolean    = Tag[Int] <:< Tag[Int | String] // true
val show: String      = a.show
```

> **Note:** Inside the `kyo` package, `Tag.derive` fails rather than falling back to dynamic-tag construction. This is a performance constraint on internal Kyo code; user code outside the package falls back to dynamic derivation when needed.

### Union- and intersection-aware tags

When the type to identify involves a union or intersection (and is not generic), `ConcreteTag[A]` captures it precisely. `ClassTag` collapses unions and intersections; `ConcreteTag` does not. It is what `Result.flatMapError` uses to recover from specific subtypes of a union error.

```scala
import kyo.*

case class Timeout(after: Duration)
case class BadCredentials(user: String)

val ct: ConcreteTag[Timeout | BadCredentials] = ConcreteTag[Timeout | BadCredentials]

// Factory from a runtime class
val intTag: ConcreteTag[Int] = ConcreteTag.fromClass(java.lang.Integer.TYPE)

// Allocate an array of the matching primitive type
val arr: Array[Int] = ConcreteTag.newArray[Int](4)
```

> **Caution:** `ConcreteTag` does not support generic types (no type parameters) and cannot represent `Null`. Use `Tag` when you need full generic-type identity.

`ConcreteTag.Element` is the sealed runtime representation: `Union`, `Intersection`, `LiteralTag`, and the primitive cases (`IntTag`, `LongTag`, `DoubleTag`, `FloatTag`, `ByteTag`, `ShortTag`, `CharTag`, `BooleanTag`), plus `UnitTag`, `AnyValTag`, and `NothingTag`. Most user code does not pattern-match on these directly; the union and intersection types are introspected through the `Element` cases when needed.

### Tag vs ConcreteTag: when to use which

Both `Tag` and `ConcreteTag` identify types at runtime. Use `Tag` for generic types (`Tag[List[Int]]`, `Tag[Result[E, A]]`) and for effect-handler-style code where union/intersection reasoning is bundled into Kyo's effect machinery. Use `ConcreteTag` specifically when you need to check membership in a union or intersection at runtime and the types involved are non-generic (`ConcreteTag[Timeout | BadCredentials]`). `Result.flatMapError` requires `ConcreteTag` rather than `Tag` for exactly that reason.

### Heterogeneous maps

`TypeMap[+A]` stores values keyed by `Tag`. The type parameter tracks which types are in the map: `TypeMap[Int & String]` contains an `Int` and a `String`. `add[B]` extends the type; `get[B]` retrieves by type.

```scala
import kyo.*

val empty: TypeMap[Any]            = TypeMap.empty
val one: TypeMap[Int]              = TypeMap(42)
val two: TypeMap[Int & String]     = one.add("hello")
val three: TypeMap[Int & String & Double] = two.add(3.14)

val i: Int    = three.get[Int]
val s: String = three.get[String]
```

> **Caution:** `TypeMap.get[B]` throws `RuntimeException` if the requested type is not present. There is no `Maybe`-returning variant on the same name. Construct the map so the type parameter accurately reflects what was added; the compiler then ensures `get` finds the value.

> **Note:** `TypeMap.get` requires `NotIntersection[B]` evidence at compile time. Querying by an intersection type (`get[Int & String]`) will not compile; query each component separately.

`prune[B]` filters to entries whose keys are subtypes of `B`. `union(other)` combines two type maps. `size`, `isEmpty`, and `show` are the usual.

### Compile-time literals as runtime values

`ConstValue[A]` is an opaque type that materializes a singleton-type literal (a string literal type like `"name"`, or a numeric literal type) as a runtime value. It is what `Field.apply[Name, Value]` uses to recover the field name string from the `Name` type parameter.

### Rejecting intersection types in evidence

`NotIntersection[A]` is macro-derived evidence that `A` is not an intersection type. It is used by `TypeMap.get` and other surfaces that need a single, non-composite key. User code typically does not write `NotIntersection` directly; the compiler summons it as a constraint on type parameters.

## Text and rendering

When code needs printable output (logs, error messages, diagnostic dumps), `toString` is rarely the right answer for opaque types and structural data. `kyo-data` provides `Text` for cheap string concatenation, `Render[A]` for customizable text representations, `Ansi` extensions for terminal formatting, and `Base64` for byte-to-string encoding.

### Cheap concatenation

`Text` is an opaque type over `String | Op`, where `Op` is an internal rope-like structure used for cheap concatenation. Plain strings stay as `String`; concatenation produces an `Op` and is O(1); the underlying characters are materialized only when the `Text` is rendered to a `String`.

```scala
import kyo.*

val a: Text = Text("hello, ")
val b: Text = "world"  // String widens to Text
val ab: Text = a + b

val length: Int    = ab.length
val rendered: String = ab.show
```

`Text` supports indexing, slicing, length, comparison, and case conversion. Character predicates are passed as `Text.Predicate`, an SAM trait.

### Customizable rendering

`Render[A]` is the type class that produces a `Text` for a value. `kyo-data` opaque types (`Maybe`, `Result`, `Record`, ...) define explicit `Render` instances so their printed form reflects their semantic structure, not the underlying erased value.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)

val text: String = Render[User].asString(alice)
val also: String = Render.asText(alice).show  // via summoned Render
```

`Render` derives automatically for product and sum types whose components all have `Render` instances. The fallback `Render` from `LowPriorityRenders` uses `toString`. `Render.from` builds a custom instance from a function.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

given Render[User] = Render.from(u => Text(s"User(${u.id}, ${u.name})"))
```

`Rendered` is an opaque-type wrapper that converts implicitly from any value with a `Render` instance, and is a subtype of `Text`. The `t` string interpolator on `StringContext` takes `Rendered*` arguments, so any value with a `Render` can be interpolated without an explicit call:

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)

val alice = User(1, "Alice", "alice@example.com", Instant.Epoch)
val line: Text = t"signed in: $alice at ${alice.signedUpAt}"
```

### Terminal color and formatting

`Ansi` provides extension methods on `String` and `Text` for ANSI color and formatting (`red`, `green`, `bold`, `dim`, ...) and a `highlight` helper used by `Frame.render` to format source-context error messages. There is also a `stripAnsi` extension that removes any ANSI escape sequences from a string.

```scala
import kyo.*
import kyo.Ansi.*

val warn: String = "kaboom".red.bold
val info: String = "ready".green
val clean: String = warn.stripAnsi
```

### Binary-to-text encoding

`Base64` is a cross-platform RFC 4648 encoder/decoder operating on `Span[Byte]`. It is pure Scala (no `java.util.Base64` dependency), so it runs identically on JVM, Scala.js, and Native.

```scala
import kyo.*

val bytes: Span[Byte] = Span.from(IArray[Byte](72, 101, 108, 108, 111))
val encoded: String   = Base64.encode(bytes)

val decoded: Result[IllegalArgumentException, Span[Byte]] =
    Base64.decode(encoded)

val unsafe: Span[Byte] = Base64.decodeOrThrow(encoded)
```

## Putting it together

The types in this module are designed to be combined. A `Dict` stores values by key; lookups return `Maybe`; failed operations return `Result`; timestamps and deadlines use `Instant` and `Duration`; records hold structured fields.

```scala
import kyo.*

case class User(id: Int, name: String, email: String, signedUpAt: Instant)
case class Order(id: Int, userId: Int, total: Duration, items: Chunk[String])

val signedUpAt = Instant.parse("2024-01-15T10:00:00Z").getOrThrow
val alice = User(1, "Alice", "alice@example.com", signedUpAt)
val order = Order(101, 1, 5.minutes, Chunk("book", "pen"))

// Optional values without wrapper allocation
val byId: Dict[Int, User] = Dict(1 -> alice)
val found: Maybe[User]    = byId.get(1)

// Errors as data, distinguishing expected from unexpected
val parsed: Result[Duration.InvalidDuration, Duration] = Duration.parse("30s")

// Time arithmetic with saturating semantics
val deadline: Instant = signedUpAt + 7.days

// Records derived from a case class, type-safe field access
val rec = Record.fromProduct(alice)
```

## Call-site context and exceptions

When an error happens, the most useful diagnostic is usually "where in the source did this call originate." `kyo-data` provides `Frame` (a compile-time-captured source position) and `KyoException` (a base exception that carries a `Frame` and renders with source context in development).

### Compile-time-captured source position

`Frame` is an opaque type whose `derive` macro captures the call site (file, line, column, snippet, class, method) at compile time, with no runtime cost. Any method that takes a `using Frame` parameter automatically captures the position of the call.

```scala
import kyo.*

def report(message: String)(using frame: Frame): String =
    s"[${frame.position.fileName}:${frame.position.lineNumber}] $message"

val log = report("something happened")  // frame captures this line
```

`Frame.Position` is the structured accessor over location components: `fileName`, `lineNumber`, `columnNumber`, and `show`. `frame.className`, `frame.methodName`, `frame.snippet`, and `frame.snippetShort` provide the surrounding code. `frame.render` produces a syntax-highlighted source-context string, optionally annotated with detail values.

```scala
import kyo.*

def fail(msg: String)(using frame: Frame): String =
    frame.render(msg)
```

### Exceptions with frame context

`KyoException` is the base exception for `kyo-data` errors. It carries a `Frame` (captured at construction) instead of a stack trace (it extends `NoStackTrace`), overrides `getCause` for performance, and formats its message differently in development vs. production. The constructor is package-private; new subclasses are declared inside the `kyo` package.

`Duration.InvalidDuration` is the canonical example, thrown by `Duration.parse` when the input does not parse. Catching it in user code goes through `Result`:

```scala
import kyo.*
import Result.*

val parsed: Result[Duration.InvalidDuration, Duration] = Duration.parse("not-a-duration")

val message: String = parsed match
    case Success(d) => s"parsed: $d"
    case Failure(e) => s"failed: ${e.getMessage}"
    case Panic(t)   => s"panic: ${t.getMessage}"
```

> **Note:** `KyoException` strips ANSI colors and frame context in production (controlled by `kyo.internal.Environment`). Development mode produces detailed, syntax-highlighted error output for human readers.

`KyoException.maxMessageLength` (default 1000) caps the formatted message size to prevent log explosion.
