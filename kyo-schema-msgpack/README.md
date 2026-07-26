# kyo-schema-msgpack

MessagePack codec for [kyo-schema](../kyo-schema). Provides the `MsgPack` entry
point (`MsgPack.encode` / `MsgPack.decode` for the compact binary wire format)
for any type with a `Schema` instance.

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-msgpack" % "<latest version>"
```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [MsgPack section of the kyo-schema README](../kyo-schema/README.md#msgpack);
everything there applies unchanged — only the artifact name is new.
