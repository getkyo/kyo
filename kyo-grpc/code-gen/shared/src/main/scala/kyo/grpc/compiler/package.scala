package kyo.grpc

import kyo.grpc.compiler.builders.*
import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

import scala.language.implicitConversions

package object compiler {

    private[compiler] val WIDTH  = 100
    private[compiler] val INDENT = 2

    def mods(chooses: Mod.Choose*): Vector[String] =
        chooses.map(_.choice).toVector

    final case class AddClassDsl(builder: ClassBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddClassDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddClassDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addParents(params: String*): AddClassDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddClassDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddClassDsl =
            copy(builder = builder.setBody(body))

        def endClass: FunctionalPrinter = fp.addDoc(builder.result)
    }

    object AddClassDsl {
        implicit def endClass(dsl: AddClassDsl): FunctionalPrinter = dsl.endClass
    }

    final case class AddObjectDsl(builder: ObjectBuilder, fp: FunctionalPrinter) {

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

    object AddObjectDsl {
        implicit def endObject(dsl: AddObjectDsl): FunctionalPrinter = dsl.endObject
    }

    final case class AddTraitDsl(builder: TraitBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddTraitDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddTraitDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

        def addParents(params: String*): AddTraitDsl =
            copy(builder = builder.appendParents(params))

        def addBody(body: PrinterEndo): AddTraitDsl =
            copy(builder = builder.setBody(body))

        def addBodyDoc(body: Doc): AddTraitDsl =
            copy(builder = builder.setBody(body))

        def endTrait: FunctionalPrinter = fp.addDoc(builder.result)
    }

    object AddTraitDsl {
        implicit def endTrait(dsl: AddTraitDsl): FunctionalPrinter = dsl.endTrait
    }

    final case class AddMethodDsl(builder: MethodBuilder, fp: FunctionalPrinter) {

        def addAnnotations(annotations: String*): AddMethodDsl =
            copy(builder = builder.appendAnnotations(annotations))

        def addMods(mods: Mod.Choose*): AddMethodDsl =
            copy(builder = builder.appendMods(mods.map(_.choice)))

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

        def addBodyDoc(body: Doc): AddMethodDsl =
            copy(builder = builder.setBody(body))

        def endMethod: FunctionalPrinter = fp.addDoc(builder.result)
    }

    object AddMethodDsl {
        implicit def endMethod(dsl: AddMethodDsl): FunctionalPrinter = dsl.endMethod
    }

    implicit class ScalaFunctionalPrinterOps(val fp: FunctionalPrinter) extends AnyVal {

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
}
