# Phase 31 decisions

Decision 1: Make SingleAssign.get() and isSet pure by internally embracing AllowUnsafe
Rationale: Write-once slot; post-set reads are referentially transparent (§839 case 3). The set() mutation path keeps (using AllowUnsafe).
Time: 2026-05-30

Decision 2: Make OnceCell.get() pure by internally embracing AllowUnsafe
Rationale: Memoized lazy; race-and-discard semantics; post-init reads are referentially transparent (§839 case 3). The init() allocation path keeps (using AllowUnsafe).
Time: 2026-05-30

Decision 3: Name.apply(s: String) drops (using AllowUnsafe); internally embraces for Interner.intern
Rationale: Monotone interner; same input produces same Name forever; externally observable behavior is referentially transparent (§839 case 3).
Time: 2026-05-30

Decision 4: Name.asString drops (using AllowUnsafe)
Rationale: OnceCell.get() is now pure (Decision 2); asString was the sole AllowUnsafe consumer.
Time: 2026-05-30

Decision 5: Symbol.fullName drops (using AllowUnsafe)
Rationale: Only call was _fullNameOnce.get() (OnceCell.get() now pure via Decision 2).
Time: 2026-05-30

Decision 6: Symbol.isPackageObject drops (using AllowUnsafe)
Rationale: Only call was name.string.get() (OnceCell.get() now pure via Decision 2).
Time: 2026-05-30

Decision 7: Symbol.scaladoc drops (using AllowUnsafe)
Rationale: Only calls were _scaladoc.isSet and _scaladoc.get() (SingleAssign.isSet/get() now pure via Decision 1).
Time: 2026-05-30

Decision 8: Symbol.position drops (using AllowUnsafe)
Rationale: Only calls were _position.isSet and _position.get() (SingleAssign.isSet/get() now pure via Decision 1).
Time: 2026-05-30

Decision 9: Symbol.declaredType drops (using AllowUnsafe)
Rationale: Only call was _declaredType.get() (SingleAssign.get() now pure via Decision 1).
Time: 2026-05-30

Decision 10: Symbol.parents drops (using AllowUnsafe)
Rationale: Only call was _parents.get() (SingleAssign.get() now pure via Decision 1).
Time: 2026-05-30

Decision 11: Symbol.typeParams drops (using AllowUnsafe)
Rationale: Only call was _typeParams.get() (SingleAssign.get() now pure via Decision 1).
Time: 2026-05-30

Decision 12: Symbol.declarations drops (using AllowUnsafe)
Rationale: Only call was _declarations.get() (SingleAssign.get() now pure via Decision 1).
Time: 2026-05-30

Decision 13: Symbol.companion drops (using AllowUnsafe); internally embraces for pureClass lookup
Rationale: home.isAssigned and home.get() become pure (Decision 15); pureClass still needs AllowUnsafe (reads stateRef.unsafe.get()). Internally embraces for pureClass (§839 case 3 -- post-open immutable Ready-state fqnIndex lookup).
Time: 2026-05-30

Decision 14: Symbol.permittedSubclasses drops (using AllowUnsafe)
Rationale: Only calls were _permittedSubclasses.isSet and _permittedSubclasses.get() (SingleAssign.isSet/get() now pure via Decision 1).
Time: 2026-05-30

Decision 15: ClasspathRef.get() and ClasspathRef.isAssigned drop (using AllowUnsafe)
Rationale: Both only called SingleAssign.get() and SingleAssign.isSet respectively (now pure via Decision 1). ClasspathRef.assign() and ClasspathRef.init() keep (using AllowUnsafe).
Time: 2026-05-30

Decision 16: Classpath extension methods findClass/findPackage/packages/topLevelClasses/errors/findModule/findClassByBinary drop (using AllowUnsafe); each internally embraces
Rationale: The internal pureClass/purePackage/etc. methods still use stateRef.unsafe.get() (genuinely unsafe). The extension methods embrace internally with §839 case 3 annotations.
Time: 2026-05-30

Decision 17: Subtyping.isSubtype and all private Subtyping methods drop (using AllowUnsafe) (Step 6 transitive cleanup)
Rationale: These methods only accessed _parents.isSet, _parents.get(), _typeParams.isSet, _typeParams.get(), and fullName.asString -- all now pure after Decisions 1, 2, 5, 10, 11. No genuine state mutation remains.
Time: 2026-05-30

Decision 18: SnapshotWriter.nameToStr drops (using AllowUnsafe) (Step 6 transitive cleanup)
Rationale: Only called n.asString which is now pure via Decision 4.
Time: 2026-05-30

Decision 19: Symbol.computeFullName and Symbol.computeBinaryName lose their internal import AllowUnsafe.embrace.danger
Rationale: These only called Name.asString (now pure via Decision 4) and Name.apply (now pure via Decision 3). No unsafe operations remain.
Time: 2026-05-30

Decision 20: Tasty.isSubtypeOf extension loses its internal given AllowUnsafe = AllowUnsafe.embrace.danger
Rationale: Subtyping.isSubtype no longer requires AllowUnsafe (Decision 17).
Time: 2026-05-30

Decision 21: TastyTest.scala INV-001 tests updated to assert ABSENCE of (using AllowUnsafe) on all 10 accessors
Rationale: Phase 31 goal reverses the Phase 02a invariant; the tests now assert the cleaned-up state.
Time: 2026-05-30

Decision 22: TastyTest.scala doctest anchor tests updated to search for pure method signatures
Rationale: findClass/topLevelClasses/packages no longer have (using AllowUnsafe) in their signatures.
Time: 2026-05-30

---

## Methods that KEPT (using AllowUnsafe) - genuinely state-mutating:

- SingleAssign.set(a: A)(using AllowUnsafe): Unit -- writes AtomicRef.Unsafe (mutation)
- SingleAssign.init[A]()(using AllowUnsafe): SingleAssign[A] -- allocates AtomicRef.Unsafe
- OnceCell.init[A](init: () => A)(using AllowUnsafe): OnceCell[A] -- allocates AtomicRef.Unsafe
- ClasspathRef.assign(cp)(using AllowUnsafe): Unit -- calls SingleAssign.set (mutation)
- ClasspathRef.init()(using AllowUnsafe): ClasspathRef -- allocates SingleAssign (mutation)
- Symbol.make(...)(using AllowUnsafe): Symbol -- allocates SingleAssign and OnceCell slots
- Symbol.TastyOrigin.init(...)(using AllowUnsafe): TastyOrigin -- allocates SingleAssign slot
- Interner.intern(...)(using AllowUnsafe): Entry -- mutates shard state
- Interner.init(...)(using AllowUnsafe): Interner -- allocates AtomicRef.Unsafe/AtomicInt.Unsafe
- DeclarationTable.populate/get/all/storageKind (using AllowUnsafe) -- AtomicRef mutations
- Classpath.isClosed(using AllowUnsafe): Boolean -- reads stateRef.unsafe.get()
- Classpath.pureClass/purePackage/pureModule/pureTopLevelClasses/purePackages/accumulatedErrors (using AllowUnsafe) -- stateRef.unsafe.get()
- Classpath.transitionToReady/close (using AllowUnsafe) -- stateRef.unsafe.set()
- Classpath.allSymbols(using AllowUnsafe) -- stateRef.unsafe.get()
- ByteView cursor reads (readByte, readNat, etc.) (using AllowUnsafe) -- mutate cursor position
- Varint.writeNat and friends (using AllowUnsafe) -- mutate ArrayBuffer
- All AstUnpickler/TreeUnpickler/TypeUnpickler reader methods (using AllowUnsafe) -- ByteView cursor reads
- CommentsUnpickler/SectionIndex/PositionsUnpickler reader methods (using AllowUnsafe) -- ByteView cursor reads
- SnapshotWriter.serialize internal ByteArray operations (using AllowUnsafe) -- stateRef.unsafe.get()
- SnapshotReader parse methods (using AllowUnsafe) -- ByteView cursor reads

Total: ~2 (using AllowUnsafe) remaining in Tasty.scala (was ~25 before Phase 31), all for make() and TastyOrigin.init().
