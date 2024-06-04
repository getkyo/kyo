package kyo.grpc

import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

import scala.annotation.tailrec

package object compiler {

    implicit class ScalaFunctionalPrinterOps(val fp: FunctionalPrinter) extends AnyVal {

        def addPackage(name: String): FunctionalPrinter =
            fp.add(s"package $name")

        def addObject(name: String)(body: PrinterEndo): FunctionalPrinter =
            addBlock(s"object $name ")(body)

        def addTrait(name: String)(body: PrinterEndo): FunctionalPrinter =
            addBlock(s"trait $name ")(body)

        def addMethod(name: String, parameters: Seq[(String, String)], returnType: String)(body: PrinterEndo): FunctionalPrinter = {
            fp.add(s"def $name(")
                .indented(_.addParameters(parameters))
            addBlock(s"): $returnType = ")(body)
        }

        def addAbstractMethod(name: String, parameters: Seq[(String, String)], returnType: String): FunctionalPrinter =
            fp.add(s"def $name(")
                .indented(_.addParameters(parameters))
                .add(s"): $returnType")

        def addParameters(parameters: Seq[(String, String)]): FunctionalPrinter = {
            @tailrec
            def loop(parameters: Seq[(String, String)]): FunctionalPrinter =
                parameters match {
                    case (name, tpe) +: Seq() =>
                        fp.add(s"$name: $tpe")
                    case (name, tpe) +: tail =>
                        fp.add(s"$name: $tpe,")
                        loop(tail)
                }

            loop(parameters)
        }

        def addBlock(prefix: String)(body: PrinterEndo): FunctionalPrinter =
            fp.add(s"$prefix{")
                .indented(body)
                .add("}")
    }
}
