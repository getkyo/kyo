# kyo-schema-json

JSON codec for [kyo-schema](../kyo-schema). Provides the `Json` entry point
(`Json.encode` / `Json.decode`, the `Json.encodeBytes` / `Json.decodeBytes`
byte-level variants, and `Json.jsonSchema` for JSON Schema generation) for any
type with a `Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-json" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [JSON section of the kyo-schema README](../kyo-schema/README.md#json);
everything there applies unchanged — only the artifact name is new.
