# kyo-schema

Define a case class and get JSON serialization, Protobuf encoding, field validation, type-safe lenses, structural diffs, and more, all derived from the type's structure. No annotations, no boilerplate. Works across JVM, JavaScript, and Scala Native.

The module depends only on `kyo-data` (pure data structures) and has no dependency on Kyo's effect runtime, so it can be adopted as a standalone library.

```scala
// Schema-based JSON and Protobuf
Json.encode(alice)      // {"id":1,"name":"Alice","password":"secret","address":{"city":"Portland","zip":"97201"}}
Protobuf.encode(alice)  // Span[Byte] (binary)

// Type-safe lenses reach any depth
Schema[User].focus(_.address.city).update(alice)(_.toUpperCase)
// User(1, "Alice", "secret", Address("PORTLAND", "97201"))

// Type-level reshaping: drop sensitive fields, rename, add computed
val publicView =
    Schema[User]
        .drop(_.password)
        .rename(_.name, "displayName")
// Encodes alice as: {"id":1,"displayName":"Alice","address":{"city":"Portland","zip":"97201"}}

// Diffs as data: capture just what changed, ship, replay
val renamed = alice.copy(name = "Alicia")
val cs = Changeset(alice, renamed) // Produces a changeset with the new name patch
cs.applyTo(alice)                  // Result.Success(renamed)
```

Everything flows from `Schema[A]`, the central type that captures a type's structure at compile time. It's the single source of truth that powers serialization, validation, navigation, and conversion.

The module provides these primitives:

| Primitive | Purpose |
|-----------|---------|
| `Json` / `Protobuf` | Serialize to JSON strings or Protocol Buffers bytes |
| `Focus` | Type-safe lens for reading, writing, and updating fields at any depth |
| `Compare` | Read-only field-by-field comparison of two values |
| `Modify` | Batched field mutations applied as a single unit |
| `Changeset` | Serializable diff that can be stored, transmitted, and replayed |
| `Builder` | Incremental, type-safe case class construction |
| `Convert` | Bidirectional conversion between structurally compatible types |
| `Structure` | Runtime type description and untyped value trees |

## Getting Started

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-schema" % "<latest version>"
```

All public types live in the `kyo` package:

```scala
import kyo.*
```

Schemas are derived automatically on demand, but adding `derives Schema` caches the derivation and reduces compilation time in larger projects:

```scala
case class User(name: String, age: Int) derives Schema
```

## Serialization

### JSON

`Json.encode` converts a value to a JSON string. `Json.decode` parses it back, returning a `Result` instead of throwing:

```scala
case class User(name: String, age: Int)

val json: String = Json.encode(User("Alice", 30))
// {"name":"Alice","age":30}

Json.decode[User](json)
// Result.Success(User("Alice", 30))

Json.decode[User]("""{"name": 42}""")
// Result.Failure(DecodeException(...))
```

Sealed traits use wrapper-object encoding, where the outer key identifies the variant:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

Json.encode[Shape](Circle(5.0))
// {"Circle":{"radius":5.0}}

Json.encode[Shape](Rectangle(3.0, 4.0))
// {"Rectangle":{"width":3.0,"height":4.0}}
```

On decode, the key determines which subtype to construct. This format is self-describing and works without configuration.

For a flat encoding with a type discriminator field, use `schema.discriminator`:

```scala
given Schema[Shape] = Schema[Shape].discriminator("type")

Json.encode[Shape](Circle(5.0))
// {"type":"Circle","radius":5.0}

Json.encode[Shape](Rectangle(3.0, 4.0))
// {"type":"Rectangle","width":3.0,"height":4.0}
```

Missing fields with default values are filled in automatically, which is useful for configuration types and backward-compatible evolution:

```scala
case class Config(host: String, port: Int = 8080, ssl: Boolean = false)

Json.decode[Config]("""{"host":"localhost"}""")
// Result.Success(Config("localhost", 8080, false))
```

For raw bytes, `Json.encodeBytes` and `Json.decodeBytes` work with `Span[Byte]` instead of `String`:

```scala
val bytes: Span[Byte] = Json.encodeBytes(User("Alice", 30))
Json.decodeBytes[User](bytes)
// Result.Success(User("Alice", 30))
```

When accepting untrusted input, configure safety limits to protect against denial-of-service attacks. `maxDepth` limits nesting depth (default 512) and `maxCollectionSize` limits the number of entries in any single collection or object (default 100,000):

```scala
Json.decode[User](untrustedInput, maxDepth = 64, maxCollectionSize = 10000)
```

Exceeding either limit returns a `Result.Failure` containing a `LimitExceededException`.

### Protobuf

The same types that serialize to JSON also serialize to Protocol Buffers:

```scala
val bytes: Span[Byte] = Protobuf.encode(User("Alice", 30))

Protobuf.decode[User](bytes)
// Result.Success(User("Alice", 30))
```

No annotations, no `.proto` files. Each field gets a stable numeric ID derived from its name via MurmurHash3. Adding, removing, or reordering fields in the case class does not break existing serialized data, because IDs are name-based rather than position-based.

`Protobuf.decode` accepts the same `maxDepth` and `maxCollectionSize` safety limits as `Json.decode`.

For interoperability with existing `.proto` definitions, custom IDs can be assigned:

```scala
val schema =
    Schema[User]
        .fieldId(_.name)(1)
        .fieldId(_.age)(2)
```

`Protobuf.protoSchema[A]` generates a `.proto` definition as a string:

```scala
val proto = Protobuf.protoSchema[User]
// syntax = "proto3";
//
// message User {
//   string name = 1;
//   sint32 age = 2;
// }
```

### Built-in Types

Schemas are provided for all common types out of the box:

| Category | Types |
|----------|-------|
| Primitives | `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, `Short`, `Byte`, `Char`, `BigDecimal`, `BigInt` |
| Time | `Instant`, `Duration`, `LocalDate`, `LocalTime`, `LocalDateTime` |
| Identifiers | `UUID` |
| Collections | `List[A]`, `Vector[A]`, `Set[A]`, `Seq[A]`, `Chunk[A]`, `Span[A]`, `Map[String, V]`, `Dict[K, V]` |
| Optional | `Option[A]`, `Maybe[A]` |
| Other | `(A, B)`, `(A, B, C)`, `Result[E, A]`, `Frame`, `Tag[A]`, `Unit` |

Any case class or sealed trait composed of these types derives a `Schema` automatically. Nested case classes work without additional setup.

### Custom Types

For opaque types, assign the schema for the underlying type inside the companion object where the type boundary is transparent:

```scala
opaque type Email = String

object Email:
    def apply(s: String): Email = s
    given Schema[Email] = Schema[String]
```

Inside the companion, `Email` is `String` to the compiler, so `Schema[String]` satisfies `Schema[Email]` without any conversion. Outside the companion the types are distinct, so the given must live inside where the boundary is visible.

For types that need a non-trivial conversion, use `Schema[Underlying].transform[MyType](to)(from)`:

```scala
opaque type Username = String

object Username:
    def apply(s: String): Username = s.toLowerCase
    given Schema[Username] =
        Schema[String].transform[Username](Username(_))(identity)
```

## Validation

The same `Schema[A]` that serializes also validates. Each constraint enforces a rule at runtime and records it as JSON Schema metadata, so your validation logic and API spec stay in sync automatically.

### Constraints

Numeric constraints apply to any field with a `Numeric` instance:

```scala
case class Product(name: String, price: Double, quantity: Int)

val schema =
    Schema[Product]
        .checkMin(_.price)(0.0)          // price >= 0.0
        .checkMax(_.price)(99999.99)     // price <= 99999.99
        .checkMin(_.quantity)(1)         // quantity >= 1
        .checkMax(_.quantity)(1000)      // quantity <= 1000
```

`checkExclusiveMin` and `checkExclusiveMax` use strict inequality (`>` and `<`).

String constraints validate length and format:

```scala
case class Account(username: String, email: String)

val schema =
    Schema[Account]
        .checkMinLength(_.username)(3)              // at least 3 characters
        .checkMaxLength(_.username)(20)             // at most 20 characters
        .checkPattern(_.email)("^.+@.+\\..+$")     // must match regex
        .checkFormat(_.email)("email")             // advisory: appears in JSON Schema only
```

`checkPattern` both validates at runtime and records the pattern in JSON Schema output. For cross-platform compatibility, use POSIX regex features (character classes, anchors, alternation, quantifiers). `checkFormat` is advisory only: it annotates JSON Schema but does not produce a runtime check.

Collection constraints control size and uniqueness on any `Iterable` field:

```scala
case class Order(id: Int, tags: List[String])

val schema =
    Schema[Order]
        .checkMinItems(_.tags)(1)     // at least 1 tag
        .checkMaxItems(_.tags)(10)    // at most 10 tags
        .checkUniqueItems(_.tags)     // no duplicates
```

### Custom Checks

For validations that cannot be expressed as standard constraints, `check` accepts an arbitrary predicate. Unlike constraint methods, `check` does not appear in JSON Schema output because the predicate is an opaque function:

```scala
val schema =
    Schema[Account]
        .checkMinLength(_.username)(3)
        .check(_.username)(_.forall(_.isLetterOrDigit), "alphanumeric only")
```

There are two forms: `.check(_.field)(pred, msg)` targets a single field, while `.check(pred, msg)` targets the root value for cross-field validation:

```scala
val schema =
    Schema[Person]
        .check(_.name)(_.nonEmpty, "name is required")
        .check(person => person.age >= 18 || person.name.nonEmpty, "minors must have a name")
```

### Running Validation

`validate` runs every accumulated constraint and check, collecting all failures:

```scala
val errors: Chunk[ValidationFailedException] =
    schema.validate(Product("", -1.0, 0))
// Chunk(
//   ValidationFailedException(path = List("price"), message = "must be >= 0.0", ...),
//   ValidationFailedException(path = List("quantity"), message = "must be >= 1", ...)
// )
```

Validation is non-short-circuiting: every check runs and you get back all problems at once. Each error carries a `path` showing where the failure occurred and a `frame` recording where the constraint was defined.

### JSON Schema

`Json.jsonSchema[A]` derives a JSON Schema at compile time. Without a `Schema[A]` in scope, it produces a bare structural description:

```scala
val spec = Json.jsonSchema[User]
// JsonSchema.Obj(properties = List(("name", Str()), ("age", Integer())), required = List("name", "age"))
```

When a `Schema[A]` with constraints or documentation is in scope, the output is enriched with all registered metadata:

```scala
given Schema[Product] =
    Schema[Product]
        .checkMin(_.price)(0.0)
        .checkMax(_.price)(99999.99)
        .doc(_.price)("Product price in USD")

val enriched = Json.jsonSchema[Product]
// The "price" property now includes minimum=0.0, maximum=99999.99, and description="Product price in USD"
```

## Navigation

So far every operation has worked on whole values. `Focus` lets you read, write, and update individual fields at any depth.

### Focus

Call `schema.focus(_.path)` to create a lens that targets a specific field:

```scala
case class Address(street: String, city: String, zip: String)
case class Person(name: String, age: Int, address: Address)

val cityFocus = Schema[Person].focus(_.address.city)

val person = Person("Alice", 30, Address("123 Main St", "Portland", "97201"))

cityFocus.get(person)                     // "Portland"
cityFocus.set(person, "Seattle")          // Person with city = "Seattle"
cityFocus.update(person)(_.toUpperCase)   // Person with city = "PORTLAND"
```

Every other field is preserved. Only the targeted value changes, at any depth.

When the path crosses a sealed trait variant, the value might not exist at runtime. If the active variant doesn't match the path, there's nothing to get. The compiler detects this and returns a `Focus` where `get` returns `Maybe` instead of a bare value:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape
case class Drawing(title: String, shape: Shape)

val radiusFocus = Schema[Drawing].focus(_.shape.Circle.radius)

radiusFocus.get(Drawing("art", Circle(5.0)))       // Maybe(5.0)
radiusFocus.get(Drawing("art", Rectangle(3, 4)))   // Maybe.empty

radiusFocus.set(Drawing("art", Circle(5.0)), Maybe(10.0))     // Drawing with radius 10.0
radiusFocus.set(Drawing("art", Rectangle(3, 4)), Maybe.empty) // unchanged
```

A Maybe-mode `Focus` also provides `getOrElse` and `isDefined` for convenience.

### Foreach

When a schema contains collection fields, `foreach` navigates into the elements. It returns a `Focus` where `get` returns `Chunk` (zero or more values) instead of a single value:

```scala
case class Item(name: String, price: Double)
case class Order(id: Int, items: List[Item])

val each = Schema[Order].foreach(_.items)
val order = Order(1, List(Item("Widget", 9.99), Item("Gadget", 19.99)))

each.get(order)
// Chunk(Item("Widget", 9.99), Item("Gadget", 19.99))

each.update(order)(item => item.copy(price = item.price * 1.1))
// Order with all prices increased by 10%
```

Because `foreach` returns a `Focus`, you can chain `.focus` to drill into a specific field of each element:

```scala
val prices = Schema[Order].foreach(_.items).focus(_.price)

prices.get(order)            // Chunk(9.99, 19.99)
prices.update(order)(_ * 2)  // all prices doubled
```

`foreach` composes for nested collections: `foreach(_.orders).foreach(_.items)` flattens across both levels.

### Mode

The examples above showed three different return types from `get`: a bare value, `Maybe`, and `Chunk`. These are the three navigation modes, and the compiler picks the right one based on the path:

| Mode | `get` returns | When |
|------|---------------|------|
| `Focus.Id` | `Value` | Product fields: always exactly one value |
| `Maybe` | `Maybe[Value]` | Sum variants: the variant might not be active |
| `Chunk` | `Chunk[Value]` | Collections: zero or more elements |

The compiler infers the mode from the path. You never choose it yourself. Once you cross a sum variant or enter a collection, every subsequent step inherits that uncertainty:

```scala
schema.focus(_.name)                          // Focus[Person, String, Id]
schema.focus(_.shape.Circle.radius)           // Focus[Drawing, Double, Maybe]
schema.foreach(_.items)                       // Focus[Order, Item, Chunk]
schema.foreach(_.items).focus(_.price)        // Focus[Order, Double, Chunk]
```

### Fold

`Focus` navigates to a specific field. When you need to process *all* fields generically (for example, building a map of field names to values, or generating a log line), `fold` iterates with fully typed field names and values:

```scala
val fieldMap = Schema[Person].fold(person)(Map.empty[String, Any]) {
    [N <: String, V] => (acc, field, value) =>
        acc + (field.name -> value)
}
// Map("name" -> "Alice", "age" -> 30)
```

The polymorphic function receives the exact singleton name type `N` and value type `V` for each field, along with a `Field[N, V]` descriptor that carries the field name, tag, default value, and metadata. `fold` respects active transforms: dropped fields are skipped, renamed fields use the new name, and computed fields are included.

## Transforms

Sometimes the entire shape needs to change: omitting sensitive fields for an API response, renaming for a different convention, or adding computed values. Transforms reshape a schema's structural type without changing the source type `A`. Because serialization, validation, and navigation all read the same structural description, a transform affects all of them consistently. Transforms are only available on case class schemas.

Consider a common scenario: a `User` with `id`, `name`, `email`, `password`, and `signupDate`, where an API response should omit the password, rename `name` to `displayName`, and add a computed `active` field:

```scala
case class User(id: Int, name: String, email: String, password: String, signupDate: Long)

val apiSchema =
    Schema[User]
        .drop(_.password)
        .rename(_.name, "displayName")
        .add("active")(user => user.signupDate > 0L)
```

The password is absent from serialized output because it is absent from the structural type.

### drop / rename / add / select / flatten

**drop** removes a field:

```scala
Schema[Person].drop(_.age)
// Serialized: {"name":"Alice"}
```

**rename** changes a field's name, preserving its type:

```scala
Schema[Person].rename(_.name, "userName")
// Serialized: {"userName":"Alice","age":30}
```

**add** adds a computed field derived from the source value:

```scala
Schema[Person].add("adult")(_.age >= 18)
// Serialized: {"name":"Alice","age":30,"adult":true}
```

**select** keeps only the named fields, dropping everything else:

```scala
Schema[Person].select(_.name)
// Serialized: {"name":"Alice"}
```

**flatten** inlines nested case class fields into the parent level:

```scala
case class Address(city: String, zip: String)
case class Person(name: String, address: Address)

Schema[Person].flatten
// Serialized: {"name":"Alice","city":"Portland","zip":"97201"}
```

String-based versions (`drop("field")`, `rename("from", "to")`, etc.) are also available when the field name is not known at compile time.

### Transforms and Serialization

When you have a named or transformed schema, use the instance-level `encodeString` and `decodeString` methods:

```scala
val s = Schema[Person].rename(_.name, "userName")

s.encodeString[Json](Person("Alice", 30))
// {"userName":"Alice","age":30}

s.decodeString[Json]("""{"userName":"Alice","age":30}""")
// Result.Success(Person("Alice", 30))
```

`encode` and `decode` are also available for `Span[Byte]` input/output.

Each transform has defined behavior on the round-trip:

- **rename**: encode uses the new name, decode reads the new name and maps it back to the original field.
- **drop**: encode omits the field. Decode fills it from the default value if one exists; otherwise uses the zero value.
- **add**: the computed field appears in encoded output. Decode ignores unknown fields, so the extra field is silently discarded.
- **select**: equivalent to dropping all fields not named in the selection.

## Type Conversion

`Convert[A, B]` is a one-directional conversion derived at compile time from the field structure of both types. It succeeds when B's fields are a subset of A's (with matching types), or when B has defaults for the missing ones:

```scala
case class Point2D(x: Int, y: Int)
case class Coords(x: Int, y: Int)

val convert = Convert[Point2D, Coords]

convert(Point2D(3, 4))  // Coords(3, 4)
```

`Convert` extends `scala.Conversion[A, B]`, so providing it as a `given` enables implicit conversion:

```scala
given Convert[Point2D, Coords] = Convert[Point2D, Coords]

def draw(c: Coords): Unit = ???

draw(Point2D(3, 4))  // implicit conversion applied
```

For manual construction, pass a function explicitly:

```scala
val convert = Convert[String, Int](_.toInt)
```

`andThen` composes two converts: `Convert[A, B].andThen(Convert[B, C])` produces a `Convert[A, C]`.

### Transform-aware Conversion

`schema.convert[B]` matches against the schema's transformed view (after drops, renames, adds) rather than the original type:

```scala
case class UserResponse(displayName: String, email: String, id: Int, active: Boolean)

val toResponse: Convert[User, UserResponse] =
    Schema[User]
        .drop(_.password)
        .rename(_.name, "displayName")
        .add("active")(user => user.signupDate > 0L)
        .convert[UserResponse]

toResponse(User(1, "Alice", "alice@example.com", "secret", 1000L))
// UserResponse("Alice", "alice@example.com", 1, true)
```

If `B` has a required field with no match in the transformed view, compilation fails. Fields with defaults are filled automatically.

## Comparison and Mutation

When you need to compare two values, patch one into another, or describe mutations as data, the module provides three types:

- **Compare**: read-only, holds two values and lets you query which fields differ
- **Modify**: batched writes, accumulates field mutations and applies them as a single unit
- **Changeset**: serializable diff, captures the operations needed to turn one value into another as data that can be stored, transmitted, and replayed

All three use the same focus lambda syntax (`_.field`) for field navigation.

### Compare

`Compare` gives you a field-by-field view of the differences between two values:

```scala
case class Config(host: String, port: Int, ssl: Boolean)

val staging    = Config("staging.example.com", 8080, false)
val production = Config("prod.example.com", 443, true)

val d = Compare(staging, production)

d.changed              // true (at least one field differs)
d.changed(_.host)      // true
d.changed(_.port)      // true
d.left(_.host)         // Maybe("staging.example.com")
d.right(_.host)        // Maybe("prod.example.com")
d.changes              // Seq(("host", ..., ...), ("port", ..., ...), ("ssl", ..., ...))
```

`left` and `right` return `Maybe[V]` because the path might cross a sealed trait variant that is not active.

### Modify

`Modify` accumulates field mutations and applies them all at once. Use it when you want to describe a set of changes as a reusable value before applying them:

```scala
val updated =
    Modify[Config]
        .set(_.host)("prod.example.com")
        .update(_.port)(_ + 1)
        .applyTo(staging)
// Config("prod.example.com", 8081, false)
```

**Modify vs Focus.set/update**: `Focus` is for immediate, single-field operations where you have a value and want to change one thing right now. `Modify` is for building up a batch of changes as a first-class value that you can store, pass around, and apply later. If you only need to change one field, use `Focus`. If you need to describe multiple changes as a unit, use `Modify`.

For sum-type paths, if the active variant does not match, the operation is silently skipped:

```scala
Modify[Shape]
    .update(_.Circle.radius)(_ * 2)  // applied if shape is Circle, skipped otherwise
    .applyTo(someShape)
```

### Changeset

`Changeset` computes the difference between two values and stores it as a sequence of patch operations. Unlike `Compare`, which requires both values in memory, a `Changeset` is self-contained data that can be serialized, sent over the wire, and applied independently.

Under the hood, both values are converted to untyped `Structure.Value` trees and compared field by field. For each difference, the most specific operation is chosen: an arithmetic delta for numbers, a text patch for strings, recursive descent for nested records, element-level tracking for collections and maps. The result is a `Chunk[Changeset.Op]` that can reconstruct the target from the source:

```scala
val cs = Changeset(staging, production)

cs.isEmpty  // false
cs.operations
// Chunk(
//   StringPatch(Chunk("host"), 0, 23, "prod.example.com"),
//   NumericDelta(Chunk("port"), -7637),
//   SetField(Chunk("ssl"), Bool(true))
// )
```

Each operation carries its field path (`Chunk[String]`) so it knows where to apply. `NumericDelta` stores the arithmetic difference rather than the absolute value. `StringPatch` stores a character-range edit. `Nested` recurses into sub-records. `SequencePatch` and `MapPatch` track additions, removals, and updates at the element level.

`applyTo` replays the operations against a source value:

```scala
val result: Result[SchemaException, Config] = cs.applyTo(staging)
// Result.Success(Config("prod.example.com", 443, true))
```

`Changeset` derives `Schema`, so it serializes like any other data. This makes it possible to compute a diff on one machine, send it over the wire, and apply it on another:

```scala
// Sender: compute and serialize the diff
val serialized: String = Json.encode(cs)

// Receiver: deserialize and apply
val received = Json.decode[Changeset[Config]](serialized).getOrThrow
val updated = received.applyTo(staging)
// Result.Success(Config("prod.example.com", 443, true))
```

Changesets compose with `andThen`.

## Construction

`Builder[A]` provides type-safe incremental construction of case classes. Fields can be set in any order, but all required fields (those without default values) must be set before calling `.result`. The compiler tracks which fields are still missing:

```scala
case class Config(host: String, port: Int = 8080, debug: Boolean = false)

val config =
    Builder[Config]
        .host("localhost")
        .result
// Config("localhost", 8080, false)
```

Fields with defaults are always optional. Calling `.result` before setting all required fields is a compile error that names the missing fields.

## Ordering and Equality

`Schema[A]` derives `Ordering[A]` and `CanEqual[A, A]` for case classes, so you don't need to write comparison logic by hand. The ordering is lexicographic by field declaration order: the first field is the primary sort key, the second field breaks ties, and so on:

```scala
val schema = Schema[User]
import schema.order

val users = List(User("Charlie", 25), User("Alice", 30), User("Alice", 28))
users.sorted
// List(User("Alice", 28), User("Alice", 30), User("Charlie", 25))
// "Alice" < "Charlie" (primary key), then 28 < 30 (secondary key)
```

`CanEqual` enables `==` when strict equality is active:

```scala
import schema.canEqual

User("Alice", 30) == User("Alice", 30)  // true
```

Both are imported from the schema instance. The ordering always follows the case class field declaration order, regardless of any transforms applied to the schema.

## Structural Introspection

### Structure

`Structure` is the runtime type description API. It provides two complementary trees: `Structure.Type` describes the shape of a Scala type (fields, variants, element types), and `Structure.Value` holds actual data in an untyped, format-neutral representation. Together they let you traverse, compare, and transform values without knowing their concrete types. This is useful for generic programming, building admin UIs, bridging to dynamic languages, and powering `Changeset`.

`Structure.of[A]` derives the type shape at compile time:

```scala
val tpe: Structure.Type = Structure.of[Person]
// Structure.Type.Product with fields "name" (Str) and "age" (Integer)
```

The type tree has variants for each category of Scala type: `Product` (case classes), `Sum` (sealed traits), `Collection` (lists, sets), `Mapping` (maps), `Optional` (Option/Maybe), and `Primitive` (scalars).

`Structure.encode` converts a typed value into the untyped `Value` tree. `Structure.decode` converts it back:

```scala
val dynamic: Structure.Value = Structure.encode(Person("Alice", 30))
// Structure.Value.Record(Chunk(("name", Str("Alice")), ("age", Integer(30))))

val restored: Result[DecodeException, Person] = Structure.decode[Person](dynamic)
// Result.Success(Person("Alice", 30))
```

Case classes become `Value.Record` (named fields). Sealed traits become `Value.VariantCase` (variant name + payload). Collections become `Value.Sequence`. Scalars become `Value.Str`, `Value.Integer`, `Value.Decimal`, `Value.Bool`, or `Value.BigNum`.

`Structure.Path` navigates untyped value trees via `get` and `set`, composing segments with `/`:

```scala
val path = Structure.Path.field("name")
path.get(dynamic)  // Result.Success(Chunk(Str("Alice")))
```

Path segments include `Field("name")` for record fields, `Variant("Circle")` for sum variants, `Index(0)` for sequence elements, and `Each` for wildcard traversal of all elements.

### Schema Metadata

Schemas carry documentation, examples, and deprecation markers. These flow into JSON Schema generation, making your API spec reflect the annotations you add in code:

```scala
val schema =
    Schema[Person]
        .doc("A person in the system")
        .doc(_.name)("The person's full name")
        .deprecated(_.age)("Use birthDate instead")
        .example(Person("Alice", 30))
```

Field layout is available at runtime for building dynamic UIs, generating documentation, or driving generic serialization:

```scala
Schema[User].fieldNames        // Set("name", "age")
Schema[User].fieldDescriptors  // Seq of Field descriptors with name, tag, default, etc.
```

`schema.defaults` returns a typed `Record` of fields that have compile-time defaults, useful for Builder-style construction and form pre-population:

```scala
val defaults = Schema[Config].defaults
defaults.port   // 8080
defaults.debug  // false
```

`schema.structure` returns the runtime `Structure.Type`. `schema.toRecord` converts a value into a typed `Record` respecting active transforms. `schema.fieldDocs`, `schema.droppedFields`, and `schema.renamedFields` expose transform metadata for tools that need to inspect what reshaping has been applied.

## Custom Formats

`Json` and `Protobuf` are the built-in formats, but the serialization pipeline itself is format-agnostic. A schema describes a value as a sequence of typed events (`objectStart`, `field`, `int`, `arrayStart`, ...) and a matching sequence on the way back. A format is the code that turns those events into bytes and back.

### The Codec trait

A format is implemented as a `Codec`, which is just a factory for a matching `Writer` and `Reader`:

```scala
abstract class Codec:
    def newWriter(): Codec.Writer
    def newReader(input: Span[Byte])(using Frame): Codec.Reader
```

- `Writer` receives a stream of structural events and accumulates bytes in a buffer
- `Reader` consumes bytes and answers the same events in reverse to reconstruct the value

Schemas never know which codec is in use. They traverse the value in declaration order and emit events; the codec decides how those events are laid out on the wire. This is why the same `Schema[User]` can serialize to JSON, Protobuf, or any format you define, without modification.

### The event model

`Codec.Writer` and `Codec.Reader` both expose the same vocabulary. Every structural category (product, sum, collection, map, primitive) maps to a sequence of calls:

| Structure | Writer calls | Reader calls |
|-----------|--------------|--------------|
| Case class | `objectStart(name, size)`, `field(name, id)`, value, ..., `objectEnd()` | `objectStart()`, `field()`, value, `hasNextField()`, ..., `objectEnd()` |
| Sealed trait | wrapper object with one field per variant (or discriminator, see `Schema.discriminator`) | same |
| Collection | `arrayStart(size)`, element, ..., `arrayEnd()` | `arrayStart()`, element, `hasNextElement()`, ..., `arrayEnd()` |
| Map | `mapStart(size)`, key, value, ..., `mapEnd()` | `mapStart()`, key, value, `hasNextEntry()`, ..., `mapEnd()` |
| Primitive | `int(v)` / `string(v)` / `bool(v)` / ... | `int()` / `string()` / `boolean()` / ... |
| Optional | `nil()` when absent, otherwise the inner value | `isNil()` to detect, otherwise the inner value |

`fieldBytes(nameBytes, fieldId)` is available for codecs that want to avoid `String` allocation on hot paths. Protobuf uses the numeric `fieldId`; JSON uses the name bytes. Codecs that do not care about one side can ignore it.

### A minimal codec

The smallest useful codec is a pair of classes extending `Codec.Writer` and `Codec.Reader`. Here is the skeleton for a format that writes each field's name followed by its value, one per line:

```scala
final class LinesWriter extends Codec.Writer:
    private val sb = StringBuilder()
    def objectStart(name: String, size: Int): Unit = ()
    def objectEnd(): Unit                          = ()
    def field(name: String, id: Int): Unit         = sb.append(name).append('=')
    def string(v: String): Unit                    = sb.append(v).append('\n')
    def int(v: Int): Unit                          = sb.append(v).append('\n')
    // ... implement the rest of Writer's methods
    def result(): Span[Byte] =
        Span.from(sb.toString.getBytes(StandardCharsets.UTF_8))
end LinesWriter

final class LinesReader(input: Span[Byte])(using val frame: Frame) extends Codec.Reader:
    // ... parse the input back into events
end LinesReader

final class Lines extends Codec:
    def newWriter(): Codec.Writer                                  = LinesWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader    = LinesReader(input)
```

Once a `given Lines = Lines()` is in scope, every schema method works with the new format:

```scala
given Lines = Lines()

Schema[User].encode[Lines](alice)          // Span[Byte] in the Lines format
Schema[User].decode[Lines](bytes)          // Result[DecodeException, User]
```

### Safety limits

`Codec.Reader` enforces two DoS limits that the base class applies automatically: `maxDepth` (nesting) and `maxCollectionSize` (entries per collection). Implementations call `checkDepth()` inside `objectStart`/`arrayStart`/`mapStart` and `checkCollectionSize(size)` when a collection reports its length. The `Frame` captured at construction time is used to attribute any `LimitExceededException` to the caller, not to the codec internals.

### Reference implementations

The `Json` and `Protobuf` codecs live next to the trait in the same package and are the recommended starting points for any new format. They demonstrate the full Writer/Reader contract, including the optimizations (`fieldBytes`, `initFields`, `matchField`, `release`) that are optional but useful on hot paths.
