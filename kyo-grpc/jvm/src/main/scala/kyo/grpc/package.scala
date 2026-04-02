package kyo.grpc

import io.grpc.*

/** Common types for kyo-grpc */
package object grpc:
    
    /** Metadata key for gRPC headers */
    opaque type MetadataKey[+T] <: Metadata.Key[T] = Metadata.Key[T]
    
    object MetadataKey:
        def apply[T](name: String, marshaller: Marshallers[T]): MetadataKey[T] =
            Metadata.Key.create(marshaller, name)
        def stringKey(name: String): MetadataKey[String] =
            Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)

    /** A gRPC service definition */
    trait ServiceDef[-S]:
        def name: String
        def bind(service: S)(using ServerServiceDefinition): ServerServiceDefinition

    /** Client configuration for gRPC */
    case class GrpcClientConfig(
        host: String = "localhost",
        port: Int = 50051,
        deadline: Option[java.time.Duration] = None
    )

    /** Server configuration for gRPC */
    case class GrpcServerConfig(
        host: String = "0.0.0.0",
        port: Int = 50051,
        maxConcurrentCallsPerConnection: Int = Int.MaxValue,
        keepAliveTime: java.time.Duration = java.time.Duration.ofMinutes(2),
        permitKeepAliveTime: java.time.Duration = java.time.Duration.ofMinutes(5)
    )

end grpc
