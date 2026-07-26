# kyo-schema-bson

BSON codec for [kyo-schema](../kyo-schema). Provides the `Bson` entry point
(`Bson.encode` / `Bson.decode` for BSON document bytes) for any type with a
`Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-bson" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [BSON section of the kyo-schema README](../kyo-schema/README.md#bson);
everything there applies unchanged — only the artifact name is new.
