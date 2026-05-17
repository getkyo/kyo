# kyo-grpc

`kyo-grpc` provides gRPC runtime support and ScalaPB code generation for Kyo services.

The runtime module supports all four gRPC method shapes:

- unary
- server streaming
- client streaming
- bidirectional streaming

The code generator is intended to run alongside ScalaPB. It emits a Kyo service trait, a gRPC `ServerServiceDefinition`, and a typed client facade for protobuf services.

## Build Setup

Add `sbt-protoc` and the Kyo gRPC code generator to `project/plugins.sbt`:

```scala
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")

libraryDependencies += "io.getkyo" %% "kyo-grpc-code-gen" % kyoVersion
```

Add the runtime dependency and protoc targets to `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-grpc-core" % kyoVersion

Compile / PB.targets := Seq(
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
    kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb"
)
```

Generated service companions expose:

- `service(serviceImpl)` for server registration
- `client(channel, options)` for client creation from an existing channel
- `managedClient(host, port)(configure)` for scoped channel and client creation

## Local Validation

Run the affected module checks from the repository root:

```sh
sbt 'kyo-grpc-core/test' 'kyo-grpc-code-gen/test' 'kyo-grpc-e2e/test'
```

The e2e module compiles `e2e/shared/src/main/protobuf/test.proto`, generates Kyo service/client code, starts a local gRPC server, and validates unary plus all streaming RPC shapes.
