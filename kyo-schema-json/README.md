# kyo-schema-json

JSON codec for [kyo-schema](../kyo-schema). Provides the `Json` entry point
(`Json.encode` / `Json.decode`, the `Json.encodeBytes` / `Json.decodeBytes`
byte-level variants, and `Json.jsonSchema` for JSON Schema generation) for any
type with a `Schema` instance. `Json.Lines` adds line-delimited JSON (JSONL,
also called NDJSON): a pure, resumable framer plus whole-input `decodeAll` /
`encodeAll` helpers over it. Streaming JSONL from files and byte streams lives
in [kyo-json](../kyo-json), which drives that same framer under `Sync` and
`Async`.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-json" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [JSON section of the kyo-schema README](../kyo-schema/README.md#json).
Everything there applies unchanged; the artifact name is the only thing this
module adds.
