# kyo-grpc

gRPC runtime support for the [Kyo](https://getkyo.io) effect system.

## Overview

`kyo-grpc` provides seamless integration between gRPC (via grpc-java) and Kyo's effect system. It supports all four gRPC RPC types and manages server/channel lifecycle through Kyo's `Scope` effect.

## Features

- **All 4 gRPC RPC types**: Unary, Server Streaming, Client Streaming, Bidirectional Streaming
- **Kyo-native effects**: Handlers return `A < (Abort[StatusException] & Async)` instead of raw callbacks
- **Automatic lifecycle management**: Servers and channels are managed via `Scope.acquireRelease`
- **Error handling**: gRPC errors are surfaced as `Abort[StatusException]`
- **Streaming support**: Kyo `Stream` integrates with gRPC's `StreamObserver`

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-grpc" % "latest.version"
```

## Usage

### Define a Service

```scala
import io.grpc.*
import kyo.*
import kyo.grpc.Grpcs

// Create a marshaller for your message type
val stringMarshaller = new MethodDescriptor.Marshaller[String]:
    def stream(value: String): java.io.InputStream =
        new java.io.ByteArrayInputStream(value.getBytes("UTF-8"))
    def parse(stream: java.io.InputStream): String =
        new String(stream.readAllBytes(), "UTF-8")

// Define the method descriptor
val echoMethod = MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
    .setType(MethodType.UNARY)
    .setFullMethodName("my.Service/Echo")
    .build()

// Create a handler
val handler = Grpcs.unaryHandler[String, String](
    echoMethod,
    request => Async.defer(s"echo: $request")
)

// Build the service definition
val serviceDef = ServerServiceDefinition.builder("my.Service")
    .addMethod(echoMethod, handler.getServerCallHandler)
    .build()
```

### Start a Server

```scala
Scope.run {
    for
        server <- Grpcs.server(8080, Seq(serviceDef))
        _      <- Console.printLine(s"Server running on port ${server.getPort}")
        _      <- Async.never // Keep running
    yield ()
}
```

### Make a Client Call

```scala
Scope.run {
    for
        channel <- Grpcs.channel("localhost:8080")
        result  <- Grpcs.unaryCall(channel, echoMethod, "hello")
        _       <- Console.printLine(s"Response: $result")
    yield result
}
```

### Server Streaming

```scala
val streamHandler = Grpcs.serverStreamingHandler[String, String](
    streamMethod,
    request => Stream.init(Seq(s"1: $request", s"2: $request", s"3: $request"))
)
```

### Client Streaming

```scala
val clientStreamHandler = Grpcs.clientStreamingHandler[String, String](
    clientStreamMethod,
    stream => stream.runFold("")((acc, s) => acc + s)
)
```

### Bidirectional Streaming

```scala
val bidiHandler = Grpcs.bidiStreamingHandler[String, String](
    bidiMethod,
    stream => stream.map(s => s"echo: $s")
)
```

## API Reference

### `Grpcs.server(port, services)`

Creates a gRPC server. The server is automatically shut down when the enclosing `Scope` exits.

- `port`: Port to bind to (use 0 for any available port)
- `services`: Sequence of `ServerServiceDefinition` to register

Returns: `Server < (Async & Scope)`

### `Grpcs.channel(target)`

Creates a gRPC channel. The channel is automatically shut down when the enclosing `Scope` exits.

- `target`: Target address (e.g., "localhost:8080")

Returns: `ManagedChannel < (Async & Scope)`

### `Grpcs.unaryHandler(method, handler)`

Creates a unary call handler.

- `method`: `MethodDescriptor[Req, Resp]`
- `handler`: `Req => Resp < (Abort[StatusException] & Async)`

Returns: `ServerMethodDefinition[Req, Resp]`

### `Grpcs.serverStreamingHandler(method, handler)`

Creates a server streaming handler.

- `method`: `MethodDescriptor[Req, Resp]`
- `handler`: `Req => Stream[Resp, Abort[StatusException] & Async]`

Returns: `ServerMethodDefinition[Req, Resp]`

### `Grpcs.clientStreamingHandler(method, handler)`

Creates a client streaming handler.

- `method`: `MethodDescriptor[Req, Resp]`
- `handler`: `Stream[Req, Async] => Resp < (Abort[StatusException] & Async)`

Returns: `ServerMethodDefinition[Req, Resp]`

### `Grpcs.bidiStreamingHandler(method, handler)`

Creates a bidirectional streaming handler.

- `method`: `MethodDescriptor[Req, Resp]`
- `handler`: `Stream[Req, Async] => Stream[Resp, Abort[StatusException] & Async]`

Returns: `ServerMethodDefinition[Req, Resp]`

### `Grpcs.unaryCall(channel, method, request)`

Performs a unary gRPC call from the client side.

- `channel`: The gRPC channel
- `method`: `MethodDescriptor[Req, Resp]`
- `request`: The request message

Returns: `Resp < (Abort[StatusException] & Async)`

## Error Handling

Errors in handlers are automatically converted to gRPC status codes:

- `Abort[StatusException]` failures are propagated as-is
- Panics are converted to `Status.INTERNAL`

On the client side, use `Abort.run[StatusException]` to catch errors:

```scala
Abort.run[StatusException](Grpcs.unaryCall(channel, method, request)).map {
    case Result.Success(resp)       => handleSuccess(resp)
    case Result.Failure(statusEx)   => handleGrpcError(statusEx)
    case Result.Panic(ex)           => handleUnexpected(ex)
}
```

## Future Work

- ScalaPB code generator for generating Kyo service traits from `.proto` files
- Additional client helpers for streaming calls
- TLS/mTLS support helpers
- Benchmarks comparing with zio-grpc and fs2-grpc

## License

Apache 2.0
