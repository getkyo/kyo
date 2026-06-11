package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

/** Maps callback signatures to the shape ids used by [[kyo.ffi.internal.CallbackRegistry]] on Scala Native.
  *
  * Scala Native 0.5 cannot pass an arbitrary Scala closure as a `CFuncPtr`, `CFuncPtr.fromScalaFunction` accepts only a non-capturing
  * top-level `def` (via eta-expansion), a top-level `val` holding a non-capturing lambda, or a literal lambda that references only its own
  * parameters. `kyo-ffi` side-steps that restriction with a runtime registry: the user callback is stashed in [[CallbackRegistry]] and an
  * opaque top-level trampoline `def` reads it back from the registry when C invokes it.
  *
  * This object mirrors the canonical shape catalog declared at build time in `project/CallbackShapesGen.scala` (the single source of truth
  * that emits `CallbackRegistryShapes.scala` + `RetainedTrampolines.scala`). The runtime publishes trampoline methods named
  * `trampolineT_<SHAPE>` and `claimRetainedSlot_<SHAPE>` / `releaseRetainedSlot_<SHAPE>`; this object maps a [[TypeRef]] pattern to the
  * matching `<SHAPE>` id or fails codegen with a pointer to where to extend.
  *
  * Adding a new shape = append a [[Shape]] entry to [[catalog]] below AND append the same entry to `project/CallbackShapesGen.scala`'s
  * `SHAPES` list. No other edits are required, the runtime per-shape methods + trampolines regenerate automatically.
  */
private[codegen] object NativeCallbackCatalog:

    /** A catalog entry: parameter `TypeRef`s + return `TypeRef` mapped to the canonical shape id (`II_I`, `V_U`, etc.) used as the suffix
      * on `trampolineT_`, `claimRetainedSlot_`, and `releaseRetainedSlot_` in [[kyo.ffi.internal.CallbackRegistry]].
      *
      * Must stay in lockstep with `project/CallbackShapesGen.scala`'s `SHAPES` list. The `id` is what the emitter appends when generating
      * `CallbackRegistry.claimRetainedSlot_${id}`, so mismatching id = linker error at Scala Native link time.
      */
    case class Shape(id: String, params: List[TypeRef], ret: TypeRef)

    /** Canonical shape list. Mirror of `project/CallbackShapesGen.scala`'s `SHAPES`. */
    val catalog: List[Shape] =
        import TypeRef.*
        List(
            Shape("V_U", Nil, UnitT),
            Shape("I_U", IntT :: Nil, UnitT),
            Shape("I_I", IntT :: Nil, IntT),
            Shape("II_I", IntT :: IntT :: Nil, IntT),
            Shape("JJ_I", LongT :: LongT :: Nil, IntT),
            Shape("P_U", BufferT(UnitT) :: Nil, UnitT),
            Shape("PI_U", BufferT(UnitT) :: IntT :: Nil, UnitT),
            Shape("J_J", LongT :: Nil, LongT),
            Shape("J_U", LongT :: Nil, UnitT),
            Shape("D_D", DoubleT :: Nil, DoubleT),
            Shape("II_U", IntT :: IntT :: Nil, UnitT),
            Shape("JJ_J", LongT :: LongT :: Nil, LongT)
        )
    end catalog

    /** Resolve the shape id for a callback signature, or `None` if unsupported.
      *
      * `BufferT(_)` on the codegen side renders as `Ptr[Byte]` on the C side; the element type is irrelevant for the shape match, so a
      * `BufferT(anyElem)` at any position in the catalog matches any concrete `BufferT`. Other `TypeRef` cases match structurally.
      */
    def shapeId(params: List[TypeRef], ret: TypeRef): Option[String] =
        catalog.find(entry => matchesParams(entry.params, params) && matchesType(entry.ret, ret)).map(_.id)

    private def matchesParams(catalogParams: List[TypeRef], actual: List[TypeRef]): Boolean =
        catalogParams.length == actual.length &&
            catalogParams.zip(actual).forall { case (c, a) => matchesType(c, a) }

    private def matchesType(catalog: TypeRef, actual: TypeRef): Boolean =
        (catalog, actual) match
            case (TypeRef.BufferT(_), TypeRef.BufferT(_)) => true
            case (c, a)                                   => c == a

    /** Require a shape id or fail codegen with a diagnostic that names the offending method + signature and points at the files to extend.
      */
    def requireShapeId(method: MethodSpec, params: List[TypeRef], ret: TypeRef, cbTypeRendered: String): String =
        shapeId(params, ret).getOrElse {
            throw new IllegalStateException(
                s"Unsupported callback shape $cbTypeRendered in method ${method.scalaName}; extend " +
                    "project/CallbackShapesGen.scala (SHAPES) and kyo.ffi.codegen.emitters.NativeCallbackCatalog (catalog)."
            )
        }
    end requireShapeId

    /** Name of the trampoline `def` in `CallbackRegistry` for the transient branch of a given shape. */
    def transientTrampolineName(shape: String): String =
        s"trampolineT_$shape"

    /** Name of the claim method in `CallbackRegistry` for the retained branch of a given shape. */
    def claimRetainedName(shape: String): String =
        s"claimRetainedSlot_$shape"

    /** Name of the release method in `CallbackRegistry` for the retained branch of a given shape. */
    def releaseRetainedName(shape: String): String =
        s"releaseRetainedSlot_$shape"

end NativeCallbackCatalog
