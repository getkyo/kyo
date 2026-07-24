# kyo-schema

Define a case class and get JSON/YAML serialization, Protobuf and MessagePack encoding, field validation, type-safe lenses, structural diffs, and more, all derived from the type's structure. No required annotations, no boilerplate. Works across JVM, JavaScript, and Scala Native. The module depends only on `kyo-data` (pure data structures) and has no dependency on Kyo's effect runtime, so it can be adopted as a standalone library.

<!-- doctest:setup
```scala
case class Address(city: String, zip: String)
case class User(id: Int, name: String, email: String, password: String, address: Address) derives Schema
val alice = User(1, "Alice", "alice@example.com", "secret", Address("Portland", "97201"))
```
-->

```scala doctest:scope=nested
case class Address(city: String, zip: String)
case class User(id: Int, name: String, email: String, password: String, address: Address) derives Schema

val alice = User(1, "Alice", "alice@example.com", "secret", Address("Portland", "97201"))

Json.encode(alice)
// {"id":1,"name":"Alice","email":"alice@example.com","password":"secret","address":{"city":"Portland","zip":"97201"}}

Yaml.encode(alice)
// YAML 1.2-compatible text

Protobuf.encode(alice)
// Span[Byte] (binary)

Schema[User].focus(_.address.city).update(alice)(_.toUpperCase)
// User(1, "Alice", "alice@example.com", "secret", Address("PORTLAND", "97201"))
```

Everything flows from `Schema[A]`, the central type that captures a type's structure at compile time. It's the single source of truth that powers serialization, validation, navigation, and conversion.

The serialization format is chosen at the call site, not baked into the type. `Json.encode(value)`, `Ion.encode(value)`, `Protobuf.encode(value)`, and `MsgPack.encode(value)` summon the `Schema[A]` from implicit scope; a schema you reshaped or enriched only takes effect when you encode through that instance with `s.encode[Json](value)`.

These are the top-level entry points:

| Entry point | Purpose |
|-------------|---------|
| `Json` / `Ion` / `Yaml` / `Bson` / `Protobuf` / `MsgPack` | Serialize to JSON strings, Ion text or binary, YAML documents, BSON bytes, Protocol Buffers bytes, or MessagePack bytes |
| `Focus` | Type-safe lens for reading, writing, and updating fields at any depth |
| `Compare` | Read-only field-by-field comparison of two values |
| `Modify` | Batched field mutations applied as a single unit |
| `Changeset` | Serializable diff that can be stored, transmitted, and replayed |
| `Builder` | Incremental, type-safe case class construction |
| `Convert` | Bidirectional conversion between structurally compatible types |
| `Structure` | Runtime type description and untyped value trees |

## Module layout

The functionality above ships as seven artifacts: a format-agnostic core plus one small module per serialization format. `kyo-schema` alone gives you `Schema`, derivation, validation, and the optics (`Focus`, `Compare`, `Modify`, `Changeset`, `Builder`, `Convert`, `Structure`) with no codec; each format section below additionally requires its module's artifact on the classpath. Every format module pulls in the core transitively.

| Artifact | Provides |
|----------|----------|
| `kyo-schema` | The core: `Schema`, derivation, validation, annotations, optics, structural conversion, and the `Codec` SPI. No wire format. |
| `kyo-schema-json` | The `Json` entry point: JSON text and bytes, plus JSON Schema generation. |
| `kyo-schema-protobuf` | The `Protobuf` entry point: Protocol Buffers binary, plus `.proto` schema export. |
| `kyo-schema-msgpack` | The `MsgPack` entry point: MessagePack binary. |
| `kyo-schema-bson` | The `Bson` entry point: BSON document bytes. |
| `kyo-schema-ion` | The `Ion`, `IonBinary`, and `IonSchema` entry points: Amazon Ion text, binary, and Ion Schema generation. |
| `kyo-schema-yaml` | The `Yaml` entry point: YAML 1.2 documents, plus the CST and event APIs. |

## Installation

Add the format modules you need to your `build.sbt`. Most projects start with JSON:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-json" % "<latest version>"
```

`kyo-schema-json` brings in `kyo-schema` transitively; swap or add `kyo-schema-protobuf`, `kyo-schema-msgpack`, `kyo-schema-bson`, `kyo-schema-ion`, or `kyo-schema-yaml` for the other formats, or depend on `kyo-schema` alone for schemas without a codec.

All public types live in the `kyo` package:

```scala
import kyo.*
```

Schemas are derived automatically on demand, but adding `derives Schema` caches the derivation and reduces compilation time in larger projects:

```scala
case class Person(name: String, age: Int) derives Schema
```

**Note:** nearly every method in this module takes an implicit `kyo.Frame` parameter used for source-location reporting in exceptions. The signatures shown throughout this document omit `(using Frame)` for readability; the compiler synthesizes it at the call site from the caller's frame.

## Schemas: the single source of truth

Every API in this module reads from a `Schema[A]`. Once you have one, serialization, navigation, validation, and conversion are methods you call on it:

```scala doctest:scope=nested
case class Address(city: String, zip: String)
case class User(id: Int, name: String, email: String, password: String, address: Address) derives Schema

val alice = User(1, "Alice", "alice@example.com", "secret", Address("Portland", "97201"))

val schema =
    Schema[User]
        .check(_.name)(_.nonEmpty, "name must not be blank")
        .checkMin(_.id)(0)

Json.encode(alice)              // uses Schema[User] implicitly
schema.focus(_.name).get(alice) // "Alice"
schema.validate(alice.copy(name = "", id = -1))
// Chunk(
//   ValidationFailedException(path = List("name"), message = "name must not be blank", ...),
//   ValidationFailedException(path = List("id"),   message = "must be >= 0.0", ...)
// )
```

`validate` is never invoked automatically during encode or decode; constraints are enforced only when you call it explicitly.

Schemas compose: a case class whose fields already have a `Schema` gets one for free. Sealed traits, collections, tuples, and the built-in scalar types work the same way.

## Serialization

### JSON

`Json.encode` converts a value to a JSON string. `Json.decode` parses it back, returning a `Result` (Kyo's equivalent of `Either`, from `kyo-data`) rather than throwing:

```scala
val json: String = Json.encode(alice)
// {"id":1,"name":"Alice","email":"alice@example.com","password":"secret","address":{"city":"Portland","zip":"97201"}}

Json.decode[User](json)
// Result.Success(alice)

Json.decode[User]("""{"id":1,"name": 42}""")
// Result.Failure(TypeMismatchException(path = List("name"), expected = "string", actual = "number", ...))
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

Sealed traits support six wire representations, selected by a builder on the schema. The default is wrapper-object; the rest are opt-in:

| Representation | Builder | Wire shape for `Circle(10.0)` |
|---|---|---|
| Wrapper-object (default) | (none) | `{"Circle":{"radius":10.0}}` |
| Flat discriminator | `.discriminator("type")` | `{"type":"Circle","radius":10.0}` |
| Adjacent | `.adjacent("type","content")` | `{"type":"Circle","content":{"radius":10.0}}` |
| Tuple (tagged) | `.tupleTagged` | `["Circle",{"radius":10.0}]` |
| Tuple (flat) | `.tupleFlat` | `["Circle",10.0]` |
| Untagged | `.untagged` | `{"radius":10.0}` |

**Fallback chains.** A single representation must be expressible by the active codec or encode fails (see the Protobuf constraint below). To let one schema serve both a self-describing codec and Protobuf, declare an ordered fallback chain with `representations`; encode picks the highest-priority entry the codec can express and decode tries the chain in declared order. `orElseRepresentation(fallback)` is the single-fallback shorthand. `External` is always expressible and acts as the implicit chain floor, so it need not be listed explicitly. A chain containing a duplicate entry raises `DuplicateRepresentationException` at the builder call, not at encode time.

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

// tupleFlat where the codec can express a top-level array, else the always-expressible External wrapper
given Schema[Shape] = Schema[Shape].tupleFlat.orElseRepresentation(Schema.UnionRepresentation.External)

Json.encode[Shape](Rectangle(3.0, 4.0))
// ["Rectangle",3.0,4.0]

// Protobuf cannot express a top-level array, so it falls back to External
Protobuf.encode[Shape](Rectangle(3.0, 4.0))
// Span[Byte] (External wrapper-object form)
```

The tagged representations (`.discriminator`, `.adjacent`, `.tupleTagged`, `.tupleFlat`) route the tag through the variant-naming layer below, so `renameAllVariants`, `variantNames`, and `variantAlias` all apply to the emitted tag. `.untagged` carries no tag, so variant naming and aliases do not apply to it.

For a flat encoding with a type discriminator field, use `schema.discriminator`:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

given Schema[Shape] = Schema[Shape].discriminator("type")

Json.encode[Shape](Circle(5.0))
// {"type":"Circle","radius":5.0}

Json.encode[Shape](Rectangle(3.0, 4.0))
// {"type":"Rectangle","width":3.0,"height":4.0}
```

To map the wire discriminator value to a custom name, chain `variantNames` (explicit pairs) or `renameAllVariants` (a case convention). To rename every field by convention, use `renameAllFields`. Decode-only aliases accept alternate wire names on decode:

```scala
sealed trait Node
case class DList(headRef: String)  extends Node
case class Paragraph(text: String) extends Node

given Schema[Node] =
    Schema[Node].discriminator("kind")
        .renameAllVariants(Schema.NameCase.SnakeCase)
        .renameAllFields(Schema.NameCase.SnakeCase)
        .variantAlias("d_list", "dlist")

Json.encode[Node](DList("h1"))
// {"kind":"d_list","head_ref":"h1"}

Json.decode[Node]("""{"kind":"dlist","head_ref":"h1"}""")
// Result.Success(DList("h1"))
```

The case engine is acronym-aware: an uppercase run is one word. `DList` tokenizes as `D` + `List`, giving `d_list` under `SnakeCase`. The `Paragraph` variant needs no explicit mapping; `renameAllVariants(SnakeCase)` gives `paragraph`. The `variantAlias` call registers `dlist` as a decode-only alias for the variant whose primary wire name is `d_list`. A typo'd Scala variant name passed to `variantNames` raises `UnknownVariantException` at config time.

Variant naming applies only under `.discriminator(...)`. Without a discriminator the configuration has no effect and the default wrapper-object format is used.

**Adjacent** keeps the tag and payload in separate, named keys of one object. The payload is always an object, even for a single-field variant:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

given Schema[Shape] = Schema[Shape].adjacent("type", "content")

Json.encode[Shape](Circle(10.0))
// {"type":"Circle","content":{"radius":10.0}}
```

**Tuple (tagged)** encodes the sum as a two-element array: tag at index 0, payload object at index 1:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

given Schema[Shape] = Schema[Shape].tupleTagged

Json.encode[Shape](Circle(10.0))
// ["Circle",{"radius":10.0}]
```

**Tuple (flat)** encodes the sum as an array: tag at index 0, then each payload field value in declaration order. Use this representation when the variant type has only scalar fields and you want the most compact array form:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

given Schema[Shape] = Schema[Shape].tupleFlat

Json.encode[Shape](Rectangle(3.0, 4.0))
// ["Rectangle",3.0,4.0]
```

A record-typed field nests as one element rather than being deep-flattened: a variant like `Group(bounds: Rectangle)` encodes as `["Group",{"width":3.0,"height":4.0}]`, keeping the inner record whole.

**Untagged** emits only the variant payload, with no tag or wrapper. On decode, each variant is attempted in declaration order and the first clean parse wins:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

given Schema[Shape] = Schema[Shape].untagged

Json.encode[Shape](Circle(10.0))
// {"radius":10.0}

Json.decode[Shape]("""{"width":3.0,"height":4.0}""")
// Result.Success(Rectangle(3.0, 4.0))
```

Because there is no tag, variants whose fields overlap are resolved by declaration order: the first variant whose fields all match wins. When no variant matches, decode returns `Result.Failure(NoVariantMatchException(...))` listing the variants that were attempted.

Scala 3 type unions derive directly: `Schema[A | B]` is untagged by default, so it encodes the bare member payload and decodes by trying each member in declaration order. A type union has no nominal variant names, so the tagged representations (`discriminator`, `adjacent`) do not apply to it; only the untagged shape is available. When more than one member decodes the same wire value (a JSON number is both an `Int` and a `Long`), the default `Strict` policy fails with `AmbiguousVariantMatchException` listing the matched members rather than picking arbitrarily. Choose `unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)` to resolve a known-ambiguous union by declaration order instead.

```scala
val s: Schema[Int | Long] = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
given Schema[Int | Long]  = s

Json.decode[Int | Long]("42")
// Result.Success(42)  (Int wins as first-declared)
```

The array-shaped and bare representations (`.tupleTagged`, `.tupleFlat`, `.untagged`) require a self-describing codec. Encoding through Protobuf raises `RepresentationUnsupportedException` before any bytes are written. Wrapper-object, flat-discriminator, and adjacent all work on Protobuf.

**Field presence on the wire.** By default an absent optional encodes as a null-valued key and an empty collection as `[]` or `{}`. To drop a field from the wire, apply an omit policy. `omitNone` and `omitEmptyCollections` are schema-wide. Per field, `omit(_.field)` opens a policy you finish four ways: `.whenNone` (drop an absent optional), `.whenEmpty` (drop an empty collection or map), `.when(predicate)` (drop when a predicate over the field's encoded value returns true), and `.whenDefault` (drop when the value equals the field's compile-time default). A per-field policy shadows the schema-wide one for that field. Only optional and collection/map fields are affected by `whenNone`/`whenEmpty`; `when` and `whenDefault` apply to any field type. An empty product is never dropped. On decode, an omitted field defaults back to `None`, the typed-empty value, or its compile-time default, so the round-trip is preserved.

```scala
case class Cart(id: Int, items: List[String])

given Schema[Cart] = Schema[Cart].omit(_.items).whenEmpty

Json.encode(Cart(1, Nil))
// {"id":1}

Json.decode[Cart]("""{"id":1}""")
// Result.Success(Cart(1, List()))
```

`when` and `whenDefault` drop a field based on its encoded value rather than its emptiness. `when(predicate)` runs the predicate over the field's materialized `Structure.Value`; `whenDefault` drops the field when its value equals the compile-time default declared in the case class. A field with no compile-time default never omits under `whenDefault`:

```scala
case class Item(label: String, count: Int = 0)

given Schema[Item] = Schema[Item].omit(_.count).whenDefault

Json.encode(Item("widget", 0))
// {"label":"widget"}

Json.encode(Item("widget", 5))
// {"label":"widget","count":5}
```

Missing fields with default values are filled in automatically, which is useful for configuration types and backward-compatible evolution:

```scala
case class Config(host: String, port: Int = 8080, ssl: Boolean = false)

Json.decode[Config]("""{"host":"localhost"}""")
// Result.Success(Config("localhost", 8080, false))
```

When a field is absent and has no compile-time default, decode fails with `MissingFieldException`. To supply a fallback at decode time without changing the case class, register one with `default(_.field)(supplier)`. The supplier is by-name, evaluated at most once per decode and only when the field is missing; a present field never runs it. A registered supplier takes precedence over a Scala `=` default, which in turn takes precedence over the field's tag zero value:

```scala
case class Account(id: Int, name: String)

given Schema[Account] = Schema[Account].default(_.name)("guest")

Json.decode[Account]("""{"id":1}""")
// Result.Success(Account(1, "guest"))

Json.decode[Account]("""{"id":1,"name":"ada"}""")
// Result.Success(Account(1, "ada"))
```

For raw bytes, `Json.encodeBytes` and `Json.decodeBytes` work with `Span[Byte]` (the immutable byte sequence from `kyo-data`) instead of `String`:

```scala
val bytes: Span[Byte] = Json.encodeBytes(alice)
Json.decodeBytes[User](bytes)
// Result.Success(alice)
```

When accepting untrusted input, configure safety limits to protect against denial-of-service attacks. `maxDepth` limits nesting depth (default `Codec.DefaultMaxDepth`, currently `512`) and `maxCollectionSize` limits the number of entries in any single collection or object (default `Codec.DefaultMaxCollectionSize`, currently `100000`):

```scala
val untrustedInput = """{"id":1,"name":"Alice","email":"a@b.com","password":"s","address":{"city":"Portland","zip":"97201"}}"""
Json.decode[User](untrustedInput, maxDepth = 64, maxCollectionSize = 10000)
```

Exceeding either limit returns `Result.Failure(LimitExceededException)`. `LimitExceededException` is a subtype of `DecodeException`, so the same pattern-match handles malformed input and limit breaches.

**Strict decoding.** By default decode ignores input fields the schema does not name, so unknown keys are silently discarded. `denyUnknownFields` flips this: decode rejects the first input field absent from the schema's effective read set with `Result.Failure(UnknownFieldException(...))`. The check runs after field naming, aliases, and schema transforms are applied, so renamed and aliased wire names are accepted while a renamed-away source name is rejected. The policy is read-side only; encode output is unchanged:

```scala
case class Person(id: Int, name: String)

val strict = Schema[Person].denyUnknownFields

strict.decodeString[Json]("""{"id":1,"name":"Ada"}""")
// Result.Success(Person(1, "Ada"))

strict.decodeString[Json]("""{"id":1,"name":"Ada","extra":true}""")
// Result.Failure(UnknownFieldException(...))  (rejects the first unknown field)
```

### Ion

`Ion.encode` converts a value to Amazon Ion text. Case classes become structs, collections become lists, `Map[String, V]` becomes a struct, and `Span[Byte]` becomes an Ion blob:

```scala
val ion: String = Ion.encode(alice)
// {id:1,name:"Alice",email:"alice@example.com",password:"secret",address:{city:"Portland",zip:"97201"}}

Ion.decode[User](ion)
// Result.Success(alice)

Ion.encode(Span.from("hello".getBytes("UTF-8")))
// {{aGVsbG8=}}
```

The reader accepts the Ion text features most useful for schema-shaped data: unquoted or quoted field names, comments, annotations, typed nulls, blobs, long strings, and symbol values decoded as strings:

```scala
Ion.decode[User](
    """user::{
      |  id: 1,
      |  name: "Alice",
      |  email: "alice@example.com",
      |  password: "secret",
      |  address: {city: Portland, zip: "97201"},
      |}""".stripMargin
)
// Result.Success(alice)
```

Ion type annotations are accepted as input syntax and ignored as metadata during schema decoding. By default (`Ion.AnnotationEmissionMode.Suppress`), they are not preserved by `Ion.decode` or emitted by `Ion.encode`. Setting `annotationEmissionMode = Ion.AnnotationEmissionMode.Emit` on `Ion.Config` makes `Ion.encode` emit captured schema annotations as Ion type annotations instead.

`Ion.decode` and `Ion.decodeBytes` accept the same `maxDepth` and `maxCollectionSize` safety limits as `Json.decode`.

`Ion.Config` also selects the wire format: setting `format = Ion.Format.Binary` routes the byte helpers through Ion Binary, a self-describing binary encoding for the same Ion data model, instead of text:

```scala
val config            = Ion.Config(format = Ion.Format.Binary)
val bytes: Span[Byte] = Ion.encodeBytes(alice, config)

Ion.decode[User](bytes, config)
// Result.Success(alice)
```

`IonBinary` exposes the same binary encoding as a standalone byte-oriented entry point (`IonBinary.encode`/`IonBinary.decode`), useful when code selects a codec value directly rather than through `Ion.Config`. An Ion Schema Language document describing a type's wire shape can also be derived from its `Schema`; see the Ion Schema section under Validation.

### BSON

BSON is the document-oriented binary format used by MongoDB. Its top-level value must be object-shaped: a case class or a string-keyed map. `Bson.encode` produces `Span[Byte]`; `Bson.decode` reads it back:

```scala
val bytes: Span[Byte] = Bson.encode(alice)

Bson.decode[User](bytes)
// Result.Success(alice)
```

Case classes encode as a BSON document keyed by field name, collections as BSON arrays, `Span[Byte]` as BSON binary, and `java.time.Instant` as the BSON UTC datetime type. BSON's datetime type is millisecond-precision only; encoding an `Instant` carrying sub-millisecond precision raises `SchemaNotSerializableException`. A `BigInt` field encodes only within the signed 64-bit range that BSON's int64 type supports; a value outside that range fails encoding with `SchemaNotSerializableException` rather than being truncated. `BigDecimal` has no such limit: it encodes losslessly up to 34 decimal digits through the BSON Decimal128 type.

`Bson.decode` accepts the same `maxDepth` and `maxCollectionSize` safety limits as `Json.decode`, configurable through `Bson.Config`:

```scala
val config = Bson.Config(maxDepth = 8, maxCollectionSize = 64)

Bson.decode[User](Bson.encode(alice, config), config)
// Result.Success(alice)
```

### YAML

`Yaml.decode` parses one YAML document into a typed value and returns `Result[DecodeException, A]`. For document streams, use `Yaml.decodeAll`, or pass `Yaml.DocumentIndex(n)` to target one zero-based document without decoding the whole stream. Use `Yaml.ReaderConfig` when you need document selection, stream-fragment merging, decode limits, or YAML 1.1 scalar resolution for legacy systems.

```scala
val yaml =
    """id: 1
      |name: Alice
      |email: alice@example.com
      |password: secret
      |address:
      |  city: Portland
      |  zip: "97201"
      |""".stripMargin

Yaml.decode[User](yaml)
// Result.Success(alice)

val stream =
    """---
      |id: 0
      |name: Bob
      |email: bob@example.com
      |password: secret
      |address:
      |  city: Seattle
      |  zip: "98101"
      |---
      |id: 1
      |name: Alice
      |email: alice@example.com
      |password: secret
      |address:
      |  city: Portland
      |  zip: "97201"
      |""".stripMargin

Yaml.decode[User](stream, Yaml.DocumentIndex(1))
// decodes the second document in the stream

Yaml.decode[User](
    stream,
    Yaml.ReaderConfig(
        documentIndex = Maybe(Yaml.DocumentIndex(1)),
        maxDepth = 64,
        maxCollectionSize = 1024,
        yamlVersion = Yaml.SpecVersion.Yaml11
    )
)
```

`Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings` treats non-empty documents in a stream as fragments of one top-level mapping. This is useful for configuration files that split one case class, sealed trait wrapper, or Scala 3 enum case across document separators:

```scala
val splitStream =
    """---
      |id: 1
      |name: Alice
      |---
      |email: alice@example.com
      |password: secret
      |---
      |address:
      |  city: Portland
      |  zip: "97201"
      |""".stripMargin

Yaml.decode[User](
    splitStream,
    Yaml.ReaderConfig(
        documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings
    )
)
```

`Yaml.encode` writes YAML 1.2 by default. Use `Yaml.WriterConfig` profiles and `yamlVersion` when writing for systems that still use YAML 1.1 implicit scalar rules.

For YAML-specific tooling, `Yaml.Events` exposes parser and writer events without requiring a YAML node tree. This is useful for checks and transforms that care about anchors, aliases, tags, document boundaries, scalar styles, or source marks. Ordinary schema decoding and encoding should still use `Yaml.decode` and `Yaml.encode`; use `Yaml.pipeline` when middleware needs to sit between YAML parsing and schema decoding.

#### YAML events

Events can be collected, transformed, rendered, or produced from schema values. This example uppercases every scalar from a YAML parser stream and renders the transformed events back to YAML:

```scala
val renderer = Yaml.Events.Renderer()
val uppercase =
    Yaml.Events.Processor.mapScalars[DecodeException] { (value, meta) =>
        Result.succeed((value.toUpperCase, meta))
    }

val rewritten =
    Yaml.Events.visit("name: Alice\n", ())(uppercase.andThen(renderer))
        .map(_ => renderer.resultString)

assert(rewritten == Result.succeed("NAME: ALICE\n"))
```

The same event protocol can be driven from schema output with `Yaml.Events.write`, so YAML-specific tooling can sit on either side of the schema layer.

#### YAML pipelines

`Yaml.pipeline` composes event processors with schema operations. With no processors it delegates to the normal `Yaml.decode` and `Yaml.encode` fast paths. With processors, `decode` reads the transformed event stream directly into `Schema[A]`; it does not render YAML text first.

```scala
case class PublicUser(name: String, age: Int) derives Schema

val legacyYaml =
    """fullName: Alice
      |age: 30
      |""".stripMargin

val renameFullName =
    Yaml.Events.Processor.mapScalars[DecodeException] { (value, meta) =>
        val next =
            if value == "fullName" then "name"
            else value
        Result.succeed((next, meta))
    }

val decoded =
    Yaml.pipeline
        .through(renameFullName)
        .decode[PublicUser](legacyYaml)

assert(decoded == Result.succeed(PublicUser("Alice", 30)))
```

Use `render` when the transformed YAML document itself is the desired output:

```scala
val renameFullName =
    Yaml.Events.Processor.mapScalars[DecodeException] { (value, meta) =>
        val next =
            if value == "fullName" then "name"
            else value
        Result.succeed((next, meta))
    }

val rendered =
    Yaml.pipeline
        .through(renameFullName)
        .render("fullName: Alice\nage: 30\n")

assert(rendered == Result.succeed("name: Alice\nage: 30\n"))
```

#### YAML CST

`Yaml.cst` is an opt-in concrete syntax tree for structural YAML tools. It keeps source text, comments, whitespace, node syntax, anchors, tags, and source marks so unchanged documents render from the original source, and edits can preserve nearby comments and trivia while changed regions render canonically. Schema decode and encode remain the fast paths for typed values.

```scala
val source =
    """# service owner
      |name: Alice # current
      |active: true
      |""".stripMargin

val replacement =
    Yaml.Cst.from("Bob").getOrThrow.root.get

val edited =
    Yaml.cst(source).getOrThrow
        .replace(Yaml.Cst.Path.root / "name", replacement)
        .getOrThrow

assert(
    edited.render(using Yaml.WriterConfig.Default) ==
        """# service owner
          |name: Bob # current
          |active: true
          |""".stripMargin
)
```

The assertion shows that the `name` value changed while the owner comment, inline comment, and `active` entry stayed in place.

Pipeline CST helpers use the same event middleware as `decode` and `render`. With no middleware, `Yaml.pipeline.cst(input)` delegates to source-backed CST parsing. With middleware, the pipeline materializes transformed events into a canonical CST, which is useful for structural tooling that wants transformed YAML without schema decoding or building a `Yaml.Node` tree.

```scala
val renameFullName =
    Yaml.Events.Processor.mapScalars[DecodeException] { (value, meta) =>
        val next =
            if value == "fullName" then "name"
            else value
        Result.succeed((next, meta))
    }

val doc =
    Yaml.pipeline
        .through(renameFullName)
        .cst("fullName: Alice\n")
        .getOrThrow

assert(doc.source.isEmpty)
assert(doc.render(using Yaml.WriterConfig.Default) == "name: Alice\n")
```

The empty source proves the CST came from transformed events rather than the original bytes, and the render assertion proves the scalar rename happened.

A CST document can also serve as the decode source directly. `Yaml.decode[A](doc)` reads the CST's event stream through the schema without re-parsing YAML text. `throughCst` composes a structural edit as a pipeline stage so the same pipeline can both decode the edited values and render the result with comments preserved:

```scala
val cfgSource =
    """# deployment
      |services:
      |  api:
      |    image: app:v1
      |""".stripMargin

// Decode straight from a CST document
val cfgDoc = Yaml.cst(cfgSource).getOrThrow
val cfgDecoded =
    Yaml.decode[Map[String, Map[String, Map[String, String]]]](cfgDoc)
assert(cfgDecoded.isSuccess)

// Edit via throughCst (comments preserved), then render the result
val imageV2 = Yaml.Cst.from("app:v2").getOrThrow.root.get
val bumped =
    Yaml.pipeline
        .throughCst(
            _.replace(
                Yaml.Cst.Path.root / "services" / "api" / "image",
                imageV2
            )
        )
        .render(cfgSource)
        .getOrThrow

assert(bumped.contains("app:v2"))
assert(bumped.contains("# deployment"))
```

The comment assertion holds because `throughCst` builds a source-backed CST from the input, applies the structural edit, and renders through the trivia-aware renderer. Nodes that were not edited retain their original text, so the leading `# deployment` comment survives.

For richer examples, including an anchor audit that finds undeclared aliases and unused anchors, direct pipeline decode of case classes and ADTs, and a complete node-builder with a custom error hierarchy, see [YamlEventsTest.scala](../kyo-schema-yaml/shared/src/test/scala/kyo/YamlEventsTest.scala) and [YamlPipelineTest.scala](../kyo-schema-tests/shared/src/test/scala/kyo/YamlPipelineTest.scala). When callers do want a tree, `Yaml.parse` builds one explicitly.

### Protobuf

The same types that serialize to JSON also serialize to Protocol Buffers:

```scala
val bytes: Span[Byte] = Protobuf.encode(alice)

Protobuf.decode[User](bytes)
// Result.Success(alice)
```

No annotations, no `.proto` files. Each field gets a stable numeric ID derived from its name via XXH32. Adding, removing, or reordering fields in the case class does not break existing serialized data, because IDs are name-based rather than position-based.

The hashed IDs occupy a 21-bit range (~2 million values). For schemas with a few dozen fields, accidental collisions are statistically negligible but not impossible. If you need certainty (for example, for fields that must round-trip across independently versioned services), you can pin IDs explicitly.

For interoperability with existing `.proto` definitions, custom IDs can be assigned. Pin critical fields with `fieldId`:

```scala
val schema =
    Schema[User]
        .fieldId(_.id)(1)
        .fieldId(_.name)(2)
```

The same pin can be declared inline on the field with `@proto.fieldNumber(n)`, the recommended way to fix Protobuf field numbers for strict external interop. The annotation desugars onto the same override during derivation, so `@proto.fieldNumber(1) id: Long` and `.fieldId(_.id)(1)` produce identical wire numbers; see [Protobuf field numbers](#protobuf-field-numbers) under Annotations.

`Protobuf.decode` accepts the same `maxDepth` and `maxCollectionSize` safety limits as `Json.decode`.

Maps encode as a standard proto3 `map<K, V>`: each entry is a `MapEntry` message (key at field 1, value at field 2) repeated under the map field. A key type proto3 admits as a map key (an integral, `Boolean`, or `String` scalar, or an opaque type / value class whose `Schema` reduces to one) yields an interoperable `map<K, V>`. Any other key type (a message, `Float`, or `Double` key) cannot form a proto3-native `map<K, V>`. Under the default `Conformance.Strict` mode, encoding such a map raises `SchemaNotSerializableException` with a message prefixed `"non-canonical proto3 map key: "`. Under `Conformance.Permissive`, the map uses an entry-message encoding that round-trips through this codec but is not standard proto3 externally, and `protoSchema` describes the field as `repeated <Name>Entry` rather than the invalid `map<...>` syntax.

Repeated and map fields follow proto3 emptiness: an empty `List`/`Seq`/`Map`/... emits no bytes, and an absent repeated or map field decodes to the empty collection rather than failing, so collection fields are not required on the wire.

Repeated scalar fields (`List[Int]`, `Vector[Double]`, `Set[Boolean]`, and so on) are packed into a single length-delimited record, matching proto3's canonical form. The reader accepts both packed and unpacked forms. Self-consistent round-trips (kyo encode then decode) always work; an externally-produced zero-length packed scalar record is byte-identical to an empty string element on the same field number, so that specific external wire form is not distinguished.

The proto3 conformance mode is `Conformance.Strict` by default: the zero-argument `given Protobuf` rejects any shape that cannot be encoded in canonical proto3. To allow the non-canonical extensions that still round-trip through this codec, supply an explicit instance:

```scala
given Protobuf = Protobuf(Protobuf.Config(conformance = Protobuf.Conformance.Permissive))
```

`Protobuf.protoSchema[A]` generates a `.proto` definition as a string:

```scala
val proto = Protobuf.protoSchema[User]
// syntax = "proto3";
//
// message Address {
//   string city = 1521334;  // hash-derived field number; pin via Schema.fieldId for stable external interop
//   string zip = 176885;  // hash-derived field number; pin via Schema.fieldId for stable external interop
// }
//
// message User {
//   sint32 id = 198960;  // hash-derived field number; pin via Schema.fieldId for stable external interop
//   string name = 1684051;  // hash-derived field number; pin via Schema.fieldId for stable external interop
//   string email = 1928090;  // hash-derived field number; pin via Schema.fieldId for stable external interop
//   string password = 48118;  // hash-derived field number; pin via Schema.fieldId for stable external interop
//   Address address = 209473;  // hash-derived field number; pin via Schema.fieldId for stable external interop
// }
```

The field numbers in the generated `.proto` match the wire field numbers the codec writes: each is derived from the field name (XXH32 applied to the name's JLS string hash), the same number used at encode and decode. Hash-derived fields carry a provenance comment. Pinning a field with `fieldId` removes the provenance comment and writes the pinned number on the wire instead. A hash-derived number in proto3's reserved range (19000-19999) escalates the comment to a WARNING because external `protoc` rejects numbers in that band. The specific numbers in the examples above (1521334, 176885, and so on) are the hash-derived values for those field names and are shown for illustration; the actual values for your types are computed at compile time from field names via the same formula.

`Protobuf.fieldNumberAudit[A]` returns one `FieldNumberInfo` per message field, depth-first, reporting the wire field number, a `pinned` flag, and an `inReservedRange` flag without performing any encode or decode:

```scala
val audit = Protobuf.fieldNumberAudit[User]
// Chunk.Indexed(
//   FieldNumberInfo(id,id,198960,false,false),       // numbers are hash-derived for these field names
//   FieldNumberInfo(name,name,1684051,false,false),
//   FieldNumberInfo(email,email,1928090,false,false),
//   FieldNumberInfo(password,password,48118,false,false),
//   FieldNumberInfo(address,address,209473,false,false),
//   FieldNumberInfo(address.city,city,1521334,false,false),
//   FieldNumberInfo(address.zip,zip,176885,false,false)
// )
```

A few shapes that proto3 cannot express raise `IllegalArgumentException` at encode or `protoSchema` time: `Option[Option[_]]`, `List[Option[_]]`, `List[List[_]]`, `List[Map[_,_]]`, and `Unit`-typed fields. `BigInt` and `BigDecimal` serialize as proto3 `string`, since proto3 has no arbitrary-precision number type; this preserves exact round-trip values.

Protobuf supports the wrapper-object, flat-discriminator, and adjacent sum representations. The array-shaped and bare representations (`.tupleTagged`, `.tupleFlat`, `.untagged`) need a self-describing codec; encoding one through Protobuf raises `RepresentationUnsupportedException` before any bytes are written.

### MsgPack

MessagePack is a compact, self-describing binary format. Like Protobuf it produces `Span[Byte]`, but every value carries its own type tag on the wire, so the bytes are readable by any standard MessagePack decoder without the schema:

```scala
val bytes: Span[Byte] = MsgPack.encode(alice)

MsgPack.decode[User](bytes)
// Result.Success(alice)
```

Case classes encode as a MessagePack map keyed by field name, collections as arrays, `Option`/`Maybe` as the value or `nil`, and `Span[Byte]` as a binary blob. `MsgPack.decode` accepts the same `maxDepth` and `maxCollectionSize` safety limits as `Json.decode`.

The wire shape is configurable through `MsgPack.Config`. For a more compact payload, switch field keys from names to the same stable XXH32 IDs the Protobuf codec uses:

```scala
given MsgPack = MsgPack(MsgPack.Config(keyEncoding = MsgPack.KeyEncoding.FieldId))

MsgPack.decode[User](MsgPack.encode(alice))
// Result.Success(alice)
```

`KeyEncoding.FieldId` trades self-description for size. Dynamic `Map` keys and the `Result`/`Either` discriminators always stay strings, since a hash is not reversible.

`Instant` defaults to a lossless `[seconds, nanos]` array; `InstantEncoding.Extension` writes the spec-defined MessagePack timestamp extension (type -1), which standard MessagePack decoders in other languages read as a timestamp:

```scala
given MsgPack = MsgPack(MsgPack.Config(instantEncoding = MsgPack.InstantEncoding.Extension))

MsgPack.decode[User](MsgPack.encode(alice))
// Result.Success(alice)
```

`Duration` has no MessagePack standard, so `DurationEncoding` lets you choose: `Lossless` (default, a `[seconds, nanos]` array keeping the full `java.time.Duration` range) or `Compat` (a string of total nanoseconds, wire-compatible with upickle/weePickle, limited to the `Long` nanosecond range). Schemas are provided for both `java.time.Duration` and `scala.concurrent.duration.{Duration, FiniteDuration}`. The reader auto-detects the wire shape either way:

```scala
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

case class Timeout(connect: FiniteDuration, idle: Duration) derives Schema

given MsgPack = MsgPack(MsgPack.Config(durationEncoding = MsgPack.DurationEncoding.Compat))

val t = Timeout(5.seconds, Duration.Inf)
MsgPack.decode[Timeout](MsgPack.encode(t))
// Result.Success(Timeout(5 seconds, Duration.Inf))
```

`scala.concurrent.duration.Duration` (the possibly-infinite type) always uses the string form so it can carry `Inf`/`MinusInf`/`Undefined`; `FiniteDuration` and `java.time.Duration` follow the `DurationEncoding` setting.

Because MessagePack is self-describing, its reader can materialize an arbitrary payload into a `Structure.Value` without a schema. This makes MsgPack a binary transport for open-shaped wire protocols, the role JSON plays for JSON-RPC: an envelope can hold a `Structure.Value` slot whose concrete type is decided per message.

```scala
val bytes: Span[Byte] = MsgPack.encode(alice)

summon[Schema[Structure.Value]].decode[MsgPack](bytes)
// Result.Success(Structure.Value.Record(...))
```

### Built-in Types

Schemas are provided for all common types out of the box:

| Category | Types |
|----------|-------|
| Primitives | `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, `Short`, `Byte`, `Char`, `BigDecimal`, `BigInt`, `Unit` |
| Time | `java.time.Instant`, `java.time.Duration`, `kyo.Instant`, `kyo.Duration`, `LocalDate`, `LocalTime`, `LocalDateTime` |
| Identifiers | `kyo.UUID`, `java.util.UUID`, `Frame`, `Tag[A]` |
| Collections | `List[A]`, `Vector[A]`, `Set[A]`, `Seq[A]`, `Chunk[A]`, `Span[A]`, `Map[String, V]`, `Dict[K, V]`, `OrderedDict[K, V]` |
| Optional | `Option[A]`, `Maybe[A]` |
| Sums | `Either[A, B]`, `Result[E, A]` |
| Tuples | `(A, B)`, `(A, B, C)`, `(A, B, C, D)`, `(A, B, C, D, E)` |

Prefer `kyo.UUID` in new schemas. Its primary built-in schema is `Schema.uuidSchema`; it writes canonical lowercase UUID text and reads through `UUID.parse`. `java.util.UUID` remains available for Java-facing compatibility through `Schema.javaUuidSchema`. Both use a string wire representation, but their runtime schema tags remain type-specific.

Any case class or sealed trait composed of these types derives a `Schema` automatically. Nested case classes work without additional setup.

`OrderedDict[K, V]` serializes in the same shape as `Dict[K, V]` and additionally preserves insertion order across an encode/decode round-trip: encoding walks the map in insertion order, and decoding rebuilds it by inserting entries in wire order.

`Map[String, V]` and `Dict[String, V]` both serialize as JSON objects, because JSON object keys must be strings. `Map[K, V]` and `Dict[K, V]` with a non-string key type serialize as an array of two-field `{key, value}` records, which the Protobuf codec renders as a standard proto3 `MapEntry`. `Span[Byte]` is specialized to serialize as a primitive byte sequence rather than an array of individual bytes.

> **Note:** a map entry whose value is an empty collection can decode incorrectly on the Protobuf codec. proto3 has no representation for an empty `repeated` field, so the entry is written without its value and the decode reads whatever follows in its place, which either fails or yields a wrong value. Entries whose values are non-empty are unaffected, and the other codecs are unaffected. This is a shared codec defect in the `mapSchema`, `dictSchema`, and `orderedDictSchema` givens alike, not a property of any one map type. It is tracked in [getkyo/kyo#1747](https://github.com/getkyo/kyo/issues/1747) and will be fixed in a follow-up.

### Custom Types

For opaque types, assign the schema for the underlying type inside the companion object where the type boundary is transparent:

```scala
opaque type Email = String

object Email:
    def apply(s: String): Email = s
    given Schema[Email]         = Schema[String]
```

Inside the companion, `Email` is `String` to the compiler, so `Schema[String]` satisfies `Schema[Email]` without any conversion. Outside the companion the types are distinct, so the given must live inside where the boundary is visible.

For types that need a non-trivial conversion, use `Schema[Underlying].transform[MyType](to)(from)`:

```scala
opaque type Username = String

object Username:
    def apply(s: String): Username = s.toLowerCase
    given Schema[Username] =
        Schema[String].transform[Username](Username(_))(identity)
end Username
```

## Annotations

Everything the serialization sections configure with builder calls (variant representations, field and variant renaming, decode aliases, conditional omission, custom field codecs) can also be declared inline on the type with annotations from `kyo.schema`. `derives Schema` reads them and desugars each onto the same programmatic configuration, so an annotated type and the equivalent builder chain produce identical schemas. Annotations are opt-in: a type with none derives a byte-identical schema, and when an annotation and a programmatic call configure the same field, the programmatic call wins.

```scala
import kyo.schema.*
```

The annotations compose. A single order event can rename its identifier, document a field, omit an optional field while it is empty, and keep a server-only field off the wire:

```scala doctest:scope=nested
import kyo.schema.*

case class Order(
    @rename("order_id") @alias("id") orderId: Long,
    @doc("Customer-facing label") label: String,
    @omit(omit.WhenNone) note: Maybe[String] = Maybe.empty,
    @transient internalToken: String = ""
) derives Schema

Json.encode(Order(7L, "Boots", Maybe.empty, "tok-123"))
// {"order_id":7,"label":"Boots"}

Json.decode[Order]("""{"id":7,"label":"Boots"}""")
// Result.Success(Order(7, "Boots", Absent, ""))
```

`@rename` sets the wire key used on encode and the primary key accepted on decode. `@alias` registers extra names accepted on decode only, so the legacy `id` key still parses. `@omit(omit.WhenNone)` drops `note` from the output while it is `Maybe.empty` and rebuilds it from the field default on decode. `@transient` always drops `internalToken` on encode and reconstructs it from its Scala default. `@doc` attaches field documentation that `Json.jsonSchema` reports as the property description; it never reaches the wire.

### Renaming and aliases

`@rename(wireName)` changes a field's wire key; `@alias(names*)` adds decode-only names against the effective key. Once a field is renamed, input using the original Scala name no longer decodes unless an `@alias` re-admits it. A field alias that collides with another field's wire name raises `FieldNameCollisionException` at schema construction.

```scala doctest:scope=nested
import kyo.schema.*

case class Account(@rename("user_name") @alias("login") userName: String) derives Schema

Json.encode(Account("ada"))
// {"user_name":"ada"}

Json.decode[Account]("""{"login":"ada"}""")
// Result.Success(Account("ada"))
```

### Omitting fields

`@omit` controls when a field is left off the wire. The mode is an `omit.Mode` value passed as the argument; the no-arg form is type-aware. An omitted field is reconstructed from its Scala default (or `Maybe.empty` for optional fields) on decode.

| Annotation | Omits the field when |
|---|---|
| `@omit` | type-aware: an absent `Maybe`/`Option`, or an empty collection |
| `@omit(omit.WhenNone)` | the value is `Maybe.empty` / `None` |
| `@omit(omit.WhenEmpty)` | the value is an empty collection or map |
| `@omit(omit.WhenDefault)` | the value equals the field's Scala default (the field must declare one) |
| `@omit(omit.When(pred))` | the supplied `OmitPredicate` returns true for the value |

`@omit(omit.When(pred))` runs a custom predicate. Implement `OmitPredicate` as a named object; `test` receives the field's materialized `Structure.Value`:

```scala doctest:scope=nested
import kyo.schema.*

object DropZero extends OmitPredicate:
    def test(value: Structure.Value): Boolean =
        value match
            case Structure.Value.Integer(n) => n == 0L
            case _                          => false
end DropZero

case class Reading(@omit(omit.When(DropZero)) celsius: Int = 0) derives Schema

Json.encode(Reading(0))
// {}

Json.encode(Reading(21))
// {"celsius":21}
```

`@transient` is the unconditional sibling: it always drops the field on encode and requires a Scala default to rebuild it on decode. Unlike `@omit`, which may or may not emit the field depending on its value, `@transient` is never present on the wire.

### Sum representations

For sealed traits, `@discriminator`, `@adjacent`, and `@untagged` select the wire encoding declaratively, matching the `.discriminator`, `.adjacent`, and `.untagged` builders. They are sealed-trait-only; placing one on a case class is a compile error. `@rename` and `@alias` on a variant set and extend its tag value.

```scala doctest:scope=nested
import kyo.schema.*

@discriminator("type") sealed trait Event derives Schema
@rename("created") case class Created(id: Long) extends Event derives Schema
case class Deleted(id: Long)                    extends Event derives Schema

Json.encode[Event](Created(1L))
// {"type":"created","id":1}

Json.decode[Event]("""{"type":"created","id":1}""")
// Result.Success(Created(1))
```

`@adjacent(tagKey, contentKey)` carries the tag and payload in separate keys; `@untagged()` writes each variant as its bare payload and decodes by trying variants in declaration order. Untagged decoding requires a self-describing codec and fails on Protobuf, exactly like the `.untagged` builder.

### Custom field codecs

`@transform(obj)` installs a custom codec for one field. The object extends one of the `Transformer` family: `Transformer.Full` for custom read and write, `Transformer.WriteOnly` for custom write only, or `Transformer.ReadOnly` for custom read only. The argument must be a stable object reference, not a closure.

```scala doctest:scope=nested
import kyo.schema.*

object Hex extends Transformer.Full[Int]:
    def write(value: Int, writer: Codec.Writer): Unit = writer.string(value.toHexString)
    def read(reader: Codec.Reader): Int               = java.lang.Integer.parseInt(reader.string(), 16)

case class Packet(@transform(Hex) code: Int) derives Schema

Json.encode(Packet(255))
// {"code":"ff"}

Json.decode[Packet]("""{"code":"ff"}""")
// Result.Success(Packet(255))
```

### Protobuf field numbers

Format-specific annotations are scoped under an object named for their wire format. `@proto.fieldNumber(n)` is the first: it pins the Protobuf field number for a field, overriding the hash-derived default, and is the recommended way to fix numbers for strict interoperability with an external `.proto` definition. The annotation desugars onto the same single-segment override `Schema[A].fieldId(_.field)(n)` produces, so encode, decode, `Protobuf.protoSchema`, and `Protobuf.fieldNumberAudit` all honor it. A programmatic `.fieldId` applied after derivation still wins, consistent with the rest of the annotation set.

```scala doctest:scope=nested
import kyo.schema.*

case class Payment(@proto.fieldNumber(1) amount: Long, currency: String) derives Schema

Schema[Payment].fieldId("amount")
// 1
```

Proto field numbers are positive (`>= 1`); pin a stable number to keep a field out of proto3's reserved band (19000-19999), which external tooling rejects. `Protobuf.fieldNumberAudit` reports a pinned field with `pinned = true`. Schema-general annotations (`@rename`, `@doc`, `@discriminator`, ...) stay flat; only format-specific ones take a scope.

### Reading annotations back

Every built-in annotation subtypes `SchemaAnnotation`, and so can any annotation you define. The derivation always reifies `SchemaAnnotation` instances onto `schema.structure`, where tooling reads them by type. The extractors `annotationOf[A]` and `annotationsOf[A]` work on a product type, a sum type, a field, or a variant; `fieldsWith[A]` and `variantsWith[A]` pair each carrier with its annotation:

```scala doctest:scope=nested
import kyo.schema.*

case class Profile(@rename("user_name") name: String, @doc("age in years") age: Int) derives Schema

val product = Schema[Profile].structure.asInstanceOf[Structure.Type.Product]

product.fields.fieldsWith[rename].map((field, r) => field.name -> r.wireName)
// Chunk(("name", "user_name"))

product.fields.fieldsWith[doc].map((field, d) => field.name -> d.text)
// Chunk(("age", "age in years"))
```

A custom annotation subtyping `SchemaAnnotation` is captured the same way, ready for a codec author to drive behavior from the captured instance:

```scala doctest:scope=nested
import kyo.schema.*

final class pii() extends SchemaAnnotation

case class Customer(@pii ssn: String, name: String) derives Schema

Schema[Customer].structure.asInstanceOf[Structure.Type.Product]
    .fields.fieldsWith[pii].map(_._1.name)
// Chunk("ssn")
```

Annotations that do not subtype `SchemaAnnotation` (third-party markers such as validation annotations) are captured according to an `AnnotationPolicy` summoned at derivation. The default admits every annotation except a fixed compiler-noise set. Place an `inline given` in derivation scope to narrow capture, for example `inline given AnnotationPolicy = AnnotationPolicy.markersOnly` to keep only `SchemaAnnotation` subtypes. Marker subtypes are always captured and never consult the policy.

## Validation

The same `Schema[A]` that serializes also validates. Each constraint enforces a rule at runtime and records it as JSON Schema metadata, so your validation logic and API spec stay in sync automatically.

### Constraints

Constraints are attached with a focus lambda. The `_.price` lambda picks which field the constraint targets; you will see the same `_.field` syntax for lenses, diffs, and transforms later in this document. Numeric constraints apply to any field with a `Numeric` instance:

```scala
case class Product(name: String, price: Double, quantity: Int)

val schema =
    Schema[Product]
        .checkMin(_.price)(0.0)      // price >= 0.0
        .checkMax(_.price)(99999.99) // price <= 99999.99
        .checkMin(_.quantity)(1)     // quantity >= 1
        .checkMax(_.quantity)(1000)  // quantity <= 1000
```

`checkExclusiveMin` and `checkExclusiveMax` use strict inequality (`>` and `<`).

String constraints validate length and format:

```scala
val schema =
    Schema[User]
        .checkMinLength(_.name)(3)             // at least 3 characters
        .checkMaxLength(_.name)(20)            // at most 20 characters
        .checkPattern(_.email)("^.+@.+\\..+$") // must match regex
        .checkFormat(_.email)("email")         // advisory: appears in JSON Schema only
```

`checkPattern` both validates at runtime and records the pattern in JSON Schema output. For cross-platform compatibility, use POSIX regex features (character classes, anchors, alternation, quantifiers). `checkFormat` is advisory only: it annotates JSON Schema but does not produce a runtime check.

Collection constraints control size and uniqueness on any `Iterable` field:

```scala
case class Order(id: Int, tags: List[String])

val schema =
    Schema[Order]
        .checkMinItems(_.tags)(1)  // at least 1 tag
        .checkMaxItems(_.tags)(10) // at most 10 tags
        .checkUniqueItems(_.tags)  // no duplicates
```

### Custom Checks

For validations that cannot be expressed as standard constraints, `check` accepts an arbitrary predicate. Unlike constraint methods, `check` does not appear in JSON Schema output because the predicate is an opaque function:

```scala
val schema =
    Schema[User]
        .checkMinLength(_.name)(3)
        .check(_.name)(_.forall(_.isLetterOrDigit), "alphanumeric only")
```

There are two forms: `.check(_.field)(pred, msg)` targets a single field, while `.check(pred, msg)` targets the root value for cross-field validation:

```scala
case class Person(name: String, age: Int)

val schema =
    Schema[Person]
        .check(_.name)(_.nonEmpty, "name is required")
        .check(person => person.age >= 18 || person.name.nonEmpty, "minors must have a name")
```

### Running Validation

`validate` runs every accumulated constraint and check, collecting all failures:

```scala
case class Product(name: String, price: Double, quantity: Int)

val schema =
    Schema[Product]
        .checkMin(_.price)(0.0)
        .checkMin(_.quantity)(1)

val errors: Chunk[ValidationFailedException] =
    schema.validate(Product("", -1.0, 0))
// Chunk(
//   ValidationFailedException(path = List("price"), message = "must be >= 0.0", ...),
//   ValidationFailedException(path = List("quantity"), message = "must be >= 1", ...)
// )
```

`validate` returns a `Chunk` rather than a `Result` because it collects *every* constraint violation; short-circuiting on the first one would hide problems from the user. Each error carries a `path` showing where the failure occurred and a `frame` recording where the constraint was defined.

### JSON Schema

`Json.jsonSchema[A]` derives a JSON Schema at compile time. Without a `Schema[A]` in scope, it produces a bare structural description:

```scala
val spec = Json.jsonSchema[User]
// JsonSchema.Obj(properties = List(("name", Str()), ("age", Integer())), required = List("name", "age"))
```

When a `Schema[A]` with constraints or documentation is in scope, the output is enriched with all registered metadata:

```scala
case class Product(name: String, price: Double, quantity: Int)

given Schema[Product] =
    Schema[Product]
        .checkMin(_.price)(0.0)
        .checkMax(_.price)(99999.99)
        .doc(_.price)("Product price in USD")

val enriched = Json.jsonSchema[Product]
// The "price" property now includes minimum=0.0, maximum=99999.99, and description="Product price in USD"
```

### Ion Schema

`Ion.ionSchemaString[A]()` derives an Ion Schema Language (ISL) 2.0 document describing a type's wire shape and renders it to ISL text. `Ion.ionSchema[A]()` returns the `IonSchema` document value instead, and `IonSchema.encode` renders any document value:

```scala
val isl: String = Ion.ionSchemaString[User]()
// $ion_schema_2_0 followed by one type::{ name: User, type: struct, fields: closed::{ ... } } definition
```

Products become closed structs, collections become `list`, `Option`/`Maybe` fields become `$null_or::` type arguments, string-keyed maps become `field_names: string` structs, and sealed traits become `one_of` alternatives matching their configured object representation.

Validation metadata registered on the `Schema` carries into the document: `checkMin`/`checkMax` become `valid_values: range::`, `checkMinLength`/`checkMaxLength` become `codepoint_length`, `checkMinItems`/`checkMaxItems` become `container_length`, and `checkUniqueItems` marks list elements `distinct::`:

```scala
case class Product(name: String, price: Double, quantity: Int)

given Schema[Product] =
    Schema[Product]
        .checkMin(_.price)(0.0)
        .checkMax(_.price)(99999.99)

val islProduct: String = Ion.ionSchemaString[Product]()
// the "price" field carries valid_values: range::[0, 99999.99] from its checkMin/checkMax bounds
```

`IonSchema.Config(version, closedStructs, annotationEmissionMode)` controls the ISL version marker, whether structs render `closed::`, and whether captured annotations are emitted. Set `annotationEmissionMode = Ion.AnnotationEmissionMode.Emit` to include them as ISL `annotations` constraints:

```scala
val annotated: String =
    Ion.ionSchemaString[User](
        IonSchema.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
    )
```

> **Note:** This derives an ISL document describing the wire shape; it does not validate values. Runtime value checking stays with `Schema.decode` and `Schema.validate`, since Ion Schema validates the full Ion data model (annotations and typed nulls included) that ordinary value decoding intentionally erases. kyo does not ship an ISL evaluator.

## Navigation and lenses

So far every operation has worked on whole values. `Focus` lets you read, write, and update individual fields at any depth.

### Reading and writing one field

Call `schema.focus(_.path)` to create a lens that targets a specific field:

```scala doctest:scope=nested
case class Address(city: String, zip: String)
case class User(id: Int, name: String, email: String, password: String, address: Address) derives Schema

val cityFocus = Schema[User].focus(_.address.city)

val user = User(1, "Alice", "alice@example.com", "secret", Address("Portland", "97201"))

cityFocus.get(user)                   // "Portland"
cityFocus.set(user, "Seattle")        // User with city = "Seattle"
cityFocus.update(user)(_.toUpperCase) // User with city = "PORTLAND"
```

Every other field is preserved. Only the targeted value changes, at any depth.

When the path crosses a sealed trait variant, the value might not exist at runtime. If the active variant doesn't match the path, there's nothing to get. The compiler detects this and returns a `Focus` where `get` returns `Maybe` instead of a bare value:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape
case class Drawing(title: String, shape: Shape)

val radiusFocus = Schema[Drawing].focus(_.shape.Circle.radius)

radiusFocus.get(Drawing("art", Circle(5.0)))     // Maybe(5.0)
radiusFocus.get(Drawing("art", Rectangle(3, 4))) // Maybe.empty

radiusFocus.set(Drawing("art", Circle(5.0)), Maybe(10.0))     // Drawing with radius 10.0
radiusFocus.set(Drawing("art", Rectangle(3, 4)), Maybe.empty) // unchanged
```

A Maybe-mode `Focus` also provides `getOrElse` and `isDefined`. The mode isn't something you configure; the compiler picks it from the path:

| Mode | `get` returns | When |
|------|---------------|------|
| `Focus.Id` | `Value` | Product fields: always exactly one value |
| `Maybe` | `Maybe[Value]` | Sum variants: the variant might not be active |
| `Chunk` | `Chunk[Value]` | Collections: zero or more elements (see `foreach` below) |

Once you cross a sum variant or enter a collection, every subsequent step inherits that uncertainty.

### Navigating into collections

When a schema contains collection fields, `foreach` navigates into the elements. It returns a `Focus` in `Chunk` mode:

```scala
case class Item(name: String, price: Double)
case class Order(id: Int, items: List[Item])

val each  = Schema[Order].foreach(_.items)
val order = Order(1, List(Item("Widget", 9.99), Item("Gadget", 19.99)))

each.get(order)
// Chunk(Item("Widget", 9.99), Item("Gadget", 19.99))

each.update(order)(item => item.copy(price = item.price * 1.1))
// Order with all prices increased by 10%
```

Because `foreach` returns a `Focus`, you can chain `.focus` to drill into a specific field of each element:

```scala
case class Item(name: String, price: Double)
case class Order(id: Int, items: List[Item])
val order = Order(1, List(Item("Widget", 9.99), Item("Gadget", 19.99)))

val prices = Schema[Order].foreach(_.items).focus(_.price)

prices.get(order)           // Chunk(9.99, 19.99)
prices.update(order)(_ * 2) // all prices doubled
```

`foreach` composes for nested collections: `foreach(_.orders).foreach(_.items)` flattens across both levels.

### Walking every field

`Focus` navigates to a specific field. When you need to process *every* field generically, `fold` visits each one in turn with its name and value and accumulates a result. It's the right tool for building a log line, a field-name-to-value map, or any other traversal that doesn't know the type statically:

```scala
case class Config(host: String, port: Int, ssl: Boolean)
val config = Config("localhost", 8080, false)

val summary = Schema[Config].fold(config)(List.empty[String]) {
    [N <: String, V] =>
        (acc, field, value) =>
            s"${field.name}=$value" :: acc
}.reverse.mkString(", ")
// "host=localhost, port=8080, ssl=false"
```

The polymorphic function (the `[N <: String, V] => ...` syntax) receives the exact singleton name type `N` and value type `V` for each field along with a `Field[N, V]` descriptor carrying the field name, tag, default value, and a list of nested field descriptors when the value is itself a `Record`. `fold` respects active transforms: dropped fields are skipped, renamed fields use the new name, and computed fields are included.

### Metadata Accessors

Every `Focus` carries the metadata associated with its field path, readable without round-tripping through `fieldDescriptors`:

```scala
case class Config(host: String, port: Int = 8080, ssl: Boolean = false)

val f = Schema[Config].focus(_.port)

f.doc         // Maybe[String]            (documentation attached via .doc(_.port)(...))
f.deprecated  // Maybe[String]            (deprecation reason, if any)
f.default     // Maybe[Int]               (compile-time default from the case class)
f.optional    // Boolean                  (true if typed Option or Maybe)
f.fieldId     // Int                      (stable wire ID, XXH32-based unless pinned)
f.constraints // Chunk[Schema.Constraint] (validators targeting this path)
```

These are useful when generating form UIs, API specs, or documentation from the schema.

## Reshaping schemas

Sometimes the entire shape needs to change: omitting sensitive fields for an API response, renaming for a different convention, or adding computed values. Transforms reshape a schema's structural type without changing the source type `A` (case classes only). Because serialization, validation, and navigation all read the same structural description, a transform affects all of them consistently.

For the `User` introduced at the top of this document (`id`, `name`, `email`, `password`, `address`), an API response might omit the password, rename `name` to `displayName`, and add a computed `active` field:

```scala
val apiSchema =
    Schema[User]
        .drop(_.password)
        .rename(_.name, "displayName")
        .add("active")(user => user.id > 0)
```

The password is absent from serialized output because it is absent from the structural type.

### drop / rename / add / select / flatten

**drop** removes a field:

```scala
Schema[Person].drop(_.age)
// Serialized: {"name":"Alice"}
```

**rename** changes a field's name, preserving its type. The source is a lambda (so the existing field is refactor-safe) and the target is a string literal (because the new name doesn't exist yet to point a lambda at):

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

```scala doctest:scope=nested
case class Address(city: String, zip: String)
case class Person(name: String, address: Address)

Schema[Person].flatten
// Serialized: {"name":"Alice","city":"Portland","zip":"97201"}
```

When the field name isn't known at compile time, `drop` and `rename` accept string-based overloads: `drop("field")`, `rename("from", "to")`, `select("f1", "f2")`. `flatten` takes no arguments. `add` always takes a string literal for the new field name (since the name doesn't exist yet to point a lambda at) plus a lambda that computes the value.

These transforms are `transparent inline` and declared to return `Any`; the compiler refines the real result to a re-typed `Schema[A]`, so navigation and conversion against the reshaped view stay fully typed.

### Serializing transformed schemas

**Gotcha:** once you derive a new schema via `drop`/`rename`/`add`/`select`/`flatten`, `Json.encode(value)` still uses the *original* `Schema[User]` summoned from implicit scope, not your reshaped one. The transform lives on the schema *instance*; you have to call the serialization methods on that instance:

```scala
val s = Schema[Person].rename(_.name, "userName")

s.encodeString[Json](Person("Alice", 30))
// {"userName":"Alice","age":30}

s.decodeString[Json]("""{"userName":"Alice","age":30}""")
// Result.Success(Person("Alice", 30))
```

`s.encode[Json]` and `s.decode[Json]` work on `Span[Byte]`; `encodeString`/`decodeString` wrap those with UTF-8 conversion.

Each transform has defined behavior on the round-trip:

- **rename**: encode uses the new name, decode reads the new name and maps it back to the original field.
- **drop**: encode omits the field. Decode fills it from the default value if one exists; otherwise uses the zero value.
- **add**: the computed field appears in encoded output. Decode ignores unknown fields, so the extra field is silently discarded.
- **select**: equivalent to dropping all fields not named in the selection.

### Per-field wire transforms

`drop`/`rename`/`add` reshape the structural field set. To keep a field but change only how it crosses the wire, use `transformField(_.field)(write)(read)`. It overrides the field's encode and decode functions while preserving the structural type, so navigation, validation, and conversion still see the original Scala field. `transformFieldWrite` and `transformFieldRead` override one direction and preserve any existing override for the other. A later transform for the same field replaces both directions.

```scala
case class Order(name: String, quantity: Int)

val s = Schema[Order]
    .transformField(_.quantity)((v, w) => w.string(v.toString))(r => r.string().toInt)

s.encodeString[Json](Order("hat", 42))
// {"name":"hat","quantity":"42"}

s.decodeString[Json]("""{"name":"hat","quantity":"42"}""")
// Result.Success(Order("hat", 42))
```

Per-field transforms compose with the other builders: a `rename` on the same field still applies, and a `default(_.field)(...)` supplies the field when it is absent. Watch the omit case: an `omit(_.field).when(...)` predicate runs over the post-transform `Structure.Value`, so write the predicate against the transformed wire value, not the original Scala field type, or it silently never fires.

## Type Conversion

`Convert[A, B]` is a one-directional conversion derived at compile time from the field structure of both types. It succeeds when B's fields are a subset of A's (with matching types), or when B has defaults for the missing ones:

```scala
case class Point2D(x: Int, y: Int)
case class Coords(x: Int, y: Int)

val convert = Convert[Point2D, Coords]

convert(Point2D(3, 4)) // Coords(3, 4)
```

`Convert` extends `scala.Conversion[A, B]`, so providing it as a `given` enables implicit conversion:

```scala
case class Point2D(x: Int, y: Int)
case class Coords(x: Int, y: Int)

given Convert[Point2D, Coords] = Convert[Point2D, Coords]

def draw(c: Coords): Unit = ???

draw(Point2D(3, 4)) // implicit conversion applied
```

Providing `Convert[A, B]` as a `given` makes any `A` flow into a context expecting `B` without an explicit call site, which can be surprising at a distance. Prefer explicit `.apply` unless the conversion is genuinely ambient.

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
        .drop(_.address)
        .rename(_.name, "displayName")
        .add("active")(user => user.id > 0)
        .convert[UserResponse]

toResponse(alice)
// UserResponse("Alice", "alice@example.com", 1, true)
```

If `B` has a required field with no match in the transformed view, compilation fails. Fields with defaults are filled automatically.

## Comparison and Mutation

When you need to compare two values, patch one into another, or describe mutations as data, the module provides three types in increasing complexity:

- **Compare**: read-only, holds two values and lets you query which fields differ
- **Modify**: batched writes, accumulates field mutations and applies them as a single unit
- **Changeset**: serializable diff, captures the operations needed to turn one value into another as data that can be stored, transmitted, and replayed

All three use the same focus lambda syntax (`_.field`) introduced in Navigation.

### Comparing two values

`Compare` gives you a field-by-field view of the differences between two values:

```scala
case class Config(host: String, port: Int, ssl: Boolean)

val staging    = Config("staging.example.com", 8080, false)
val production = Config("prod.example.com", 443, true)

val d = Compare(staging, production)

d.changed         // true (at least one field differs)
d.changed(_.host) // true
d.changed(_.port) // true
d.left(_.host)    // Maybe("staging.example.com")
d.right(_.host)   // Maybe("prod.example.com")
d.changes         // Seq(("host", ..., ...), ("port", ..., ...), ("ssl", ..., ...))
```

`left` and `right` return `Maybe[V]` because the path might cross a sealed trait variant that is not active.

### Batching mutations

`Modify` accumulates field mutations and applies them all at once. Use it when you want to describe a set of changes as a reusable value before applying them:

```scala
case class Config(host: String, port: Int, ssl: Boolean)
val staging = Config("staging.example.com", 8080, false)

val updated =
    Modify[Config]
        .set(_.host)("prod.example.com")
        .update(_.port)(_ + 1)
        .applyTo(staging)
// Config("prod.example.com", 8081, false)
```

**Modify vs Focus.set/update**: `Focus` is for immediate, single-field operations where you have a value and want to change one thing right now. `Modify` is for building up a batch of changes as a first-class value that you can store, pass around, and apply later. If you only need to change one field, use `Focus`. If you need to describe multiple changes as a unit, use `Modify`.

For sum-type paths, if the active variant does not match, the operation is silently skipped. `applyTo` returns the root value unchanged (no error), so chained mutations on the wrong variant are no-ops rather than failures:

```scala
sealed trait Shape
case class Circle(radius: Double)                   extends Shape
case class Rectangle(width: Double, height: Double) extends Shape
val someShape: Shape = Circle(5.0)

Modify[Shape]
    .update(_.Circle.radius)(_ * 2) // applied if shape is Circle, skipped otherwise
    .applyTo(someShape)
```

### Diffs as data

`Changeset` computes the difference between two values and stores it as a sequence of patch operations. Unlike `Compare`, which requires both values in memory, a `Changeset` is self-contained data that can be serialized, sent over the wire, and applied independently.

Under the hood, both values are converted to untyped `Structure.Value` trees and compared field by field. For each difference, the most specific operation is chosen: an arithmetic delta for numbers, a text patch for strings, recursive descent for nested records, element-level tracking for collections and maps. The result is a `Chunk[Changeset.Patch]` that can reconstruct the target from the source:

```scala
case class Config(host: String, port: Int, ssl: Boolean)
val staging    = Config("staging.example.com", 8080, false)
val production = Config("prod.example.com", 443, true)

val cs = Changeset(staging, production)

val empty = cs.isEmpty // false
val ops   = cs.operations
// ops == Chunk(
//   StringPatch(Chunk("host"), 0, 7, "prod"),
//   NumericDelta(Chunk("port"), -7637),
//   SetField(Chunk("ssl"), Structure.Value.Bool(true))
// )
```

Each operation carries its field path (`Chunk[String]`) so it knows where to apply. `NumericDelta` stores the arithmetic difference rather than the absolute value. `StringPatch` stores a character-range edit. `Nested` recurses into sub-records. `SequencePatch` and `MapPatch` track additions, removals, and updates at the element level.

`applyTo` replays the operations against a source value:

```scala
case class Config(host: String, port: Int, ssl: Boolean)
val staging    = Config("staging.example.com", 8080, false)
val production = Config("prod.example.com", 443, true)
val cs         = Changeset(staging, production)

val result: Result[SchemaException, Config] = cs.applyTo(staging)
// Result.Success(Config("prod.example.com", 443, true))
```

`Changeset` derives `Schema`, so it serializes like any other data. This makes it possible to compute a diff on one machine, send it over the wire, and apply it on another:

```scala
case class Config(host: String, port: Int, ssl: Boolean)
val staging    = Config("staging.example.com", 8080, false)
val production = Config("prod.example.com", 443, true)
val cs         = Changeset(staging, production)

// Sender: compute and serialize the diff
val serialized: String = Json.encode(cs)

// Receiver: deserialize and apply
val received = Json.decode[Changeset[Config]](serialized).getOrThrow
val updated  = received.applyTo(staging)
// Result.Success(Config("prod.example.com", 443, true))
```

Changesets compose with `andThen`.

## Constructing values with Builder

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

`Schema[A]` derives `Ordering[A]` and `CanEqual[A, A]` for case classes, so you don't need to write comparison logic by hand. The ordering is lexicographic by field declaration order: the first field is the primary sort key, the second field breaks ties, and so on.

Both are exposed as `given` members on the schema instance, so you `import schema.order` (or `schema.canEqual`) to bring them into implicit scope:

```scala doctest:scope=inherited
case class Person(name: String, age: Int) derives Schema

val schema = Schema[Person]
import schema.order

val people = List(Person("Charlie", 25), Person("Alice", 30), Person("Alice", 28))
people.sorted
// List(Person("Alice", 28), Person("Alice", 30), Person("Charlie", 25))
// "Alice" < "Charlie" (primary key), then 28 < 30 (secondary key)
```

`CanEqual` enables `==` when strict equality is active:

```scala doctest:scope=inherited
import schema.canEqual

Person("Alice", 30) == Person("Alice", 30) // true
```

The ordering always follows the case class field declaration order, regardless of any transforms applied to the schema.

## Structural Introspection

### Docs, examples, deprecation

Schemas carry documentation, examples, and deprecation markers. These flow into JSON Schema generation, making your API spec reflect the annotations you add in code:

```scala
val schema =
    Schema[Person]
        .doc("A person in the system")
        .doc(_.name)("The person's full name")
        .deprecated(_.age)("Use birthDate instead")
        .example(Person("Alice", 30))
```

Field layout is also available at runtime for building dynamic UIs, generating documentation, or driving generic serialization. `Schema[A].fieldNames` and `Schema[A].fieldDescriptors` return the field names and full `Field` descriptors (name, tag, default, metadata). `schema.defaults` returns a typed `Record` of fields with compile-time defaults, useful for `Builder`-style construction. `schema.toRecord` converts a value into a typed `Record` respecting active transforms. `schema.fieldDocs`, `schema.droppedFields`, and `schema.renamedFields` expose transform metadata for tools that need to inspect what reshaping has been applied.

### The runtime type and value trees

`Structure` is the runtime type description API. It provides two complementary trees: `Structure.Type` describes the shape of a Scala type (fields, variants, element types), and `Structure.Value` holds actual data in an untyped, format-neutral representation. Together they let you traverse, compare, and transform values without knowing their concrete types. This is useful for generic programming, building admin UIs, bridging to dynamic languages, and powering `Changeset`.

`Structure.of[A]` derives the type shape at compile time:

```scala
val tpe: Structure.Type = Structure.of[Person]
// Structure.Type.Product with fields "name" (Str) and "age" (Integer)
```

The type tree has variants for each category of Scala type: `Product` (case classes), `Sum` (sealed traits), `Collection` (lists, sets), `Mapping` (maps), `Optional` (Option/Maybe), `Primitive` (scalars), and `Open` (a shape not fixed at compile time, such as `Structure.Value` itself or a hand-written codec built with `Schema.init`).

`Structure.encode` converts a typed value into the untyped `Value` tree. `Structure.decode` converts it back:

```scala doctest:scope=inherited
val dynamic: Structure.Value = Structure.encode(Person("Alice", 30))
// Structure.Value.Record(Chunk(("name", Str("Alice")), ("age", Integer(30))))

val restored: Result[DecodeException, Person] = Structure.decode[Person](dynamic)
// Result.Success(Person("Alice", 30))
```

Case classes become `Value.Record`, scalars become typed leaves (`Str`, `Integer`, `Decimal`, `Bool`, `BigNum`), sealed traits become `Value.VariantCase`, collections become `Value.Sequence`, maps become `Value.MapEntries`, and absent/null values become `Value.Null`.

`Structure.Path` navigates untyped value trees via `get` and `set`, composing segments with `/`:

```scala doctest:scope=inherited
val path = Structure.Path.field("name")
path.get(dynamic) // Result.Success(Chunk(Str("Alice")))
```

Path segments include `Field("name")` for record fields, `Variant("Circle")` for sum variants, `Index(0)` for sequence elements, and `Each` for wildcard traversal of all elements.

#### Type-level operations

The `Structure.Type` tree ships with a small set of operations for runtime inspection:

- `Structure.Type.compatible(a, b)`: structural equality check, returns `true` when two `Type`s have the same shape (same field names and types, same variants, recursively). Useful when verifying that a foreign-produced value matches an expected shape.
- `Structure.Type.fold(tpe)(init)(f)`: depth-first walk that threads an accumulator across every node in the type tree.
- `Structure.Type.fieldPaths(tpe)`: returns a `Chunk[Chunk[String]]` of all leaf paths through a `Product` type, flattening nested records.
- `Structure.typedValue[A](value)`: bundles a `Structure.Type` descriptor (from `Structure.of[A]`) together with the encoded `Structure.Value` into a `Structure.TypedValue`, handy for passing fully-described data to a generic receiver.

## Custom Formats

`Json`, `Ion`, `Yaml`, and `Protobuf` are the built-in formats, but the serialization pipeline itself is format-agnostic. A schema describes a value as a sequence of typed events (`objectStart`, `field`, `int`, `arrayStart`, ...) and a matching sequence on the way back. A format is the code that turns those events into bytes and back.

### The Codec trait

A format is implemented as a `Codec`, which is a factory for a matching `Writer` and `Reader`:

```scala
abstract class Codec:
    def newWriter(): Codec.Writer
    def newReader(input: Span[Byte])(using Frame): Codec.Reader
```

`Writer` receives a stream of structural events and accumulates bytes; `Reader` consumes bytes and answers the same events in reverse to reconstruct the value. Schemas never know which codec is in use. They traverse the value in declaration order and emit events; the codec decides how those events are laid out on the wire.

### The event model

`Codec.Writer` and `Codec.Reader` share the same vocabulary. Every structural category maps to a sequence of calls:

| Structure | Writer calls | Reader calls |
|-----------|--------------|--------------|
| Case class | `objectStart(name, size)`, `field(name, id)`, value, ..., `objectEnd()` | `objectStart()`, `fieldParse()` or `field()`, `matchField(nameBytes)` / `lastFieldName()`, value, `hasNextField()`, ..., `objectEnd()` |
| Sealed trait | wrapper object with one field per variant (or discriminator, see `Schema.discriminator`) | wrapper object; `captureValue()` lets the variant dispatch defer reading until after the discriminator is known |
| Collection | `arrayStart(size)`, element, ..., `arrayEnd()` | `arrayStart()`, element, `hasNextElement()`, ..., `arrayEnd()` |
| Map | `mapStart(size)`, key, value, ..., `mapEnd()` | `mapStart()`, key, value, `hasNextEntry()`, ..., `mapEnd()` |
| Primitive | `int(v)` / `string(v)` / `bool(v)` / ... | `int()` / `string()` / `boolean()` / ... |
| Optional | `nil()` when absent, otherwise the inner value | `isNil()` to detect, otherwise the inner value |

The Reader also exposes `initFields(n)`, `clearFields(n)`, `droppedFieldsMask(n)`, and `release()` as overridable hooks for pooled / allocation-sensitive implementations. See `JsonReader` for an example that uses all of them.

`fieldBytes(nameBytes, fieldId)` is available for codecs that want to avoid `String` allocation on hot paths. Protobuf uses the numeric `fieldId`; JSON uses the name bytes. Codecs that do not care about one side can ignore it.

A self-describing format (one that can materialize an arbitrary wire value without a schema, such as JSON or YAML) additionally extends `Codec.IntrospectingReader` and implements `readStructure(): Structure.Value`. That capability is what lets `Schema[Structure.Value]` accept any shape on read. A binary format without per-value type tags (Protobuf) does not extend it, so the type system rejects an open-shape decode through such a codec at compile time rather than at runtime.

### A minimal codec

A complete codec is three classes: a `Writer` that accumulates bytes from structural events, a `Reader` that answers the same events back from bytes, and a `Codec` that hands them out. The full `Writer`/`Reader` interface is around twenty methods each (one per primitive plus the structural start/end pairs). Rather than reproduce the whole contract here, the following sketch shows the pattern for a line-oriented text format handling strings and ints; a real implementation must cover every primitive the schema pipeline can emit:

```scala doctest:expect=skipped
import java.nio.charset.StandardCharsets

final class LinesWriter extends Codec.Writer:
    private val sb                                 = StringBuilder()
    def objectStart(name: String, size: Int): Unit = ()
    def objectEnd(): Unit                          = ()
    def field(name: String, id: Int): Unit         = sb.append(name).append('=')
    def string(v: String): Unit                    = sb.append(v).append('\n')
    def int(v: Int): Unit                          = sb.append(v).append('\n')
    // ... remaining primitives, arrays, maps, nil
    def result(): Span[Byte] =
        Span.from(sb.toString.getBytes(StandardCharsets.UTF_8))
end LinesWriter

final class LinesReader(input: Span[Byte])(using val frame: Frame) extends Codec.Reader:
    // parse the input back into the same events
end LinesReader

object Lines extends Codec:
    def newWriter(): Codec.Writer                               = LinesWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader = LinesReader(input)
```

Because `Lines` is an object, you don't need to instantiate it or introduce a `given`. Just pass it directly to any schema method:

```scala doctest:expect=skipped
Schema[User].encode(alice)(using Lines) // Span[Byte] in the Lines format
Schema[User].decode(bytes)(using Lines) // Result[DecodeException, User]
```

For a complete example, read `JsonWriter` and `JsonReader`, `IonWriter` and `IonReader`, or their Protobuf counterparts in the same package: they implement the full contract.

When writing a custom schema for an opaque or wrapper type, you can also construct a `Schema` instance directly using the public factories `Schema.init` (for plain schemas) and `Schema.initFocused` (when you need to track the focused type member). Both take inlined `writeFn` and `readFn` lambdas, plus an optional `getterFn`/`setterFn` pair for lens support. Abstract members must be supplied (including `fieldParse`, `matchField`, `lastFieldName`, and `captureValue`); optional overrides like `fieldBytes`, `initFields`, `clearFields`, `droppedFieldsMask`, and `release` are where real codecs recover allocation-sensitive performance.

### Safety limits

`Codec.Reader` provides two DoS limit hooks: `maxDepth` (nesting) and `maxCollectionSize` (entries per collection). Implementations must call `checkDepth()` inside `objectStart`/`arrayStart`/`mapStart` and `checkCollectionSize(size)` when a collection reports its length; the base class does not enforce these on its own. Both methods throw `LimitExceededException` when their limit is breached. The `Frame` captured at Reader construction is used to attribute the exception to the caller, not to the codec internals.

## Exceptions

All errors raised by kyo-schema extend the sealed `SchemaException` hierarchy. The most useful subtypes:

| Exception | Raised when |
|-----------|-------------|
| `MissingFieldException` | a required field is absent on decode |
| `MissingTagKeyException` | an `.adjacent` decode input lacks the configured tag key |
| `TypeMismatchException` | a runtime value is the wrong shape for the schema |
| `UnknownVariantException` | a sum-type discriminator names an undefined variant, or a `variantNames`/`variantAlias` call references an unknown Scala variant name |
| `VariantNameCollisionException` | two variants (or a variant and an alias) map to the same wire discriminator value |
| `FieldNameCollisionException` | two fields (or a field and an alias) map to the same wire name |
| `NoVariantMatchException` | an `.untagged` decode matches no variant (lists the attempted variants) |
| `UnknownFieldException` | a `denyUnknownFields` decode hits an input field absent from the schema's effective read set (after naming, aliases, and transforms); carries the offending `fieldName` |
| `ParseException` | raw input cannot be parsed by the codec |
| `RepresentationUnsupportedException` | a `.tupleTagged`, `.tupleFlat`, or `.untagged` representation is encoded through a codec that cannot express it (Protobuf) |
| `AmbiguousVariantMatchException` | an untagged or type-union decode under the default `Strict` policy matches more than one member (lists the matched members) |
| `DuplicateRepresentationException` | a `representations(...)` or `orElseRepresentation(...)` chain contains the same representation twice (raised at the builder call, not at encode time) |
| `TruncatedInputException` | the input stream ends before decoding completes |
| `LimitExceededException` | `maxDepth` or `maxCollectionSize` is exceeded |
| `RangeException` | a numeric value overflows the target type |
| `ValidationFailedException` | a `.check` / `checkMin` / ... predicate fails |
| `TransformFailedException` | a schema transform cannot complete |
| `PathNotFoundException` | a `Structure.Path` segment does not exist |
| `SchemaNotSerializableException` | an internal schema has no write/read function |
| `SchemaIndexOutOfBoundsException` | a sequence index falls outside the bounds |

All of these subtype one of the sealed markers `DecodeException`, `ValidationException`, `TransformException`, or `NavigationException`, so pattern-matching on a marker catches a family at once.

## Putting it together

A single `derives Schema` powers JSON and Protobuf serialization, deep lenses, type-level reshaping, and structural diffs, all on the same value:

```scala doctest:scope=nested
case class Address(city: String, zip: String)
case class User(id: Int, name: String, email: String, password: String, address: Address) derives Schema

val alice = User(1, "Alice", "alice@example.com", "secret", Address("Portland", "97201"))

// JSON and Protobuf
Json.encode(
    alice
) // {"id":1,"name":"Alice","email":"alice@example.com","password":"secret","address":{"city":"Portland","zip":"97201"}}
Protobuf.encode(alice) // Span[Byte] (binary)

// Type-safe lenses reach any depth
Schema[User].focus(_.address.city).update(alice)(_.toUpperCase)
// User(1, "Alice", "alice@example.com", "secret", Address("PORTLAND", "97201"))

// Type-level reshaping: drop sensitive fields, rename, add computed
val publicView =
    Schema[User]
        .drop(_.password)
        .rename(_.name, "displayName")
// Encodes alice as: {"id":1,"displayName":"Alice","email":"alice@example.com","address":{"city":"Portland","zip":"97201"}}

// Diffs as data: capture just what changed, ship, replay
val renamed = alice.copy(name = "Alicia")
val cs      = Changeset(alice, renamed) // Produces a changeset with the new name patch
cs.applyTo(alice) // Result.Success(renamed)
```

## Cross-platform behavior

kyo-schema runs on JVM, Scala.js, and Scala Native, but two areas behave differently across platforms:

- **ASCII bytes to String conversion**: the JVM uses a zero-copy path that constructs `String` directly from the underlying byte array via the private LATIN1 String constructor, sharing the array without copying. Scala.js and Scala Native copy the bytes via `new String(bytes, StandardCharsets.US_ASCII)`. This is an implementation detail that does not affect correctness, but may appear in allocations-per-request profiling on high-throughput JVM workloads.

- **Regex support in `checkPattern`**: `checkPattern` uses `java.util.regex.Pattern`. Scala.js and Scala Native emulate this API but do not support all JVM regex features. Features unavailable off-JVM include possessive quantifiers (`a++`, `a*+`), atomic groups (`(?>...)`), some Unicode property classes (`\p{...}`), and lookbehind on older JS engines. For cross-platform constraints, stick to POSIX features: character classes, anchors, alternation, basic quantifiers, capture groups, backreferences, and simple lookahead.
