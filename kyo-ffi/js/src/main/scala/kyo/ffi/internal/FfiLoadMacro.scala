package kyo.ffi.internal

import kyo.ConcreteTag
import kyo.ffi.Ffi
import scala.quoted.*
import scala.util.control.NonFatal

/** Expands `Ffi.load[T]` on Scala.js into a construction of the generated `<T>Impl` that the linker can see.
  *
  * A reflective lookup needs the impl registered for reflective instantiation, and the Scala.js linker keeps every class so registered
  * whether or not anything loads it. Every binding in a program would then sit in the module that starts it, together with koffi and the
  * native loader they reach, so a page served the same bundle would fetch all of it. Naming the impl at the call site lets reachability
  * decide instead: a binding is linked where something loads it, and a module that never loads it never carries it.
  *
  * The impl is the class a reflective lookup would find: the sibling of the binding trait named `<T>Impl`, a class extending `T` with a
  * nullary constructor. When the call site cannot see one, which is the case when the generator did not run for the trait, the expansion
  * raises the `ImplNotFound` the reflective lookup raised.
  */
private[ffi] object FfiLoadMacro:

    def load[T <: Ffi: Type](ct: Expr[ConcreteTag[T]])(using Quotes): Expr[T] =
        construction[T] match
            case Some(create) =>
                '{
                    val binding = $ct.toClass
                    val cached  = FfiLoadCore.cache.get(binding)
                    (if cached != null then cached else FfiLoad.construct(binding, () => $create)).asInstanceOf[T]
                }
            case None =>
                '{ FfiLoad.missing($ct.toClass) }

    /** The construction of `T`'s generated impl, or `None` when this compilation has no such class. */
    private def construction[T <: Ffi: Type](using Quotes): Option[Expr[AnyRef]] =
        import quotes.reflect.*
        val binding = TypeRepr.of[T].dealias.typeSymbol
        // A name the symbol table cannot resolve comes back as `NoSymbol`, as a raise, or as a stub with no constructor, depending on the
        // owner; all three read as absent here.
        val impl =
            try binding.owner.typeMember(binding.name + "Impl")
            catch case ex: Throwable if NonFatal(ex) => Symbol.noSymbol
        val constructor = if impl.exists && impl.isClassDef then impl.primaryConstructor else Symbol.noSymbol
        if constructor.exists && constructor.paramSymss.flatten.isEmpty && impl.typeRef <:< TypeRepr.of[T] then
            Some(Apply(Select(New(TypeTree.ref(impl)), constructor), Nil).asExprOf[AnyRef])
        else None
        end if
    end construction

end FfiLoadMacro
