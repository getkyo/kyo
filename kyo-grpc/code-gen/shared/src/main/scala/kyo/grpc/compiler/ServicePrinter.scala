package kyo.grpc.compiler

import com.google.protobuf.Descriptors.*

import scala.jdk.CollectionConverters.*
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.NameUtils
import scalapb.compiler.ProtobufGenerator.asScalaDocBlock
import scalapb.compiler.StreamType

case class ServicePrinter(service: ServiceDescriptor, implicits: DescriptorImplicits, fp: FunctionalPrinter = new FunctionalPrinter()) {

    import implicits.*

    private val name = NameUtils.snakeCaseToCamelCase(service.getName, upperInitial = true)

    def addTrait: ServicePrinter =
        copy(fp = fp.call(printScalaDoc).call(printServiceTrait).newline)

    def addObject: ServicePrinter =
        copy(fp = fp.call(printServiceObject).newline)

    private def printScalaDoc: PrinterEndo = {
        val lines = asScalaDocBlock(service.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
        _.add(lines *)
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
        // TODO: De-duplicate.
        def requestParameter  = "request" :- method.inputType.scalaType
        def requestsParameter = "requests" :- Types.streamGrpcRequest(method.inputType.scalaType)
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(requestsParameter)
            case StreamType.ServerStreaming => Seq(requestParameter)
            case StreamType.Bidirectional   => Seq(requestsParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcResponse(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.pendingGrpcResponse(method.outputType.scalaType)
            case StreamType.ServerStreaming => Types.pendingGrpcResponse(Types.streamGrpcResponse(method.outputType.scalaType))
            case StreamType.Bidirectional   => Types.pendingGrpcResponse(Types.streamGrpcResponse(method.outputType.scalaType))
        }
        _.call(printScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParameterList(parameters*)
            .addReturnType(returnType)
    }

    private def printScalaDoc(method: MethodDescriptor): PrinterEndo = {
        val lines = asScalaDocBlock(method.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
        _.add(lines *)
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
        // TODO: Simplify this.
        val handler = method.streamType match {
            case StreamType.Unary           => s"${Types.serverHandler}.unary(serviceImpl.${method.name})"
            case StreamType.ClientStreaming => s"${Types.serverHandler}.clientStreaming(serviceImpl.${method.name})"
            case StreamType.ServerStreaming => s"${Types.serverHandler}.serverStreaming(serviceImpl.${method.name})"
            case StreamType.Bidirectional   => s"${Types.serverHandler}.bidiStreaming(serviceImpl.${method.name})"
        }
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
                _.add(s"ClientImpl(channel, options)")
            )

    private def printClientTrait: PrinterEndo =
        _.addTrait("Client")
            .addBody {
                _.print(service.getMethods.asScala) { (fp, md) =>
                    printClientServiceMethod(fp, md)
                }
            }

    private def printClientServiceMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        // TODO: De-duplicate.
        def requestParameter  = "request" :- method.inputType.scalaType
        def requestsParameter = "requests" :- Types.streamGrpcRequest(method.inputType.scalaType)
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(requestsParameter)
            case StreamType.ServerStreaming => Seq(requestParameter)
            case StreamType.Bidirectional   => Seq(requestsParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ServerStreaming => Types.streamGrpcRequest(method.outputType.scalaType)
            case StreamType.Bidirectional   => Types.streamGrpcRequest(method.outputType.scalaType)
        }
        fp
            .call(printScalaDoc(method))
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
        // TODO: De-duplicate.
        def requestParameter  = "request" :- method.inputType.scalaType
        def requestsParameter = "requests" :- Types.streamGrpcRequest(method.inputType.scalaType)
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(requestsParameter)
            case StreamType.ServerStreaming => Seq(requestParameter)
            case StreamType.Bidirectional   => Seq(requestsParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ServerStreaming => Types.streamGrpcRequest(method.outputType.scalaType)
            case StreamType.Bidirectional   => Types.streamGrpcRequest(method.outputType.scalaType)
        }
        // TODO: Simplify this.
        val delegate = method.streamType match {
            case StreamType.Unary => s"unary(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, request)"
            case StreamType.ClientStreaming =>
                s"clientStreaming(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, requests)"
            case StreamType.ServerStreaming => s"serverStreaming(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, request)"
            case StreamType.Bidirectional   => s"bidiStreaming(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, requests)"
        }
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
}
