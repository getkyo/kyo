package kyo.internal

import kyo.*
import scala.annotation.tailrec
import scala.quoted.*

/** Recognizes a call whose callbacks may run effects, and names the `Kyo` operation that runs them as computations.
  *
  * `xs.map(x => f(x).now)` cannot run its effect inside `Seq.map`, so the lowering rewrites the call to `Kyo.foreach(xs)(x => f(x))`,
  * with the callback lowered to return a computation. [[call]] recognizes such a call by its receiver's type and the method's name: a
  * collection (any `IterableOps` whose representation is its own constructor applied to its element, so `List`, `Vector`, `Chunk`,
  * `Set`, `Iterable`), `Maybe`, `Result`, `Option`, `Try`, `Either`, or `Either.LeftProjection`. The method must be the type's own, and
  * named in the table below: the standard library's callback-taking methods, each paired with the `Kyo` operation of the same meaning.
  *
  * The `Kyo` operations take their type arguments in a fixed order, the receiver's own followed by the method's own followed by the
  * effect row, so the lowering can apply them explicitly: the receiver's come from its type, the method's from the call as the compiler
  * inferred them.
  */
final private[kyo] class Counterparts[Q <: Quotes & Singleton](using val quotes: Q):
    import quotes.reflect.*

    /** A recognized call.
      *
      * @param method
      *   the standard-library method
      * @param operation
      *   the `Kyo` overload to call instead
      * @param receiver
      *   the value the method is called on, the first explicit argument of an extension method
      * @param receiverTypes
      *   the receiver's type arguments as the operation takes them: constructor and element for a collection
      * @param methodTypes
      *   the method's own type arguments
      * @param arguments
      *   the explicit argument lists, each argument with its parameter type
      */
    final case class Call(
        method: String,
        operation: Symbol,
        receiver: Term,
        receiverTypes: List[TypeRepr],
        methodTypes: List[TypeRepr],
        arguments: List[List[(Term, TypeRepr)]]
    )

    private val collections = Map(
        "map"            -> "foreach",
        "flatMap"        -> "foreachConcat",
        "flatten"        -> "foreachConcat",
        "foreach"        -> "foreachDiscard",
        "filter"         -> "filter",
        "filterNot"      -> "filterNot",
        "withFilter"     -> "filter",
        "collect"        -> "collect",
        "collectFirst"   -> "findFirst",
        "find"           -> "find",
        "exists"         -> "exists",
        "forall"         -> "forall",
        "count"          -> "count",
        "fold"           -> "foldLeft",
        "foldLeft"       -> "foldLeft",
        "foldRight"      -> "foldRight",
        "scanLeft"       -> "scanLeft",
        "scanRight"      -> "scanRight",
        "takeWhile"      -> "takeWhile",
        "dropWhile"      -> "dropWhile",
        "span"           -> "span",
        "partition"      -> "partition",
        "partitionMap"   -> "partitionMap",
        "groupBy"        -> "groupBy",
        "groupMap"       -> "groupMap",
        "groupMapReduce" -> "groupMapReduce",
        "maxByOption"    -> "maxBy",
        "corresponds"    -> "corresponds",
        "tapEach"        -> "tapEach"
    )

    private val containers = Map(
        "map"            -> "foreach",
        "flatMap"        -> "foreachConcat",
        "foreach"        -> "foreachDiscard",
        "filter"         -> "filter",
        "filterNot"      -> "filterNot",
        "filterOrElse"   -> "filterOrElse",
        "filterToOption" -> "filterToOption",
        "exists"         -> "exists",
        "forall"         -> "forall",
        "collect"        -> "collect",
        "fold"           -> "fold",
        "orElse"         -> "orElse",
        "getOrElse"      -> "getOrElse",
        "recover"        -> "recover",
        "recoverWith"    -> "recoverWith"
    )

    val kyo                      = Symbol.requiredModule("kyo.Kyo")
    private val iterableOps      = Symbol.requiredClass("scala.collection.IterableOps")
    private val collectionOwners =
        Set(iterableOps, Symbol.requiredClass("scala.collection.IterableOnceOps"), Symbol.requiredClass("scala.collection.SeqOps"))
    private val containerTypes = List(
        TypeRepr.of[Maybe[Any]],
        TypeRepr.of[Result[Any, Any]],
        TypeRepr.of[Option[Any]],
        TypeRepr.of[scala.util.Try[Any]],
        TypeRepr.of[Either[Any, Any]],
        TypeRepr.of[Either.LeftProjection[Any, Any]]
    ).map(_.typeSymbol)

    /** The recognized call, when `tree` is one. */
    def call(tree: Term): Option[Call] =
        tree match
            case _: Apply =>
                val (core, lists, parameters, typeArguments) = peel(tree, Nil, Nil, Nil)
                val symbol                                   = core.symbol
                val receiver                                 =
                    if symbol.flags.is(Flags.ExtensionMethod) then lists.headOption.flatMap(_.headOption)
                    else
                        core match
                            case Select(qualifier, _) => Some(qualifier)
                            case _                    => None
                receiver.flatMap { receiver =>
                    family(receiver.tpe).flatMap { (owner, receiverTypes) =>
                        val table = if owner.equals(iterableOps) then collections else containers
                        table.get(symbol.name).filter(_ => declared(symbol, owner)).flatMap(operation(_, owner)).map { operation =>
                            val (explicit, explicitParameters, methodTypes) =
                                if symbol.flags.is(Flags.ExtensionMethod) then
                                    val leading = symbol.paramSymss.headOption.filter(_.forall(_.isType)).fold(0)(_.length)
                                    (lists.tail, parameters.tail, typeArguments.drop(leading))
                                else (lists, parameters, typeArguments)
                            Call(
                                symbol.name,
                                operation,
                                receiver,
                                receiverTypes,
                                methodTypes,
                                explicit.zip(explicitParameters).map(_.zip(_))
                            )
                        }
                    }
                }
            case _ => None

    /** The callee, the argument lists with their parameter types, and the type arguments of an application chain. */
    @tailrec private def peel(
        term: Term,
        lists: List[List[Term]],
        parameters: List[List[TypeRepr]],
        typeArguments: List[TypeRepr]
    ): (Term, List[List[Term]], List[List[TypeRepr]], List[TypeRepr]) =
        term match
            case Apply(fun, args) =>
                val types = fun.tpe.widen match
                    case method: MethodType => method.paramTypes
                    case _                  => args.map(_ => TypeRepr.of[Any])
                peel(fun, args :: lists, types :: parameters, typeArguments)
            case TypeApply(fun, types) => peel(fun, lists, parameters, types.map(_.tpe) ::: typeArguments)
            case _                     => (term, lists, parameters, typeArguments)

    /** The receiver's family, as the symbol of `IterableOps` or of the container type, with the type arguments its operations take. */
    private def family(tpe: TypeRepr): Option[(Symbol, List[TypeRepr])] =
        val widened = tpe.widen.dealias
        widened.baseType(iterableOps) match
            case AppliedType(_, List(element, constructor, repr)) if repr =:= constructor.appliedTo(element) =>
                Some((iterableOps, List(constructor, element)))
            case _ =>
                def container(symbol: Symbol): Option[(Symbol, List[TypeRepr])] =
                    widened match
                        case AppliedType(constructor, args) if constructor.typeSymbol.equals(symbol) => Some((symbol, args))
                        case _                                                                       =>
                            widened.baseType(symbol) match
                                case AppliedType(_, args) => Some((symbol, args))
                                case _                    => None
                containerTypes.iterator.map(container).collectFirst { case Some(found) => found }
        end match
    end family

    /** True when the method is the family's own: declared or overridden from the collection traits or the container type. Extension
      * methods, those of `Maybe` and `Result`, are recognized by the receiver's type alone.
      */
    private def declared(symbol: Symbol, owner: Symbol): Boolean =
        val owners = if owner.equals(iterableOps) then collectionOwners else Set(owner)
        symbol.flags.is(Flags.ExtensionMethod) || (symbol :: symbol.allOverriddenSymbols.toList).exists(s => owners.contains(s.owner))

    /** The `Kyo` overload named `name` for the family: the one whose source parameter has the family's type, or a type parameter for
      * the collections.
      */
    private def operation(name: String, owner: Symbol): Option[Symbol] =
        kyo.methodMember(name).find { candidate =>
            val source = Ref(kyo).select(candidate).tpe.widen match
                case poly: PolyType =>
                    poly.resType match
                        case method: MethodType => method.paramTypes.headOption
                        case _                  => None
                case _ => None
            source.exists {
                case AppliedType(constructor, _) =>
                    constructor match
                        case ParamRef(_, _) => owner.equals(iterableOps)
                        case _              => constructor.typeSymbol.equals(owner)
                case _ => false
            }
        }
end Counterparts
