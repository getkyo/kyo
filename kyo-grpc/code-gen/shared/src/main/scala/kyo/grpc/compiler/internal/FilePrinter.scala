package kyo.grpc.compiler.internal

import com.google.protobuf.Descriptors._
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.NameUtils

private[compiler] case class FilePrinter(
    file: FileDescriptor,
    implicits: DescriptorImplicits,
    fp: FunctionalPrinter = new FunctionalPrinter(),
    builder: File.Builder = File.newBuilder()
) {

    import implicits._

    def addPackage: FilePrinter =
        copy(fp = fp.addPackage(file.scalaPackage.fullName).newline)

    def addService(service: ServiceDescriptor): FilePrinter =
        copy(fp = ServicePrinter(service, implicits, fp).addTrait.addObject.fp)

    def setNameFromService(service: ServiceDescriptor): FilePrinter = {
        val dir  = file.scalaPackage.fullName.replace(".", "/")
        val name = NameUtils.snakeCaseToCamelCase(service.getName, upperInitial = true)
        copy(builder = builder.setName(s"$dir/$name.scala"))
    }

    def result: File = {
        val namedBuilder = if (builder.getName.isEmpty) builder.setName(file.scalaFileName) else builder
        namedBuilder.setContent(fp.result()).build()
    }
}
