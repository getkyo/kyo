package kyo.grpc.compiler.internal

import com.google.protobuf.Descriptors.*
import scala.jdk.CollectionConverters.*
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.NameUtils
import scalapb.compiler.ProtobufGenerator.asScalaDocBlock
import scalapb.compiler.StreamType

private[compiler] case class ServicePrinter(
    service: ServiceDescriptor,
    implicits: DescriptorImplicits,
    fp: FunctionalPrinter = new FunctionalPrinter()
) {

    import implicits.*

    private val name = NameUtils.snakeCaseToCamelCase(service.getName, upperInitial = true)

    // noinspection MutatorLikeMethodIsParameterless
    def addTrait: ServicePrinter =
        copy(fp = fp.call(printScalaDoc).call(printServiceTrait).newline)

    // noinspection MutatorLikeMethodIsParameterless
    def addObject: ServicePrinter =
        copy(fp = fp.call(printServiceObject).newline)

    private def printScalaDoc: PrinterEndo = {
        val lines = asScalaDocBlock(service.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
        _.add(lines*)
    }

    private def printServiceTrait: PrinterEndo =
        _.addTrait(name)
            .addAnnotations(Seq(service.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParents(Types.service)
            .addBody {
                _.newline
                    .call(printServiceDefinitionMethod)
                    .newline
                    .print(service.getMethods.asScala) { (fp, md) =>
                        printServiceMethod(md)(fp)
                    }
            }

    private def printServiceDefinitionMethod: PrinterEndo =
        _.addMethod("definition")
            .addMods(_.Override)
            .addReturnType(Types.serverServiceDefinition)
            .addBody {
                _.add(s"$name.service(this)")
            }

    private def printServiceMethod(method: MethodDescriptor): PrinterEndo = {
        val parameters = methodParameters(method)
        val returnType = serviceMethodReturnType(method)
        _.call(printScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParameterList(parameters*)
            .addReturnType(returnType)
    }

    private def printScalaDoc(method: MethodDescriptor): PrinterEndo = {
        val lines = asScalaDocBlock(method.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
        _.add(lines*)
    }

    private def printServiceObject: PrinterEndo =
        _.addObject(name)
            .addAnnotations(Seq(service.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addBody {
                _.newline
                    .call(printServerMethod)
                    .newline
                    .call(printClientMethod)
                    .newline
                    .call(printManagedClientMethod)
                    .newline
                    .call(printClientTrait)
                    .newline
                    .call(printClientImpl)
            }

    private def printServerMethod: PrinterEndo = {
        val methods = service.methods.map(printAddMethod)
        _.addMethod("service")
            .addParameterList("serviceImpl" :- name)
            .addReturnType(Types.serverServiceDefinition)
            .addBody(
                _.add(s"""${Types.serverServiceDefinition}.builder(${service.grpcDescriptor.fullNameWithMaybeRoot})""")
                    .call(methods*)
                    .add(".build()")
            )
    }

    private def printAddMethod(method: MethodDescriptor): PrinterEndo = {
        val methodName = method.streamType match {
            case StreamType.Unary           => "unary"
            case StreamType.ClientStreaming => "clientStreaming"
            case StreamType.ServerStreaming => "serverStreaming"
            case StreamType.Bidirectional   => "bidiStreaming"
        }
        val handler = s"${Types.serverHandler}.$methodName(serviceImpl.${method.name})"
        _.add(".addMethod(")
            .indented(
                _.add(s"${method.grpcDescriptor.fullNameWithMaybeRoot},")
                    .add(handler)
            )
            .add(")")
    }

    private def printClientMethod: PrinterEndo =
        _.addMethod("client")
            .addParameterList( //
                "channel" :- Types.channel,
                "options" :- Types.callOptions := (Types.callOptions + ".DEFAULT")
            )
            .addReturnType("Client")
            .addBody(
                _.add("ClientImpl(channel, options)")
            )

    private def printManagedClientMethod: PrinterEndo =
        _.addMethod("managedClient")
            .addParameterList( //
                "host" :- Types.string,
                "port" :- Types.int,
                "timeout" :- Types.duration    := s"${Types.duration}.fromUnits(30, ${Types.duration}.Units.Millis)",
                "options" :- Types.callOptions := (Types.callOptions + ".DEFAULT")
            )
            .addParameterList( //
                "configure" :- s"${Types.managedChannelBuilder("?")} => ${Types.managedChannelBuilder("?")}",
                "shutdown" :- s"(${Types.managedChannel}, ${Types.duration}) => ${Types.frame} ?=> ${Types.pending(Types.any, Types.sync)}" := s"${Types.client}.shutdown"
            )
            .addUsingParameters(Types.frame)
            .addReturnType(Types.pending("Client", s"${Types.resource} & ${Types.sync}"))
            .addBody(
                _.add(s"${Types.client}.channel(host, port, timeout)(configure, shutdown).map($name.client(_, options))")
            )

    private def printClientTrait: PrinterEndo =
        _.addTrait("Client")
            .addBody {
                _.print(service.getMethods.asScala) { (fp, md) =>
                    printClientServiceMethod(md)(fp)
                }
            }

    private def printClientServiceMethod(method: MethodDescriptor): PrinterEndo = {
        val parameters = methodParameters(method)
        val returnType = clientMethodReturnType(method)
        _.call(printScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParameterList(parameters*)
            .addReturnType(returnType)
    }

    private def printClientImpl: PrinterEndo =
        _.addClass("ClientImpl")
            .addParameterList( //
                "channel" :- Types.channel,
                "options" :- Types.callOptions
            )
            .addParents("Client")
            .addBody {
                _.print(service.getMethods.asScala) { (fp, md) =>
                    printClientImplMethod(md)(fp)
                }
            }

    private def printClientImplMethod(method: MethodDescriptor): PrinterEndo = {
        val parameters = methodParameters(method)
        val returnType = clientMethodReturnType(method)
        val delegateName = method.streamType match {
            case StreamType.Unary           => "unary"
            case StreamType.ClientStreaming => "clientStreaming"
            case StreamType.ServerStreaming => "serverStreaming"
            case StreamType.Bidirectional   => "bidiStreaming"
        }
        val requestParameter = method.streamType match {
            case StreamType.Unary | StreamType.ServerStreaming         => "request"
            case StreamType.ClientStreaming | StreamType.Bidirectional => "requests"
        }
        val delegate = s"$delegateName(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, $requestParameter)"
        _.call(printScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addMods(_.Override)
            .addParameterList(parameters*)
            .addReturnType(returnType)
            .addBody(
                _.add(s"${Types.clientCall}.$delegate")
            )
    }

    private def methodParameters(method: MethodDescriptor): Seq[Parameter] = {
        def requestParameter  = "request" :- method.inputType.scalaType
        def requestsParameter = "requests" :- Types.streamGrpcRequest(method.inputType.scalaType)
        method.streamType match {
            case StreamType.Unary | StreamType.ServerStreaming         => Seq(requestParameter)
            case StreamType.ClientStreaming | StreamType.Bidirectional => Seq(requestsParameter)
        }
    }

    private def clientMethodReturnType(method: MethodDescriptor): String = {
        method.streamType match {
            case StreamType.Unary | StreamType.ClientStreaming =>
                Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ServerStreaming | StreamType.Bidirectional =>
                Types.streamGrpcRequest(method.outputType.scalaType)
        }
    }

    private def serviceMethodReturnType(method: MethodDescriptor): String = {
        method.streamType match {
            case StreamType.Unary | StreamType.ClientStreaming =>
                Types.pendingGrpcResponse(method.outputType.scalaType)
            case StreamType.ServerStreaming | StreamType.Bidirectional =>
                Types.pendingGrpcResponse(Types.streamGrpcResponse(method.outputType.scalaType))
        }
    }
}
