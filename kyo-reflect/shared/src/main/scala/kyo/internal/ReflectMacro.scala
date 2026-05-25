package kyo.internal

import kyo.*
import kyo.Reflect.*
import scala.quoted.*

/** Macro entry point for Reflect.Reads.derived.
  *
  * Supports case class (product) derivation via quotes.reflect.TypeRepr inspection. Sum types and higher-kinded types are rejected at
  * compile time with a clear error message.
  *
  * Each field is read by a pre-built lambda: `Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])`. The lambdas are sequenced at
  * runtime by `ReflectRuntime.readFields`. A separate `Array[Any] => A` constructor bridge (with isolated asInstanceOf casts) builds the
  * product.
  *
  * For recursive case classes (`Node(name, children: Chunk[Node])`), a `lazy val instance` is emitted. The recursive readers receive the
  * `instance` reference at runtime via `ReflectRuntime.readFieldsLazy`, which takes `instance: Reads[A]` directly as a parameter. The
  * reference is captured inside the outer `'{...}` quote where `instance` is in scope.
  */
object ReflectMacro:

    def derivedImpl[A: scala.quoted.Type](using q: Quotes): Expr[Reflect.Reads[A]] =
        import quotes.reflect.*

        val aType = TypeRepr.of[A]
        val aSym  = aType.typeSymbol

        if aSym.isNoSymbol || !aSym.isClassDef then
            report.errorAndAbort(
                s"Reflect.Reads.derived requires a named class symbol; got: ${aType.show}"
            )
        end if

        if aSym.flags.is(Flags.Sealed) || aSym.flags.is(Flags.Enum) then
            report.errorAndAbort(
                s"""|Reflect.Reads.derived does not support sum types (sealed traits, enums) in v1.
                    |Write a hand-written Reads instance instead. Template:
                    |
                    |  given Reflect.Reads[${aSym.name}] = new Reflect.Reads[${aSym.name}]:
                    |      val symbolKinds   = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Method)
                    |      val needsBodies   = false
                    |      val touchedFields = FieldSet.Kind
                    |      def read(sym: Symbol): ${aSym.name} < (Sync & Async & Abort[ReflectError]) =
                    |          Kyo.pure(sym.kind match
                    |              case SymbolKind.Class  => /* your class case */
                    |              case SymbolKind.Trait  => /* your trait case */
                    |              case SymbolKind.Method => /* your method case */
                    |              case other             => return Abort.fail(ReflectError.SymbolNotFound(s"unexpected kind: $$other"))
                    |          )
                    |
                    |See kyo-reflect DESIGN.md section 13 "Worked example: hand-written ADT Reads" for full details.""".stripMargin
            )
        end if

        val abstractTypeParams = aSym.typeMembers.filter(p => p.isTypeParam)
        if abstractTypeParams.nonEmpty then
            report.errorAndAbort(
                s"""|Reflect.Reads.derived requires a fully monomorphic type at derivation site.
                    |Abstract type parameter found in: ${aType.show}
                    |To handle a polymorphic type, build an explicit factory:
                    |  def fooReads[X](using Reads[X]): Reads[Foo[X]] = new Reads[Foo[X]]: ...""".stripMargin
            )
        end if

        if !aSym.flags.is(Flags.Case) then
            report.errorAndAbort(
                s"Reflect.Reads.derived requires a case class; got: ${aType.show}"
            )
        end if

        buildProduct[A](aType, aSym)
    end derivedImpl

    // ── Analysis ──────────────────────────────────────────────────────────────

    sealed private trait FieldKind derives CanEqual
    private case object DirectField    extends FieldKind // field-name accessor dispatch
    private case object SummonField    extends FieldKind // summoned Reads
    private case object SelfField      extends FieldKind // Self (recursive)
    private case object ChunkSelfField extends FieldKind // Chunk[Self] (recursive)

    private def analyzeField(
        using q: Quotes
    )(
        field: quotes.reflect.Symbol,
        fieldType: quotes.reflect.TypeRepr,
        selfFullName: String
    ): (FieldKind, Expr[Reflect.FieldSet]) =
        import quotes.reflect.*
        val ftSym  = fieldType.typeSymbol
        val isSelf = !ftSym.isNoSymbol && ftSym.fullName == selfFullName
        val isChunkOfSelf = fieldType match
            case AppliedType(_, List(inner)) =>
                !inner.typeSymbol.isNoSymbol && inner.typeSymbol.fullName == selfFullName
            case _ => false

        def staticExpr(fs: Reflect.FieldSet): Expr[Reflect.FieldSet] =
            '{ new Reflect.FieldSet(${ Expr(fs.bits) }) }

        if isSelf then
            (SelfField, staticExpr(Reflect.FieldSet.Members))
        else if isChunkOfSelf then
            (ChunkSelfField, staticExpr(Reflect.FieldSet.Members))
        else
            directFieldTouched(field.name) match
                case Some(fs) => (DirectField, staticExpr(fs))
                case None =>
                    fieldType.asType match
                        case '[ft] =>
                            Expr.summon[Reflect.Reads[ft]] match
                                case Some(r) =>
                                    // Use the summoned instance's touchedFields directly so
                                    // transitive touchedFields from nested derived Reads propagate.
                                    (SummonField, '{ $r.touchedFields })
                                case None =>
                                    report.errorAndAbort(
                                        s"No Reflect.Reads[${fieldType.show}] available for field '${field.name}'. " +
                                            s"Provide a given Reflect.Reads[${fieldType.show}] in scope."
                                    )
        end if
    end analyzeField

    // ── Product derivation ────────────────────────────────────────────────────

    private def buildProduct[A: scala.quoted.Type](
        using q: Quotes
    )(
        aType: quotes.reflect.TypeRepr,
        aSym: quotes.reflect.Symbol
    ): Expr[Reflect.Reads[A]] =
        import quotes.reflect.*
        val selfFullName = aSym.fullName
        val caseFields   = aSym.caseFields

        if caseFields.length > 64 then
            report.errorAndAbort(s"Reflect.Reads.derived supports up to 64 fields; got ${caseFields.length}")

        val fieldAnalyses = caseFields.map { f =>
            analyzeField(f, aType.memberType(f), selfFullName)
        }

        val hasRecursion = fieldAnalyses.exists(fa => fa._1 == SelfField || fa._1 == ChunkSelfField)

        // Build the touchedFields expression by unioning all per-field FieldSet expressions.
        // Using Expr[FieldSet] (not a compile-time Long) preserves transitive touchedFields from
        // nested SummonField instances (e.g. Outer contains Inner whose Reads touches Parents).
        val touchedExpr: Expr[Reflect.FieldSet] = fieldAnalyses match
            case Nil => '{ Reflect.FieldSet.Empty }
            case (_, first) :: rest =>
                rest.foldLeft(first) { case (acc, (_, fsExpr)) =>
                    '{ $acc | $fsExpr }
                }

        // Determine symbolKinds conservatively.
        // Direct field names drive the static structural check.
        // For SummonField entries, we can't know at compile time whether the nested Reads
        // touches structural fields, so we conservatively include structural kinds when any
        // SummonField is present.
        val staticFieldsTouched = caseFields.foldLeft(Reflect.FieldSet.Empty) { (acc, f) =>
            directFieldTouched(f.name).fold(acc)(acc | _)
        }
        val structuralBits = Reflect.FieldSet.Parents | Reflect.FieldSet.Members | Reflect.FieldSet.TypeParams
        val hasSummonField = fieldAnalyses.exists(_._1 == SummonField)
        val usesStructural = (staticFieldsTouched.bits & structuralBits.bits) != 0L

        val symbolKindsExpr: Expr[Set[Reflect.SymbolKind]] =
            if usesStructural || hasSummonField then
                '{ Set(Reflect.SymbolKind.Class, Reflect.SymbolKind.Trait, Reflect.SymbolKind.Object) }
            else
                '{ Set(Reflect.SymbolKind.values*) }

        val ctorExpr = buildCtorFn[A](aType, aSym, caseFields)

        if hasRecursion then
            emitLazyProduct[A](aType, aSym, caseFields, fieldAnalyses, symbolKindsExpr, touchedExpr, ctorExpr)
        else
            emitSimpleProduct[A](aType, caseFields, symbolKindsExpr, touchedExpr, ctorExpr)
        end if
    end buildProduct

    // ── Non-recursive product ─────────────────────────────────────────────────

    private def emitSimpleProduct[A: scala.quoted.Type](
        using q: Quotes
    )(
        aType: quotes.reflect.TypeRepr,
        caseFields: List[quotes.reflect.Symbol],
        symbolKindsExpr: Expr[Set[Reflect.SymbolKind]],
        touchedExpr: Expr[Reflect.FieldSet],
        ctorExpr: Expr[Array[Any] => A]
    ): Expr[Reflect.Reads[A]] =
        val readersExpr = buildReadersExpr(aType, caseFields)
        '{
            new Reflect.Reads[A]:
                val symbolKinds                                                                           = $symbolKindsExpr
                val needsBodies                                                                           = false
                val touchedFields                                                                         = $touchedExpr
                private val _readers: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])] = $readersExpr
                private val _ctor: Array[Any] => A                                                        = $ctorExpr
                def read(sym: Reflect.Symbol)(using Frame): A < (Sync & Async & Abort[ReflectError]) =
                    kyo.internal.reflect.reads.ReflectRuntime.readFields(sym, _readers, _ctor)
        }
    end emitSimpleProduct

    // ── Recursive (lazy val) product ──────────────────────────────────────────

    private def emitLazyProduct[A: scala.quoted.Type](
        using q: Quotes
    )(
        aType: quotes.reflect.TypeRepr,
        aSym: quotes.reflect.Symbol,
        caseFields: List[quotes.reflect.Symbol],
        fieldAnalyses: List[(FieldKind, Expr[Reflect.FieldSet])],
        symbolKindsExpr: Expr[Set[Reflect.SymbolKind]],
        touchedExpr: Expr[Reflect.FieldSet],
        ctorExpr: Expr[Array[Any] => A]
    ): Expr[Reflect.Reads[A]] =
        // Build only the NON-recursive readers outside the quote (they don't need `instance`).
        // Recursive positions are encoded as bitmasks; runtime `readFieldsLazy` replaces them
        // with calls to the `instance` that is passed as a parameter.
        val n = caseFields.length

        // Slots 0..n-1: for non-recursive, real reader expr; for recursive, dummy null reader
        val nonRecReaders: List[Expr[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])]] =
            caseFields.zip(fieldAnalyses).map { case (field, (kind, _)) =>
                kind match
                    case SelfField | ChunkSelfField =>
                        // Placeholder; runtime replaces with instance-based reader
                        '{ (_: Reflect.Symbol) => Kyo.lift(null: Any) }
                    case _ =>
                        buildSingleFieldReader(field.name, aType.memberType(field))
            }

        // Bitmask: bit idx is set if field[idx] is SelfField
        val selfMask: Long = fieldAnalyses.zipWithIndex.foldLeft(0L) {
            case (acc, ((SelfField, _), idx)) => acc | (1L << idx)
            case (acc, _)                     => acc
        }
        // Bitmask: bit idx is set if field[idx] is ChunkSelfField
        val chunkSelfMask: Long = fieldAnalyses.zipWithIndex.foldLeft(0L) {
            case (acc, ((ChunkSelfField, _), idx)) => acc | (1L << idx)
            case (acc, _)                          => acc
        }

        val nonRecReadersExpr: Expr[Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])]] =
            '{ Chunk.from(${ Expr.ofSeq(nonRecReaders) }) }

        val selfMaskExpr      = Expr(selfMask)
        val chunkSelfMaskExpr = Expr(chunkSelfMask)

        // Inside the lazy quote, `instance` IS in scope for `readFieldsLazy`
        '{
            lazy val instance: Reflect.Reads[A] = new Reflect.Reads[A]:
                val symbolKinds                                                                                 = $symbolKindsExpr
                val needsBodies                                                                                 = false
                val touchedFields                                                                               = $touchedExpr
                private val _ctor: Array[Any] => A                                                              = $ctorExpr
                private val _nonRecReaders: Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])] = $nonRecReadersExpr
                private val _isRecSlot: Long                                                                    = $selfMaskExpr
                private val _isChunkSelf: Long                                                                  = $chunkSelfMaskExpr
                def read(sym: Reflect.Symbol)(using Frame): A < (Sync & Async & Abort[ReflectError]) =
                    kyo.internal.reflect.reads.ReflectRuntime.readFieldsLazy(sym, _nonRecReaders, _isRecSlot, _isChunkSelf, instance, _ctor)
            instance
        }
    end emitLazyProduct

    // ── Reader construction ───────────────────────────────────────────────────

    private def buildReadersExpr(
        using q: Quotes
    )(
        aType: quotes.reflect.TypeRepr,
        caseFields: List[quotes.reflect.Symbol]
    ): Expr[Chunk[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])]] =
        val elems = caseFields.map(f => buildSingleFieldReader(f.name, aType.memberType(f)))
        '{ Chunk.from(${ Expr.ofSeq(elems) }) }
    end buildReadersExpr

    private def buildSingleFieldReader(
        using q: Quotes
    )(
        fieldName: String,
        fieldType: quotes.reflect.TypeRepr
    ): Expr[Reflect.Symbol => Any < (Sync & Async & Abort[ReflectError])] =
        import quotes.reflect.*
        fieldName match
            case "name"            => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.name) }
            case "fullName"        => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.fullName) }
            case "binaryName"      => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.binaryName) }
            case "flags"           => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.flags) }
            case "kind"            => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.kind) }
            case "owner"           => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.owner) }
            case "isInline"        => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isInline) }
            case "isContextual"    => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isContextual) }
            case "isOpaque"        => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isOpaque) }
            case "isPackageObject" => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isPackageObject) }
            case "isModule"        => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isModule) }
            case "isJava"          => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.isJava) }
            case "javaSpecific"    => '{ (sym: Reflect.Symbol) => Kyo.lift(sym.javaSpecific) }
            case "declaredType"    => '{ (sym: Reflect.Symbol) => sym.declaredType }
            case "parents"         => '{ (sym: Reflect.Symbol) => sym.parents }
            case "typeParams"      => '{ (sym: Reflect.Symbol) => sym.typeParams }
            case "declarations"    => '{ (sym: Reflect.Symbol) => sym.declarations }
            case "companion"       => '{ (sym: Reflect.Symbol) => sym.companion }
            case _ =>
                fieldType.asType match
                    case '[ft] =>
                        Expr.summon[Reflect.Reads[ft]] match
                            case Some(r) => '{ (sym: Reflect.Symbol) => $r.read(sym) }
                            case None =>
                                report.errorAndAbort(
                                    s"No Reflect.Reads[${fieldType.show}] for field '$fieldName'. " +
                                        s"Provide a given Reflect.Reads[${fieldType.show}] in scope."
                                )
        end match
    end buildSingleFieldReader

    // ── Constructor bridge ────────────────────────────────────────────────────

    /** Build an Array[Any] => A constructor function. The asInstanceOf casts are correct by construction: the macro knows exact field types
      * and the runtime helper fills the Array in the same field order.
      */
    private def buildCtorFn[A: scala.quoted.Type](
        using q: Quotes
    )(
        aType: quotes.reflect.TypeRepr,
        aSym: quotes.reflect.Symbol,
        caseFields: List[quotes.reflect.Symbol]
    ): Expr[Array[Any] => A] =
        import quotes.reflect.*
        val n = caseFields.length
        if n == 0 then
            val ctor  = Apply(Select(New(TypeTree.of[A]), aSym.primaryConstructor), Nil)
            val aExpr = ctor.asExprOf[A]
            '{ (_: Array[Any]) => $aExpr }
        else
            val lambdaSym = Symbol.newMethod(
                Symbol.spliceOwner,
                "$anonfun",
                MethodType(List("arr"))(_ => List(TypeRepr.of[Array[Any]]), _ => TypeRepr.of[A])
            )
            val lambdaDef = DefDef(
                lambdaSym,
                paramss =>
                    val arr = Ref(paramss.head.head.symbol)
                    val ctorArgs = caseFields.zipWithIndex.map { case (field, idx) =>
                        val ft    = aType.memberType(field)
                        val apply = Apply(Select.unique(arr, "apply"), List(Literal(IntConstant(idx))))
                        ft.asType match
                            case '[t] =>
                                TypeApply(Select.unique(apply, "asInstanceOf"), List(TypeTree.of[t]))
                    }
                    val ctor = Select(New(TypeTree.of[A]), aSym.primaryConstructor)
                    Some(Apply(ctor, ctorArgs))
            )
            Block(List(lambdaDef), Closure(Ref(lambdaSym), None)).asExprOf[Array[Any] => A]
        end if
    end buildCtorFn

    // ── FieldSet dispatch ─────────────────────────────────────────────────────

    private def directFieldTouched(fieldName: String): Option[Reflect.FieldSet] =
        fieldName match
            case "name" | "fullName" => Some(Reflect.FieldSet.Name)
            case "binaryName"        => Some(Reflect.FieldSet.BinaryName)
            case "flags"             => Some(Reflect.FieldSet.Flags)
            case "kind"              => Some(Reflect.FieldSet.Kind)
            case "owner"             => Some(Reflect.FieldSet.Owner)
            case "isInline" | "isContextual" | "isOpaque" | "isPackageObject" |
                "isModule" | "isJava" => Some(Reflect.FieldSet.Flags)
            case "javaSpecific" => Some(Reflect.FieldSet.JavaSpecific)
            case "declaredType" => Some(Reflect.FieldSet.DeclaredType)
            case "parents"      => Some(Reflect.FieldSet.Parents)
            case "typeParams"   => Some(Reflect.FieldSet.TypeParams)
            case "declarations" => Some(Reflect.FieldSet.Members)
            case "companion"    => Some(Reflect.FieldSet.Companion)
            case _              => None

end ReflectMacro
