package kyo.grpc

import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

import scala.language.implicitConversions

package object compiler {

    private[compiler] val WIDTH  = 100
    private[compiler] val INDENT = 2

    def mods(chooses: Mod.Choose*): Seq[String] =
        chooses.map(_.choice)

    final case class AddMethodDsl(builder: MethodBuilder, fp: FunctionalPrinter) {

        def addTypeParameters(params: String*): AddMethodDsl =
            copy(builder = builder.appendTypeParameters(params))

        def addParameterList(params: (String, String)*): AddMethodDsl =
            copy(builder = builder.appendParameterList(params))

        def addImplicitParameters(params: (String, String)*): AddMethodDsl =
            copy(builder = builder.appendImplicitParameters(params))

        def addReturnType(returnType: String): AddMethodDsl =
            copy(builder = builder.setReturnType(returnType))

        def addBody(body: PrinterEndo): AddMethodDsl =
            copy(builder = builder.setBody(body))

        def print: FunctionalPrinter = builder.print(fp)
    }

    object AddMethodDsl {
        implicit def print(dsl: AddMethodDsl): FunctionalPrinter = dsl.print
    }

    final case class AddObjectDsl(builder: ObjectBuilder, fp: FunctionalPrinter) {

        def addParents(params: String*): AddObjectDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddObjectDsl =
            copy(builder = builder.setBody(body))

        def print: FunctionalPrinter = builder.print(fp)
    }

    object AddObjectDsl {
        implicit def print(dsl: AddObjectDsl): FunctionalPrinter = dsl.print
    }

    implicit class ScalaFunctionalPrinterOps(val fp: FunctionalPrinter) extends AnyVal {

        def addPackage(name: String): FunctionalPrinter =
            fp.add(s"package $name")

        def addMethod(
                         name: String
                     ): AddMethodDsl =
            addMethod(Seq.empty, name)

        def addMethod(
                         mods: Seq[String],
                         name: String
                     ): AddMethodDsl =
            AddMethodDsl(MethodBuilder(mods, name), fp)

        def addObject(
            name: String
        ): AddObjectDsl =
            addObject(Seq.empty, name)

        def addObject(
            mods: Seq[String],
            name: String
        ): AddObjectDsl =
            AddObjectDsl(ObjectBuilder(mods, name), fp)

        // TODO: Replace

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

        def addDoc(doc: Doc): FunctionalPrinter =
            fp.add(doc.render(WIDTH))
    }
}
