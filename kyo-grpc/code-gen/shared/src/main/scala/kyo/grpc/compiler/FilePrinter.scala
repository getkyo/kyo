package kyo.grpc.compiler

import com.google.protobuf.Descriptors.*
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, NameUtils}

import scala.util.chaining.scalaUtilChainingOps

case class FilePrinter(file: FileDescriptor, implicits: DescriptorImplicits, fp: FunctionalPrinter = new FunctionalPrinter(), builder: File.Builder = File.newBuilder()) {

    import implicits.*

    def addPackage: FilePrinter =
        copy(fp = fp.addPackage(file.scalaPackage.fullName).newline)

    def addService(service: ServiceDescriptor): FilePrinter =
        copy(fp = ServicePrinter(service, implicits, fp).addTrait.addObject.fp)

    def setNameFromService(service: ServiceDescriptor): FilePrinter = {
        val dir = file.scalaPackage.fullName.replace(".", "/")
        val name = NameUtils.snakeCaseToCamelCase(service.getName, upperInitial = true)
        copy(builder = builder.setName(s"$dir/$name.scala"))
    }

    def result: File =
        builder
            .pipe { b => if (b.getName.isEmpty) b.setName(file.scalaFileName) else b }
            .setContent(fp.result())
            .build()
}
