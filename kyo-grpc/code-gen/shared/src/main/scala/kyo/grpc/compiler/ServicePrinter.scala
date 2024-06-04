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

    private def content: String = {
        new FunctionalPrinter()
            .addPackage(scalaPackage.fullName)
            .newline
            .call(scalapbServicePrinter.generateScalaDoc(service))
            .addTrait(name)(_.print(service.getMethods.asScala) { (fp, md) => printMethod(fp, md) })
            .result()
    }

    private def printMethod(fp: FunctionalPrinter, method: MethodDescriptor): FunctionalPrinter = {
        def requestParameter          = "request"          -> method.inputType.scalaType
        def responseObserverParameter = "responseObserver" -> Types.streamObserver(method.outputType.scalaType)
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
            .addAbstractMethod(method.name, parameters, returnType)
    }
}
