package kyo.grpc.compiler

import org.typelevel.paiges.Doc
import org.typelevel.paiges.internal.Docx
import org.typelevel.paiges.internal.ExtendedSyntax.*
import scala.language.implicitConversions
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

package object internal {

    private[compiler] val WIDTH  = 100
    private[compiler] val INDENT = 2

    private[compiler] def mods(chooses: Mod.Choose*): Vector[String] =
        chooses.map(_.choice).toVector

    final private[compiler] case class AddClassDsl(builder: ClassBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddClassDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddClassDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addTypeParameters(params: String*): AddClassDsl =
            copy(builder = builder.appendTypeParameters(params))

        def addParameterList(params: Parameter*): AddClassDsl =
            copy(builder = builder.appendParameterList(params))

        def addImplicitParameters(params: Parameter*): AddClassDsl =
            copy(builder = builder.appendImplicitParameters(params))

        def addParents(params: String*): AddClassDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddClassDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddClassDsl =
            copy(builder = builder.setBody(body))

        def endClass: FunctionalPrinter = fp.addDoc(builder.result)
    }

    private[compiler] object AddClassDsl {
        implicit def endClass(dsl: AddClassDsl): FunctionalPrinter = dsl.endClass
    }

    final private[compiler] case class AddObjectDsl(builder: ObjectBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddObjectDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddObjectDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addParents(params: String*): AddObjectDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddObjectDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddObjectDsl =
            copy(builder = builder.setBody(body))

        def endObject: FunctionalPrinter = fp.addDoc(builder.result)
    }

    private[compiler] object AddObjectDsl {
        implicit def endObject(dsl: AddObjectDsl): FunctionalPrinter = dsl.endObject
    }

    final private[compiler] case class AddTraitDsl(builder: TraitBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddTraitDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddTraitDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addTypeParameters(params: String*): AddTraitDsl =
            copy(builder = builder.appendTypeParameters(params))

        def addParents(params: String*): AddTraitDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddTraitDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddTraitDsl =
            copy(builder = builder.setBody(body))

        def endTrait: FunctionalPrinter = fp.addDoc(builder.result)
    }

    private[compiler] object AddTraitDsl {
        implicit def endTrait(dsl: AddTraitDsl): FunctionalPrinter = dsl.endTrait
    }

    final private[compiler] case class AddMethodDsl(builder: MethodBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddMethodDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddMethodDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addTypeParameters(params: String*): AddMethodDsl =
            copy(builder = builder.appendTypeParameters(params))

        def addParameterList(params: Parameter*): AddMethodDsl =
            copy(builder = builder.appendParameterList(params))

        def addImplicitParameters(params: Parameter*): AddMethodDsl =
            copy(builder = builder.appendImplicitParameters(params))

        def addUsingParameters(params: Parameter*): AddMethodDsl =
            copy(builder = builder.appendUsingParameters(params))

        def addReturnType(returnType: String): AddMethodDsl =
            copy(builder = builder.setReturnType(returnType))

        def addBody(body: PrinterEndo): AddMethodDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddMethodDsl =
            copy(builder = builder.setBody(body))

        def endMethod: FunctionalPrinter = fp.addDoc(builder.result)
    }

    private[compiler] object AddMethodDsl {
        implicit def endMethod(dsl: AddMethodDsl): FunctionalPrinter = dsl.endMethod
    }

    implicit private[compiler] class ScalaFunctionalPrinterOps(val fp: FunctionalPrinter) extends AnyVal {

        def addPackage(id: String): FunctionalPrinter =
            fp.add(s"package $id")

        def addClass(id: String): AddClassDsl =
            AddClassDsl(ClassBuilder(id), fp)

        def addObject(id: String): AddObjectDsl =
            AddObjectDsl(ObjectBuilder(id), fp)

        def addTrait(id: String): AddTraitDsl =
            AddTraitDsl(TraitBuilder(id), fp)

        def addMethod(id: String): AddMethodDsl =
            AddMethodDsl(MethodBuilder(id), fp)

        def append(s: String): FunctionalPrinter = {
            val lastIndex = fp.content.size - 1
            if (lastIndex > 0) fp.copy(content = fp.content.updated(lastIndex, fp.content(lastIndex) + s))
            else fp.add(s)
        }

        def addDoc(doc: Doc): FunctionalPrinter =
            fp.add(doc.render(WIDTH))
    }

    implicit private[compiler] class StringParameterOps(val parameterName: String) extends AnyVal {

        def :-(typeName: String): Parameter = Parameter(parameterName, typeName, None)
    }

    private[compiler] def when(condition: Boolean)(doc: => Doc): Doc =
        if (condition) doc else Doc.empty

    private[compiler] def hardList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.hardLine, docs)

    private[compiler] def stackList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.char(',') + Doc.line, docs)

    private[compiler] def spreadList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.text(", "), docs)

    private[compiler] def extendsList(docs: Iterable[Doc]): Doc =
        when(docs.nonEmpty) {
            (Doc.text("extends ") + Doc.intercalate(Doc.line + Doc.text("with "), docs)).hangingUnsafe(INDENT * 2)
        }

    private[compiler] def typedName(name: Option[String], tpe: String): Doc =
        name match {
            case None    => Doc.text(tpe)
            case Some(n) => n +: (Doc.text(": ") + Doc.text(tpe))
        }

    private[compiler] def parameter(parameter: Parameter): Doc =
        typedName(parameter.name, parameter.typeName) +
            parameter.default.fold(Doc.empty)(default => Doc.text(" = ") + Doc.text(default))

    private[compiler] def parameterLists(parameterss: Vector[Seq[Parameter]]): Doc =
        when(parameterss.nonEmpty) {
            val parametersDocs = parameterss
                .map(_.map(parameter))
                .map(stackList)
                .map(_.tightBracketBy(Doc.char('('), Doc.char(')')))
            Doc.cat(parametersDocs)
        }

    private[compiler] def printToDoc(f: PrinterEndo): Doc =
        Docx.literal(f(new FunctionalPrinter()).result())
}
