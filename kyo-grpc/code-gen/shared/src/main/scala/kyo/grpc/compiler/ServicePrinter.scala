package kyo.grpc.compiler

import com.google.protobuf.Descriptors.*
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, NameUtils, StreamType}

import scala.jdk.CollectionConverters.*

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
            .call(printServiceObject)
            .result()

    private def printServiceTrait(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addTrait(name)
            .addAnnotations(Seq(service.deprecatedAnnotation).filter(_.nonEmpty)*)
            .addParents(Types.abstractService)
            .addBody {
                _.call(printServiceCompanionMethod)
                    .print(service.getMethods.asScala) { (fp, md) =>
                        printServiceMethod(fp, md)
                    }
            }

    private def printServiceCompanionMethod(fp: FunctionalPrinter): FunctionalPrinter =
        fp.addMethod("serviceCompanion")
            .addMods(_.Override)
            .addReturnType(s"$name.type")
            .addBody(_.add(name))

    private def printServiceMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        def requestParameter          = "request"          -> method.inputType.scalaType
        def responseObserverParameter = "responseObserver" -> Types.streamObserver(method.outputType.scalaType)
        // TODO: Only unary is done properly.
        val parameters = method.streamType match {
            case StreamType.Unary           => Seq(requestParameter)
            case StreamType.ClientStreaming => Seq(responseObserverParameter)
            case StreamType.ServerStreaming => Seq(requestParameter, responseObserverParameter)
            case StreamType.Bidirectional   => Seq(responseObserverParameter)
        }
        val returnType = method.streamType match {
            case StreamType.Unary           => Types.pendingGrpcResponses(method.outputType.scalaType)
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
            .addParents(Types.serviceCompanion(name))
            .addBody {
                _.addMethod("javaDescriptor")
                    .addReturnType(Types.javaServiceDescriptor)
                    .addBody(_.add(service.javaDescriptorSource))
                    .endMethod
                    .addMethod("scalaDescriptor")
                    .addReturnType(Types.serviceDescriptor)
                    .addBody(_.add(service.scalaDescriptorSource))
                    .endMethod
                    .call(printBindServiceMethod)
            }

    private def printBindServiceMethod(fp: FunctionalPrinter): FunctionalPrinter = {
        val methods = service.methods.map(printMethodImplementation)
        fp.addMethod("bindService")
            .addParameterList("serviceImpl" -> name, "executionContext" -> Types.executionContext)
            .addReturnType(Types.serverServiceDefinition)
            .addBody(
                _.add(s"""${Types.serverServiceDefinition}.builder(${service.grpcDescriptor.fullNameWithMaybeRoot})""")
                    .call(methods*)
                    .add(".build()")
            )
    }

    private def printMethodImplementation(method: MethodDescriptor)(fp: FunctionalPrinter): FunctionalPrinter = {
        val callOpening = method.streamType match {
            case StreamType.Unary           => s"${Types.serverCalls}.asyncUnaryCall { (request, responseObserver) =>"
            case StreamType.ClientStreaming => s"${Types.serverCalls}.asyncClientStreamingCall { responseObserver =>"
            case StreamType.ServerStreaming => s"${Types.serverCalls}.asyncServerStreamingCall { (request, responseObserver) =>"
            case StreamType.Bidirectional   => s"${Types.serverCalls}.asyncBidiStreamingCall { responseObserver =>"
        }
        fp.add(".addMethod(")
            .indented(
                _.add(s"${method.grpcDescriptor.fullNameWithMaybeRoot},")
                    .add(callOpening)
                    .indented(
                        _.add(s"val fiber = ${Types.grpcResponses}.init(serviceImpl.${method.name}(request))")
                    )
                    .add("}")
            )
            .add(")")
    }
}
