# kyo-grpc

Fiber-native gRPC support for the [Kyo](https://getkyo.io) framework. This module provides a protoc plugin and runtime library for building gRPC servers and clients using Kyo's effect system.

## Features

- **Fiber-native**: gRPC calls are first-class Kyo effects that compose with `Async`, `Abort`, `Stream`, and other Kyo effects
- **All four gRPC call types**: Unary, server streaming, client streaming, and bidirectional streaming
- **Code generation**: Protoc plugin generates Kyo-native service traits and clients from protobuf definitions
- **Resource safety**: Automatic lifecycle management via Kyo's `Scope` effect
- **Backpressure**: Built-in flow control for streaming operations
- **Error handling**: gRPC status codes map naturally to Kyo's `Abort` effect

## Quick Start

### 1. Add the plugin to your project

In `project/plugins.sbt`:

```scala
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")

libraryDependencies += "io.getkyo" %% "kyo-grpc-code-gen" % "0.19.0"
```

In `build.sbt`:

```scala
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-grpc-core" % "0.19.0",
    "io.grpc"   % "grpc-netty-shaded" % "1.72.0"
)

Compile / PB.targets := Seq(
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
    kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb"
)
```

### 2. Define your service in protobuf

```protobuf
syntax = "proto3";

package helloworld;

service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc SayHelloStream (HelloRequest) returns (stream HelloReply);
}

message HelloRequest {
  string name = 1;
}

message HelloReply {
  string message = 1;
}
```

### 3. Implement the server

```scala
import kyo.*
import kyo.grpc.*
import helloworld.*

object GreeterService extends Greeter:
    override def sayHello(request: HelloRequest): HelloReply < Grpc =
        HelloReply(s"Hello, ${request.name}!")

    override def sayHelloStream(request: HelloRequest): Stream[HelloReply, Grpc] < Grpc =
        Stream(HelloReply(s"Hello, ${request.name}!"))

object HelloWorldServer extends KyoApp:
    run {
        for
            _ <- Console.printLine("Server starting on port 50051...")
            server <- Server.start(50051)(
                _.addService(GreeterService),
                { (server, duration) =>
                    for
                        _ <- Console.print("Shutting down...")
                        _ <- Server.shutdown(server, duration)
                        _ <- Console.printLine("Done.")
                    yield ()
                }
            )
            _ <- Async.never
        yield ()
    }
```

### 4. Create a client

```scala
import kyo.*
import kyo.grpc.*
import helloworld.*

object HelloWorldClient extends KyoApp:
    run {
        for
            client <- Greeter.managedClient("localhost", 50051)(_.usePlaintext())
            request = HelloRequest(name = "World")
            response <- client.sayHello(request)
            _ <- Console.printLine(s"Response: $response")
        yield ()
    }
```

## gRPC Call Types

### Unary

Single request, single response:

```scala
def sayHello(request: HelloRequest): HelloReply < Grpc
```

### Server Streaming

Single request, stream of responses:

```scala
def listItems(request: ListRequest): Stream[Item, Grpc] < Grpc
```

### Client Streaming

Stream of requests, single response:

```scala
def uploadItems(items: Stream[Item, Grpc]): UploadResult < Grpc
```

### Bidirectional Streaming

Stream of requests, stream of responses:

```scala
def chat(messages: Stream[Message, Grpc]): Stream[Message, Grpc] < Grpc
```

## Error Handling

Service methods can fail using Kyo's `Abort` effect:

```scala
import io.grpc.Status

override def sayHello(request: HelloRequest): HelloReply < Grpc =
    if request.name.isEmpty then
        Abort.fail(Status.INVALID_ARGUMENT.withDescription("Name cannot be empty").asException)
    else
        HelloReply(s"Hello, ${request.name}!")
```

Clients handle errors with `Abort.run`:

```scala
for
    client <- Greeter.managedClient("localhost", 50051)(_.usePlaintext())
    result <- Abort.run[StatusException](client.sayHello(HelloRequest("")))
yield result match
    case Result.Success(response) => println(s"Success: $response")
    case Result.Failure(ex)       => println(s"gRPC error: ${ex.getStatus}")
    case Result.Panic(e)          => println(s"Unexpected error: $e")
```

## Resource Management

Both servers and clients are managed via Kyo's `Scope` effect for automatic cleanup:

```scala
// Server is automatically shut down when the scope closes
Server.start(port)(_.addService(myService))

// Channel is automatically closed when the scope closes
Client.channel("host", port)(_.usePlaintext())

// Or use managedClient for channel + client in one step
Greeter.managedClient("host", port)(_.usePlaintext())
```

## Configuration

### Client Options

```scala
for
    client <- Greeter.managedClient("api.example.com", 443)(
        _.useTransportSecurity()
         .keepAliveTime(30, TimeUnit.SECONDS)
         .maxInboundMessageSize(4 * 1024 * 1024)
    )
yield client
```

### Request Options

```scala
import io.grpc.Metadata

val metadata = Metadata()
metadata.put(key, "value")

for
    client <- Greeter.managedClient("host", port)(_.usePlaintext())
    response <- RequestOptions.run {
        Emit.value(RequestOptions(headers = Maybe(metadata))) {
            client.sayHello(HelloRequest("World"))
        }
    }
yield response
```

## Project Structure

- [`core`](./core/): Runtime library with server, client, and call handlers
- [`code-gen`](./code-gen): Protoc plugin for generating Kyo-native gRPC code
- [`e2e`](./e2e): End-to-end integration tests
- [`examples/helloworld`](./examples/helloworld): Complete HelloWorld example

## Development

Run tests:

```bash
sbt kyo-grpc-core/test
sbt kyo-grpc-code-gen/test
sbt kyo-grpc-e2e/test
```

Run the example:

```bash
cd examples/helloworld
sbt "runMain kgrpc.HelloWorldServer"
# In another terminal:
sbt "runMain kgrpc.HelloWorldClient"
```

## How It Works

The kyo-grpc code generator produces:

1. **Service Trait**: A Scala trait extending `kyo.grpc.Service` with methods returning Kyo effects
2. **Client Class**: A typed client with methods for each RPC call
3. **Server Definition**: gRPC server handlers that bridge between Kyo effects and the gRPC runtime

The runtime handles:
- Converting between Kyo `Stream` and gRPC's async streaming
- Managing backpressure via gRPC flow control
- Propagating errors through Kyo's `Abort` effect
- Ensuring proper resource cleanup via `Scope`

## Dependencies

- [grpc-java](https://github.com/grpc/grpc-java) for the underlying gRPC transport
- [ScalaPB](https://scalapb.github.io/) for protobuf code generation
- [Kyo](https://getkyo.io) core effects (Async, Abort, Stream, Scope, etc.)

## License

Apache License 2.0 (same as Kyo)
