package kyo.grpc.compiler

import com.google.protobuf.Descriptors.*
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import scala.jdk.CollectionConverters.*
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.NameUtils
import scalapb.compiler.StreamType

class ServicePrinter(service: ServiceDescriptor, implicits: DescriptorImplicits) {

    import implicits.*

    private val scalaPackage = service.getFile.scalaPackage

    private val dir = scalaPackage.fullName.replace(".", "/")

    private val name = NameUtils.snakeCaseToCamelCase(service.getName, upperInitial = true)

    private val scalapbServicePrinter = new scalapb.compiler.GrpcServicePrinter(service, implicits)

    def result: CodeGeneratorResponse.File = {
        CodeGeneratorResponse.File.newBuilder()
            .setName(s"$dir/$name.scala")
            .setContent(content)
            .build()
    }

    private def content: String =
        new FunctionalPrinter()
            .addPackage(scalaPackage.fullName)
            .newline
            .call(scalapbServicePrinter.generateScalaDoc(service))
            .call(printServiceTrait)
            .newline
            .call(printServiceObject)
            .result()

    private def printServiceTrait(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addTrait(name)
            .addAnnotations(Seq(service.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParents(Types.service)
            .addBody {
                _.newline
                    .call(printServiceDefinitionMethod)
                    .newline
                    .print(service.getMethods.asScala) { (fp, md) =>
                        printServiceMethod(fp, md)
                    }
            }

    private def printServiceDefinitionMethod(fp: FunctionalPrinter): FunctionalPrinter = {
        fp.addMethod("definition")
            .addMods(_.Override)
            .addReturnType(Types.serverServiceDefinition)
            .addBody {
                _.add(s"$name.service(this)")
            }
    }

    private def printServiceMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        def requestParameter          = "request" :- method.inputType.scalaType
        def responseObserverParameter = "responseObserver" :- Types.streamObserver(method.outputType.scalaType)
        // TODO: Only unary has the correct types.
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(responseObserverParameter)
            case StreamType.ServerStreaming => Seq(requestParameter, responseObserverParameter)
            case StreamType.Bidirectional   => Seq(responseObserverParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcResponse(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.streamObserver(method.inputType.scalaType)
            case StreamType.ServerStreaming => Types.unit
            case StreamType.Bidirectional   => Types.streamObserver(method.inputType.scalaType)
        }
        fp
            .call(scalapbServicePrinter.generateScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParameterList(parameters*)
            .addReturnType(returnType)
    }

    private def printServiceObject(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addObject(name)
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

    private def printServerMethod(fp: FunctionalPrinter): FunctionalPrinter = {
        val methods = service.methods.map(printAddMethod)
        fp.addMethod("service")
            .addParameterList("serviceImpl" :- name)
            .addReturnType(Types.serverServiceDefinition)
            .addBody(
                _.add(s"""${Types.serverServiceDefinition}.builder(${service.grpcDescriptor.fullNameWithMaybeRoot})""")
                    .call(methods*)
                    .add(".build()")
            )
    }

    private def printAddMethod(method: MethodDescriptor)(fp: FunctionalPrinter): FunctionalPrinter = {
        val handler = method.streamType match {
            case StreamType.Unary => s"${Types.serverHandler}.unary(serviceImpl.${method.name})"
            case _                => ???
        }
        fp.add(".addMethod(")
            .indented(
                _.add(s"${method.grpcDescriptor.fullNameWithMaybeRoot},")
                    .add(handler)
            )
            .add(")")
    }

    private def printClientMethod(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addMethod("client")
            .addParameterList( //
                "channel" :- Types.channel,
                "options" :- Types.callOptions := (Types.callOptions + ".DEFAULT")
            )
            .addReturnType("Client")
            .addBody(
                _.add(s"ClientImpl(channel, options)")
            )

    private def printClientTrait(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addTrait("Client")
            .addBody {
                _.print(service.getMethods.asScala) { (fp, md) =>
                    printClientServiceMethod(fp, md)
                }
            }

    private def printClientServiceMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        def requestParameter          = "request" :- method.inputType.scalaType
        def responseObserverParameter = "responseObserver" :- Types.streamObserver(method.outputType.scalaType)
        // TODO: Only unary has the correct types.
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(responseObserverParameter)
            case StreamType.ServerStreaming => Seq(requestParameter, responseObserverParameter)
            case StreamType.Bidirectional   => Seq(responseObserverParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.streamObserver(method.inputType.scalaType)
            case StreamType.ServerStreaming => Types.unit
            case StreamType.Bidirectional   => Types.streamObserver(method.inputType.scalaType)
        }
        fp
            .call(scalapbServicePrinter.generateScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParameterList(parameters*)
            .addReturnType(returnType)
    }

    private def printClientImpl(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addClass("ClientImpl")
            .addParameterList( //
                "channel" :- Types.channel,
                "options" :- Types.callOptions
            )
            .addParents("Client")
            .addBody {
                _.print(service.getMethods.asScala) { (fp, md) =>
                    printClientImplMethod(fp, md)
                }
            }

    private def printClientImplMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        def requestParameter          = "request" :- method.inputType.scalaType
        def responseObserverParameter = "responseObserver" :- Types.streamObserver(method.outputType.scalaType)
        // TODO: Only unary has the correct types.
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(responseObserverParameter)
            case StreamType.ServerStreaming => Seq(requestParameter, responseObserverParameter)
            case StreamType.Bidirectional   => Seq(responseObserverParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcRequest(method.outputType.scalaType)
            case StreamType.ClientStreaming => Types.streamObserver(method.inputType.scalaType)
            case StreamType.ServerStreaming => Types.unit
            case StreamType.Bidirectional   => Types.streamObserver(method.inputType.scalaType)
        }
        val delegate = method.streamType match {
            case StreamType.Unary           => s"asyncUnaryCall(channel, ${method.grpcDescriptor.fullNameWithMaybeRoot}, options, request)"
            case StreamType.ClientStreaming => ???
            case StreamType.ServerStreaming => ???
            case StreamType.Bidirectional   => ???
        }
        fp
            .call(scalapbServicePrinter.generateScalaDoc(method))
            .addMethod(method.name)
            .addAnnotations(Seq(method.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addMods(_.Override)
            .addParameterList(parameters*)
            .addReturnType(returnType)
            .addBody(
                _.add(s"${Types.clientCalls}.$delegate")
            )
    }
}
