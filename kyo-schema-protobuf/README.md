# kyo-schema-protobuf

Protocol Buffers codec for [kyo-schema](../kyo-schema). Provides the `Protobuf`
entry point (`Protobuf.encode` / `Protobuf.decode` for the binary wire format,
and `Protobuf.ProtoSchema` for `.proto` schema export) for any type with a
`Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-protobuf" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [Protobuf section of the kyo-schema README](../kyo-schema/README.md#protobuf);
everything there applies unchanged — only the artifact name is new.
