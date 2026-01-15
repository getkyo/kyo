# kyo-grpc

A Protoc plugin that generates...

# Using the plugin

<!-- TODO: This should use some kind of doc test against the example project. -->

To add the plugin to another project:

```
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")

libraryDependencies += "com.example" %% "kyo-grpc-codegen" % "0.1.0"
```

and the following to your `build.sbt`:
```
PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value / "scalapb",
  kyo.grpc.gen() -> (sourceManaged in Compile).value / "scalapb"
)
```

# Development and testing

Code structure:
- [`core`](./core/): The runtime library for this plugin
- [`code-gen`](./code-gen): The protoc plugin (code generator)
- [`e2e`](./e2e): Integration tests for the plugin
- [`examples`](./examples): Example projects

To test the plugin, within SBT:

```
> e2eJVM2_13/test
```

or 

```
> e2eJVM2_12/test
```
