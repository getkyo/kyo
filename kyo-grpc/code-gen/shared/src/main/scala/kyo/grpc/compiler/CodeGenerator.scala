package kyo.grpc.compiler

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import kyo.grpc.compiler.internal.FilePrinter
import protocbridge.Artifact
import protocbridge.ProtocCodeGenerator
import protocgen.CodeGenRequest
import protocgen.CodeGenResponse
import scala.collection.mutable
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.GeneratorException
import scalapb.compiler.ProtobufGenerator
import scalapb.options.Scalapb

object CodeGenerator extends ProtocCodeGenerator {

    def registerExtensions(registry: ExtensionRegistry): Unit =
        Scalapb.registerAllExtensions(registry)

    override def run(input: Array[Byte]): Array[Byte] = {
        val registry = ExtensionRegistry.newInstance()
        registerExtensions(registry)
        val request = CodeGeneratorRequest.parseFrom(input, registry)
        process(toCodeGenRequest(request)).toCodeGeneratorResponse.toByteArray
    }

    // When your code generator will be invoked from SBT via sbt-protoc, this will add the following
    // artifact to your users build whenever the generator is used in `PB.targets`:
    override def suggestedDependencies: Seq[Artifact] =
        Seq(
            Artifact(
                BuildInfo.organization,
                "kyo-grpc-core",
                BuildInfo.version,
                crossVersion = true
            )
        )

    // This is called by CodeGenApp after the request is parsed.
    // Example: scalapb.compiler.ProtobufGenerator.handleCodeGeneratorRequest
    def process(request: CodeGenRequest): CodeGenResponse =
        ProtobufGenerator.parseParameters(request.parameter) match {
            case Right(params) =>
                try {
                    val implicits = DescriptorImplicits.fromCodeGenRequest(params, request)
                    import implicits.ExtendedFileDescriptor
                    val files = request.filesToGenerate.filterNot(_.disableOutput).flatMap { file =>
                        if (file.scalaOptions.getSingleFile)
                            Seq(singleFile(file, implicits))
                        else
                            multipleFiles(file, implicits)
                    }
                    CodeGenResponse.succeed(files, Set(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL))
                } catch {
                    case e: GeneratorException =>
                        CodeGenResponse.fail(e.message)
                }
            case Left(error) =>
                CodeGenResponse.fail(error)
        }

    private def javaSeq[A](values: java.util.List[A]): Seq[A] = {
        val builder  = Seq.newBuilder[A]
        val iterator = values.iterator()
        while (iterator.hasNext) builder += iterator.next()
        builder.result()
    }

    private def singleFile(file: FileDescriptor, implicits: DescriptorImplicits) =
        javaSeq(file.getServices).foldLeft(FilePrinter(file, implicits).addPackage) { (fp, service) =>
            fp.addService(service)
        }.result

    private def multipleFiles(file: FileDescriptor, implicits: DescriptorImplicits) =
        javaSeq(file.getServices).map { service =>
            FilePrinter(file, implicits).addPackage.addService(service).setNameFromService(service).result
        }

    private def toCodeGenRequest(request: CodeGeneratorRequest): CodeGenRequest = {
        val protosByName = javaSeq(request.getProtoFileList).map(proto => proto.getName -> proto).toMap
        val descriptors  = mutable.Map.empty[String, FileDescriptor]

        def descriptor(name: String): FileDescriptor =
            descriptors.getOrElseUpdate(
                name, {
                    val proto        = protosByName(name)
                    val dependencies = javaSeq(proto.getDependencyList).map(descriptor).toArray
                    FileDescriptor.buildFrom(proto, dependencies)
                }
            )

        val filesToGenerate = javaSeq(request.getFileToGenerateList).map(descriptor)
        val allProtos       = javaSeq(request.getProtoFileList).map(proto => descriptor(proto.getName))
        val compilerVersion = if (request.hasCompilerVersion) Some(request.getCompilerVersion) else None
        CodeGenRequest(request.getParameter, filesToGenerate, allProtos, compilerVersion, request)
    }
}
