package kyo.grpc.compiler

import com.google.protobuf.Descriptors.*
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import kyo.grpc.CollectionConverters.*
import protocbridge.Artifact
import protocgen.CodeGenApp
import protocgen.CodeGenRequest
import protocgen.CodeGenResponse
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.ProtobufGenerator
import scalapb.options.Scalapb

object CodeGenerator extends CodeGenApp:

    override def registerExtensions(registry: ExtensionRegistry): Unit =
        Scalapb.registerAllExtensions(registry)

    // When your code generator will be invoked from SBT via sbt-protoc, this will add the following
    // artifact to your users build whenver the generator is used in `PB.targets`:
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
    def process(request: CodeGenRequest): CodeGenResponse =
        ProtobufGenerator.parseParameters(request.parameter) match
            case Right(params) =>
                // Implicits gives you extension methods that provide ScalaPB names and types
                // for protobuf entities.
                val implicits =
                    DescriptorImplicits.fromCodeGenRequest(params, request)

                // Process each top-level message in each file.
                // This can be customized if you want to traverse the input in a different way.
                CodeGenResponse.succeed(
                    for
                        file    <- request.filesToGenerate
                        message <- file.getMessageTypes().asScala
                    yield new MessagePrinter(message, implicits).result
                )
            case Left(error) =>
                CodeGenResponse.fail(error)
end CodeGenerator

class MessagePrinter(message: Descriptor, implicits: DescriptorImplicits):
    import implicits.*

    private val MessageObject =
        message.scalaType.sibling(message.scalaType.name + "FieldNums")

    def scalaFileName =
        MessageObject.fullName.replace('.', '/') + ".scala"

    def result: CodeGeneratorResponse.File =
        val b = CodeGeneratorResponse.File.newBuilder()
        b.setName(scalaFileName)
        b.setContent(content)
        b.build()
    end result

    def printObject(fp: FunctionalPrinter): FunctionalPrinter =
        fp
            .add(s"object ${MessageObject.name} {")
            .indented(
                _.print(message.getFields().asScala) { (fp, fd) => printField(fp, fd) }
                    .add("")
                    .print(message.getNestedTypes().asScala) {
                        (fp, m) => new MessagePrinter(m, implicits).printObject(fp)
                    }
            )
            .add("}")

    def printField(fp: FunctionalPrinter, fd: FieldDescriptor): FunctionalPrinter =
        fp.add(s"val ${fd.getName} = ${fd.getNumber}")

    def content: String =
        val fp = new FunctionalPrinter()
            .add(
                s"package ${message.getFile.scalaPackage.fullName}",
                ""
            ).call(printObject)
        fp.result()
    end content
end MessagePrinter
