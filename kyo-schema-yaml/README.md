# kyo-schema-yaml

YAML codec for [kyo-schema](../kyo-schema). Provides the `Yaml` entry point
(`Yaml.encode` / `Yaml.decode` for YAML 1.2 documents, plus the lower-level
`Yaml.Cst` concrete-syntax-tree and `Yaml.Events` streaming APIs) for any type
with a `Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-yaml" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [YAML section of the kyo-schema README](../kyo-schema/README.md#yaml);
everything there applies unchanged — only the artifact name is new.
