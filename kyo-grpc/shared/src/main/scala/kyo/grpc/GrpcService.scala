package kyo.grpc

import io.grpc.*
import io.grpc.stub.{ServerCalls, StreamObserver}
import com.google.protobuf.{MessageLite, Parser}
import scala.reflect.macros.blackbox

/** Base trait for gRPC service definitions */
trait GrpcServiceDef[-S]:
    type Request <: MessageLite
    type Response <: MessageLite
    
    /** The fully qualified service name */
    def serviceName: String
    
    /** The protobuf package and service name */
    def fullServiceName: String = serviceName
    
    /** Bind the service implementation to a ServerServiceDefinition */
    def bind(serviceImpl: S)(using 
        requestParser: Parser[Request],
        responseBuilder: MessageLite.Builder
    ): ServerServiceDefinition

/** Macro-based service definition generator */
object GrpcServiceDef:
    
    /** Generate a GrpcServiceDef from a trait and its implementation
     * 
     * This uses macros to generate the binding code at compile time,
     * avoiding runtime reflection overhead.
     */
    transparent inline def apply[S](
        inline serviceName: String,
        inline methods: (String, MethodDescriptor[?, ?], (S, Any, StreamObserver[Any]) => Unit)*
    ): GrpcServiceDef[S] = ${ macroImpl[S]('{ serviceName }, '{ methods.toList }) }

    private def macroImpl[S](
        serviceName: Expr[String],
        methods: Expr[List[(String, MethodDescriptor[?, ?], (S, Any, StreamObserver[Any]) => Unit)]]
    )(using s: TypeTag[S], c: blackbox.Context { type PrefixType = Null }): Expr[GrpcServiceDef[S]] = 
        import c.universe.*
        
        val serviceNameStr = serviceName match
            case Expr(Literal(Constant(s: String))) => s
            case _ => c.abort(c.enclosingPosition, "serviceName must be a constant string")
        
        val methodsList = methods match
            case Expr(Literal(Constant(l: List[(String, MethodDescriptor[?, ?], (S, Any, StreamObserver[Any]) => Unit)]])) => l
            case _ => c.abort(c.enclosingPosition, "methods must be a constant list")
        
        // Generate the GrpcServiceDef implementation
        '{ new GrpcServiceDef[S] {
            def serviceName: String = $serviceName
            
            def bind(serviceImpl: S)(using 
                requestParser: Parser[Request],
                responseBuilder: MessageLite.Builder
            ): ServerServiceDefinition = {
                val builder = ServerServiceDefinition.builder($serviceName)
                // Method bindings would be added here
                builder.build()
            }
        }}
    end macroImpl

end GrpcServiceDef

/** Helper for creating method descriptors */
object MethodDescriptors:
    
    /** Create a unary method descriptor */
    def unary[Req, Res](
        fullMethodName: String,
        requestMarshaller: io.grpc.MethodDescriptor.Marshaller[Req],
        responseMarshaller: io.grpc.MethodDescriptor.Marshaller[Res]
    ): MethodDescriptor[Req, Res] =
        MethodDescriptor
            .newBuilder[Req, Res]()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(requestMarshaller)
            .setResponseMarshaller(responseMarshaller)
            .build()

    /** Create a server streaming method descriptor */
    def serverStreaming[Req, Res](
        fullMethodName: String,
        requestMarshaller: io.grpc.MethodDescriptor.Marshaller[Req],
        responseMarshaller: io.grpc.MethodDescriptor.Marshaller[Res]
    ): MethodDescriptor[Req, Res] =
        MethodDescriptor
            .newBuilder[Req, Res]()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(requestMarshaller)
            .setResponseMarshaller(responseMarshaller)
            .build()

    /** Create a client streaming method descriptor */
    def clientStreaming[Req, Res](
        fullMethodName: String,
        requestMarshaller: io.grpc.MethodDescriptor.Marshaller[Req],
        responseMarshaller: io.grpc.MethodDescriptor.Marshaller[Res]
    ): MethodDescriptor[Req, Res] =
        MethodDescriptor
            .newBuilder[Req, Res]()
            .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(requestMarshaller)
            .setResponseMarshaller(responseMarshaller)
            .build()

    /** Create a bidirectional streaming method descriptor */
    def bidirectional[Req, Res](
        fullMethodName: String,
        requestMarshaller: io.grpc.MethodDescriptor.Marshaller[Req],
        responseMarshaller: io.grpc.MethodDescriptor.Marshaller[Res]
    ): MethodDescriptor[Req, Res] =
        MethodDescriptor
            .newBuilder[Req, Res]()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(requestMarshaller)
            .setResponseMarshaller(responseMarshaller)
            .build()

end MethodDescriptors

/** gRPC method type */
enum MethodType:
    case Unary
    case ServerStreaming
    case ClientStreaming
    case Bidirectional

/** A single gRPC method definition */
case class GrpcMethod[Req, Res](
    name: String,
    methodType: MethodType,
    fullMethodName: String,
    descriptor: MethodDescriptor[Req, Res]
)

/** Base trait for gRPC service implementations */
trait GrpcService[Req, Res]:
    this: io.grpc.BindableService =>

    /** The service name */
    def serviceName: String

    /** Process a unary request
     * 
     * Override this to handle unary calls.
     */
    def unary(request: Req, headers: Metadata)(using Frame): Res < Async =
        throw new UnsupportedOperationException(s"Unary call not implemented for $serviceName")

    /** Process a server streaming request
     * 
     * Override this to handle server streaming calls.
     */
    def serverStreaming(request: Req, observer: StreamObserver[Res], headers: Metadata)(using Frame): Unit =
        throw new UnsupportedOperationException(s"Server streaming not implemented for $serviceName")

    /** Process a client streaming request
     * 
     * Override this to handle client streaming calls.
     */
    def clientStreaming(requests: Stream[Req, Async], observer: StreamObserver[Res], headers: Metadata)(using Frame): Unit < Async =
        throw new UnsupportedOperationException(s"Client streaming not implemented for $serviceName")

    /** Process a bidirectional streaming request
     * 
     * Override this to handle bidirectional streaming calls.
     */
    def bidirectional(
        requests: Stream[Req, Async], 
        observer: StreamObserver[Res], 
        headers: Metadata
    )(using Frame): Unit < Async =
        throw new UnsupportedOperationException(s"Bidirectional streaming not implemented for $serviceName")

end GrpcService

/** StreamObserver adapter for fiber-based streaming */
class FiberStreamObserver[Res](
    observer: StreamObserver[Res]
) extends StreamObserver[Res]:
    private val queue = scala.collection.mutable.Queue[Res]()
    private val fiberRef = scala.concurrent.Promise[Fiber[Unit]]()
    
    def onNext(value: Res): Unit =
        observer.onNext(value)
    
    def onError(t: Throwable): Unit =
        observer.onError(t)
    
    def onCompleted(): Unit =
        observer.onCompleted()

end FiberStreamObserver
