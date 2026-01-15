package kyo.grpc.compiler

import com.google.protobuf.DescriptorProtos.*
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import org.scalatest.freespec.AnyFreeSpec
import protocgen.CodeGenRequest
import scalapb.options.Scalapb
import scalapb.options.Scalapb.ScalaPbOptions

class CodeGeneratorTest extends AnyFreeSpec {

    "CodeGenerator" - {
        "process a request with default options" in {
            val protoFile = createTestProtoDescriptor()

            val response = testProcess(protoFile)

            assert(response.getError.isEmpty)
            assert(response.getUnknownFields.asMap().isEmpty)
            assert(response.getFileCount === 2)

            val file1 = response.getFile(0)
            assert(file1.getUnknownFields.asMap().isEmpty)
            assert(file1.getName === "kgrpc/test/TestService.scala")
            val expected1 = scala.io.Source.fromResource("output/multiple-files-1").mkString
            assert(file1.getContent === expected1)

            val file2 = response.getFile(1)
            assert(file2.getUnknownFields.asMap().isEmpty)
            assert(file2.getName === "kgrpc/test/UtilityService.scala")
            val expected2 = scala.io.Source.fromResource("output/multiple-files-2").mkString
            assert(file2.getContent === expected2)
        }
        "process a request with single file" in {
            val options =
                ScalaPbOptions
                    .newBuilder()
                    .setSingleFile(true)
                    .build()

            val protoFile = createTestProtoDescriptor(options)

            val response = testProcess(protoFile)

            assert(response.getError.isEmpty)
            assert(response.getUnknownFields.asMap().isEmpty)
            assert(response.getFileCount === 1)

            val file = response.getFile(0)
            assert(file.getUnknownFields.asMap().isEmpty)
            assert(file.getName === "kgrpc/test/TestProto.scala")
            val expected = scala.io.Source.fromResource("output/single-file").mkString
            assert(file.getContent === expected)
        }
    }

    private def testProcess(protoFile: FileDescriptorProto) = {
        val version = PluginProtos.Version.newBuilder().setMajor(3).setMinor(21).setPatch(7).build()

        val request = CodeGenRequest(
            CodeGeneratorRequest
                .newBuilder()
                .setCompilerVersion(version)
                .setParameter("grpc")
                .addFileToGenerate("test.proto")
                .addProtoFile(protoFile)
                .build()
        )

        CodeGenerator.process(request).toCodeGeneratorResponse
    }

    private def createTestProtoDescriptor(options: ScalaPbOptions = ScalaPbOptions.getDefaultInstance): FileDescriptorProto = {
        // Create oneof for Request
        val requestOneof = OneofDescriptorProto.newBuilder()
            .setName("sealed_value")
            .build()

        val requestMessage = DescriptorProto.newBuilder()
            .setName("Request")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("success")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".kgrpc.Success")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOneofIndex(0)
                .setJsonName("success"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("fail")
                .setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".kgrpc.Fail")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOneofIndex(0)
                .setJsonName("fail"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("panic")
                .setNumber(3)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".kgrpc.Panic")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOneofIndex(0)
                .setJsonName("panic"))
            .addOneofDecl(requestOneof)
            .build()

        // Create oneof for Response
        val responseOneof = OneofDescriptorProto.newBuilder()
            .setName("sealed_value")
            .build()

        val responseMessage = DescriptorProto.newBuilder()
            .setName("Response")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("echo")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".kgrpc.Echo")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOneofIndex(0)
                .setJsonName("echo"))
            .addOneofDecl(responseOneof)
            .build()

        val successMessage = DescriptorProto.newBuilder()
            .setName("Success")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("message")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("message"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("count")
                .setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_INT32)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("count"))
            .build()

        val failMessage = DescriptorProto.newBuilder()
            .setName("Fail")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("code")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_INT32)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("code"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("after")
                .setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_INT32)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("after"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("outside")
                .setNumber(3)
                .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("outside"))
            .build()

        val panicMessage = DescriptorProto.newBuilder()
            .setName("Panic")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("message")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("message"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("after")
                .setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_INT32)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("after"))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("outside")
                .setNumber(3)
                .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("outside"))
            .build()

        val echoMessage = DescriptorProto.newBuilder()
            .setName("Echo")
            .addField(FieldDescriptorProto.newBuilder()
                .setName("message")
                .setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setJsonName("message"))
            .build()

        // Create services
        val testService = ServiceDescriptorProto.newBuilder()
            .setName("TestService")
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("OneToOne")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(false)
                .setServerStreaming(false))
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("OneToMany")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(false)
                .setServerStreaming(true))
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("ManyToOne")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(true)
                .setServerStreaming(false))
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("ManyToMany")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(true)
                .setServerStreaming(true))
            .build()

        val utilityService = ServiceDescriptorProto.newBuilder()
            .setName("UtilityService")
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("Health")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(false)
                .setServerStreaming(false))
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("Monitor")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(false)
                .setServerStreaming(true))
            .addMethod(MethodDescriptorProto.newBuilder()
                .setName("Batch")
                .setInputType(".kgrpc.Request")
                .setOutputType(".kgrpc.Response")
                .setClientStreaming(true)
                .setServerStreaming(false))
            .build()

        // Create the file descriptor
        FileDescriptorProto.newBuilder()
            .setName("test.proto")
            .setPackage("kgrpc")
            .setSyntax("proto3")
            .setOptions(FileOptions.newBuilder().setExtension(Scalapb.options, options))
            .addMessageType(requestMessage)
            .addMessageType(responseMessage)
            .addMessageType(successMessage)
            .addMessageType(failMessage)
            .addMessageType(panicMessage)
            .addMessageType(echoMessage)
            .addService(testService)
            .addService(utilityService)
            .build()
    }
}
