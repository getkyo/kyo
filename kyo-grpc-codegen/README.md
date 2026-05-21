# Kyo gRPC CodeGen

This module provides a ScalaPB generator for Kyo.

## Usage

Add the following to your `project/plugins.sbt`:

```scala
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "io.getkyo" %% "kyo-grpc-codegen" % kyoVersion
```

Then in your `build.sbt`:

```scala
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value,
  kyo.grpc.codegen.KyoGrpcGenerator -> (Compile / sourceManaged).value
)
```

## Generated Code

For a service named `Greeter`, the generator produces:
- `GreeterKyo`: A trait with Kyo-native method signatures.
- `GreeterKyo.bindService`: A helper to bridge the implementation to gRPC.
- `GreeterKyo.Client`: A Kyo-native client.
