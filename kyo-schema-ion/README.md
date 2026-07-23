# kyo-schema-ion

Amazon Ion codec for [kyo-schema](../kyo-schema). Provides the `Ion` entry
point (`Ion.encode` / `Ion.decode` for Ion text or binary), the standalone
`IonBinary` codec, and `IonSchema` for Ion Schema generation, for any type
with a `Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-ion" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [Ion section of the kyo-schema README](../kyo-schema/README.md#ion);
everything there applies unchanged — only the artifact name is new.
