# Phase 29a decisions

## Methods that received `(using AllowUnsafe)`

### ByteView (shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala)
- `readByte()` (trait + Heap impl)
- `readNat()` (default impl delegates to Varint.readNat)
- `readInt()` (default impl delegates to Varint.readInt)
- `readLongNat()` (default impl delegates to Varint.readLongNat)
- `readEnd()` (trait + Heap impl)
- `readEndInt` final wrapper (calls readEnd)
- `goto(addr: Long)` (trait + Heap impl)
- `gotoInt(addr: Int)` final wrapper (calls goto)

### Varint (shared/src/main/scala/kyo/internal/tasty/binary/Varint.scala)
- `readNat(view: ByteView)`
- `readLongNat(view: ByteView)`
- `readInt(view: ByteView)`
- `readLongInt(view: ByteView)`
- `writeNat(out: ArrayBuffer[Byte], v: Int)`
- `writeLongNat(out: ArrayBuffer[Byte], v: Long)`

### MappedByteView JVM (jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala)
- `readByte()`
- `readEnd()`
- `goto(addr: Long)`

### MappedByteView Native (native/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala)
- `readByte()`
- `readEnd()`
- `goto(addr: Long)`

### PortableInflate / BitStream (shared/src/main/scala/kyo/internal/tasty/scala2/PortableInflate.scala)
- `BitStream.readBit()`
- `BitStream.readBits(n: Int)`
- `BitStream.alignToByte()`
- `BitStream.readBytes(out, len)`
- `HuffmanTree.decodeOne(stream: BitStream)`
- `decodeStoredBlock(stream, out)`
- `decodeFixedHuffmanBlock(stream, out)`
- `decodeHuffmanBlock(stream, out, litTree, distTree)`
- `decodeDynamicHuffmanBlock(stream, out)`
- `decodeCodeLengths(stream, tree, total)`
- `inflate(compressed: Array[Byte])`

## Callers that needed propagation (methods now taking `(using AllowUnsafe)`)

### Reader layer
- `CommentsUnpickler.read` (public) - added `AllowUnsafe` to signature
- `CommentsUnpickler.readSync` - added `AllowUnsafe` to signature
- `CommentsUnpickler.skipLongInt` - added `AllowUnsafe` to signature
- `PositionsUnpickler.read` (public) - added `AllowUnsafe` to signature
- `PositionsUnpickler.readSync` - added `AllowUnsafe` to signature
- `TastyHeader.read` (public) - added `AllowUnsafe` to signature
- `TastyHeader.checkMagic` - added `AllowUnsafe` to signature
- `TastyHeader.readVersions` - added `AllowUnsafe` to signature
- `TastyHeader.readTooling` - added `AllowUnsafe` to signature
- `TastyHeader.readUuid` - added `AllowUnsafe` to signature
- `TastyHeader.readUncompressedLong` - added `AllowUnsafe` to signature
- `NameUnpickler.readBytes` private helper
- `AstUnpickler.decodeOneTypeIfPresent`
- `AstUnpickler.readDefDefReturnType`
- `AstUnpickler.decodeTemplateParents`
- `AstUnpickler.scanForwardAndCollectFlags`
- `AstUnpickler.readModifiers`
- `AstUnpickler.skipTree`
- `AstUnpickler.skipTreeBody`
- `TreeUnpickler.decodeAnnotationTerm` (public, private[kyo])
- `TreeUnpickler.readTree`
- `TreeUnpickler.readTreesUntil`
- `TreeUnpickler.decodeTreeTag`
- `TreeUnpickler.readCaseDefs`
- `TreeUnpickler.readCaseDefGuardAndBody`
- `TreeUnpickler.readUnapplyParts`
- `TreeUnpickler.readOneParamClause`
- `TreeUnpickler.readType`
- `TreeUnpickler.readTypesUntil`
- `TreeUnpickler.readOptionalRhs`
- `TreeUnpickler.skipOneTree`
- `TreeUnpickler.skipTreeBody`
- `TreeUnpickler.skipModifierTags`
- `TypeUnpickler.readType` (public)
- `TypeUnpickler.readTypeForTree`
- `TypeUnpickler.readTypeIntoSession`
- `TypeUnpickler.readTypeNode`
- `TypeUnpickler.readTypesUntil`
- `TypeUnpickler.skipToEnd`
- `TypeUnpickler.skipOneType`
- `TypeUnpickler.skipTreeBody`

### Classfile layer
- `ConstantPool.readU1`, `readU2`, `readU4`, `readU8`
- `ModuleInfoReader.checkHeader`, `skipClassStructure`, `skipMember`, `skipAttribute`
- `ModuleInfoReader.readModuleAttribute`, `readAttributesForModule`, `decodeModuleAttribute`
- `ModuleInfoReader.readRequires`, `readExports`, `readOpens`, `readUses`, `readProvides`
- `ModuleInfoReader.readModuleRefs`, `readClassRefs`
- `ClassfileUnpickler.readMemberInfos`, `readMemberList`, `readOneMemberInfo`, `readMemberAttributes`
- `ClassfileUnpickler.readClassAttributes`, `readClassAttrList`
- `ClassfileUnpickler.readRecordComponents`, `readRecordComponentList`, `readRecordComponentAttributes`
- `ClassfileUnpickler.readBootstrapMethodsData`, `readMethodParameterNames`, `readMethodParamList`
- `ClassfileUnpickler.skipTypeAnnotationTargetAndPath`, `captureBytes`, `skipBytes`

## JS InflateHook boundary change
- `js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala`: changed `Sync.defer` to `Sync.Unsafe.defer` since `PortableInflate.inflate` now requires `AllowUnsafe`.

## Tasty.scala boundary change
- Changed `Sync.defer` to `Sync.Unsafe.defer` at annotation arg decode site (line ~219) since `TreeUnpickler.decodeAnnotationTerm` now requires `AllowUnsafe`.

## Test files updated
- `ByteViewTest.scala`: added `import AllowUnsafe.embrace.danger` in 4 test bodies
- `VarintTest.scala`: rewrote with `import AllowUnsafe.embrace.danger` in every test body
- `TastyHeaderTest.scala`: rewrote with `import AllowUnsafe.embrace.danger` in every test body
- `PortableInflateTest.scala`: added `import AllowUnsafe.embrace.danger` in 8 test bodies
- `InflateHookTest.scala`: added `import AllowUnsafe.embrace.danger` in 1 test body (JS delegation test)
- `ConstantPoolTest.scala`: updated `HeapMappedStub.readByte()`, `readEnd()`, `goto()` with `(using AllowUnsafe)`
- `MappedByteViewTest.scala`: added `(using AllowUnsafe)` to `makeView` helper; added imports in 2 test bodies
- `NativeMmapReaderTest.scala` (Native-specific): added `import AllowUnsafe.embrace.danger` in 2 test bodies

## Import count change
- Before: 42 `import AllowUnsafe.embrace.danger` in production main sources
- After: 41 (net reduction of 1 because CommentsUnpickler, PositionsUnpickler, TastyHeader, TypeUnpickler.readType switched from method-body imports to `(using AllowUnsafe)` propagation)

Decision 1: For public methods (CommentsUnpickler.read, PositionsUnpickler.read, TastyHeader.read, TypeUnpickler.readType) that were previously accessible without AllowUnsafe, added `AllowUnsafe` to their parameter lists rather than using method-body imports. This reduces embrace.danger site count.
Rationale: Steering §4 "EVERY METHOD THAT PERFORMS SIDE EFFECTS WITHOUT SUSPENSION MUST TAKE (using AllowUnsafe) IN ITS SIGNATURE."
Time: 2026-05-30

Decision 2: For `TreeUnpickler.decodeAnnotationTerm`, changed call site in Tasty.scala from `Sync.defer` to `Sync.Unsafe.defer`. The method is a pure-compute tree decode with no shared state, qualifying as a §839 case 3 boundary.
Rationale: PortableInflate.inflate followed the same pattern for the JS InflateHook.
Time: 2026-05-30

Decision 3: For `decodeTreeTag`, removed the pre-existing `import AllowUnsafe.embrace.danger` since the method now has `(using AllowUnsafe)` in its signature; keeping both causes "Ambiguous given instances" compile error.
Rationale: Two AllowUnsafe providers in the same scope (method parameter + import) produce ambiguity.
Time: 2026-05-30
