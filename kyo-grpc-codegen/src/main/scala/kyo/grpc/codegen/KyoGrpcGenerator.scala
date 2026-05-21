package kyo.grpc.codegen

import com.google.protobuf.Descriptors.*
import scalapb.compiler.*
import scalapb.compiler.GeneratedFile
import scala.jdk.CollectionConverters.*

object KyoGrpcGenerator extends Generator:
    override def generate(file: FileDescriptor, implicits: DescriptorImplicits): Seq[GeneratedFile] =
        file.getServices.asScala.toSeq.map { service =>
            val p = new FunctionalPrinter()
            val serviceName = service.getName
            val packageName = file.getPackage
            
            p.add(s"package $packageName")
                .add("")
                .add("import kyo.*")
                .add("import kyo.grpc.*")
                .add("import io.grpc.*")
                .add("import io.grpc.stub.ServerCalls")
                .add("")
                .add(s"trait ${serviceName}Kyo:")
            
            service.getMethods.asScala.foreach { method =>
                val m = implicits.methodDescriptorPimp(method)
                val inputType = m.inputType.scalaType
                val outputType = m.outputType.scalaType
                val methodName = m.name
                
                if (method.isClientStreaming && method.isServerStreaming) {
                    p.add(s"    def $methodName(request: Stream[$inputType, Async]): Stream[$outputType, Abort[StatusException] & Async]")
                } else if (method.isClientStreaming) {
                    p.add(s"    def $methodName(request: Stream[$inputType, Async]): $outputType < (Abort[StatusException] & Async)")
                } else if (method.isServerStreaming) {
                    p.add(s"    def $methodName(request: $inputType): Stream[$outputType, Abort[StatusException] & Async]")
                } else {
                    p.add(s"    def $methodName(request: $inputType): $outputType < (Abort[StatusException] & Async)")
                }
            }
            
            p.add(s"end ${serviceName}Kyo")
                .add("")
                .add(s"object ${serviceName}Kyo:")
                .add(s"    def bindService(impl: ${serviceName}Kyo)(using Frame, Tag[Emit[Chunk[Any]]]): ServerServiceDefinition =")
                .add(s"        ServerServiceDefinition.builder(\"$packageName.$serviceName\")")
            
            service.getMethods.asScala.foreach { method =>
                val m = implicits.methodDescriptorPimp(method)
                val methodName = m.name
                val handler = if (method.isClientStreaming && method.isServerStreaming) "bidiStreamingHandler"
                              else if (method.isClientStreaming) "clientStreamingHandler"
                              else if (method.isServerStreaming) "serverStreamingHandler"
                              else "unaryHandler"
                
                val methodDescriptorName = s"${serviceName}Grpc.METHOD_${method.getName.toUpperCase}"
                p.add(s"            .addMethod($methodDescriptorName, Grpcs.$handler($methodDescriptorName, impl.$methodName))")
            }
            p.add("            .build()")
                .add("")
                .add(s"    class Client(channel: io.grpc.Channel)(using Frame, Tag[Emit[Chunk[Any]]]):")
            
            service.getMethods.asScala.foreach { method =>
                val m = implicits.methodDescriptorPimp(method)
                val inputType = m.inputType.scalaType
                val outputType = m.outputType.scalaType
                val methodName = m.name
                val methodDescriptorName = s"${serviceName}Grpc.METHOD_${method.getName.toUpperCase}"
                
                if (method.isClientStreaming && method.isServerStreaming) {
                    p.add(s"        def $methodName(request: Stream[$inputType, Async]): Stream[$outputType, Async] =")
                        .add(s"            Grpcs.bidiStreamingCall(channel, $methodDescriptorName, request)")
                } else if (method.isClientStreaming) {
                    p.add(s"        def $methodName(request: Stream[$inputType, Async]): $outputType < (Abort[StatusException] & Async) =")
                        .add(s"            Grpcs.clientStreamingCall(channel, $methodDescriptorName, request)")
                } else if (method.isServerStreaming) {
                    p.add(s"        def $methodName(request: $inputType): Stream[$outputType, Async] =")
                        .add(s"            Grpcs.serverStreamingCall(channel, $methodDescriptorName, request)")
                } else {
                    p.add(s"        def $methodName(request: $inputType): $outputType < (Abort[StatusException] & Async) =")
                        .add(s"            Grpcs.unaryCall(channel, $methodDescriptorName, request)")
                }
            }
            p.add("    end Client")
                .add(s"end ${serviceName}Kyo")
            
            GeneratedFile(file.getName.replace(".proto", "") + "Kyo.scala", p.result())
        }
end KyoGrpcGenerator
