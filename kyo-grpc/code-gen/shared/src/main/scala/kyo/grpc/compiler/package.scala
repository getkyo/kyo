package kyo.grpc

import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

import scala.annotation.tailrec

package object compiler {

    implicit class ScalaFunctionalPrinterOps(val fp: FunctionalPrinter) extends AnyVal {

        def addPackage(name: String): FunctionalPrinter =
            fp.add(s"package $name")

        def addObject(name: String, parents: Seq[String] = Seq.empty)(body: PrinterEndo): FunctionalPrinter =
            fp.add(s"object $name")
                .addExtends(parents)
                .appendBlock(body)

        def addTrait(name: String, parents: Seq[String] = Seq.empty)(body: PrinterEndo): FunctionalPrinter =
            fp.add(s"trait $name")
                .addExtends(parents)
                .appendBlock(body)

        def addExtends(parents: Seq[String]): FunctionalPrinter =
            parents.headOption match {
                case Some(head) =>
                    fp.indented {
                      _.add(s"extends $head")
                          .indented {
                              parents.tail.foldLeft(_) {
                                  (fp, parent) => fp.add(s"with $parent")
                              }
                          }
                    }
                case None => fp
            }

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

        def appendBlock(body: PrinterEndo): FunctionalPrinter = {
            append(" {")
                .indented(body)
                .add("}")
        }

        def append(s: String): FunctionalPrinter = {
            val lastIndex = fp.content.size - 1
            if (lastIndex > 0) fp.copy(content = fp.content.updated(lastIndex, fp.content(lastIndex) + s))
            else fp.add(s)
        }
    }
}
