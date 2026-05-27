# Cleanup Batch 3 Notes

## Scope

11 WARN items from Phase 5 (5) and Phase 5b (6) audits.

## Fix Plan

### P5-W1: Test 4 weakened (typeParams)

ClassfileUnpickler.buildResult does not call parseClassSignature on classAttrs.signatureIdx.
ClassfileResult does not expose typeParams.

Fix:
- In buildResult, when classAttrs.signatureIdx is Present, call parseClassSignature and extract the typeParam symbols.
- Add typeParams field to ClassfileResult.
- Update test 4 in ClassfileReaderTest to assert typeParams.length >= 1 and that the type param name is non-empty.

### P5-W2: Default params on internal Symbol.make / SymbolFactory.makeSymbol

Both Symbol.make in Reflect.scala and makeSymbol in symbol/Symbol.scala have `javaMetadata: Maybe[JavaMetadata] = Absent`.

Fix:
- Remove defaults from both.
- Find all call sites (makeSymbol calls without javaMetadata): all TASTy-side calls in AstUnpickler.scala, and internal calls in ClassfileUnpickler.scala that pass Absent.
- Pass Absent explicitly at all TASTy/no-metadata call sites.

### P5-W3: throw new IllegalStateException in ConstantPool.scala

Two throws:
- Line 196: unexpected ByteView variant (non-Heap case during UTF-8 copy)
- Line 269: unknown CP tag

Fix:
- ConstantPool.read is already inside Sync.defer. The throw is caught by Sync's panic boundary, not by Abort.
  The right fix is: the `read` function already returns `< Sync & Abort[ReflectError]`.
  We need to exit the Sync.defer block and return Abort.fail. But Sync.defer wraps everything in a single
  synchronous block, so throws inside it become panics (Fiber failure), not Abort failures.
  
  To properly convert to Abort.fail we need to restructure. However, the Sync.defer already catches
  thrown exceptions as panics. The correct approach is to use Abort.fail outside the Sync.defer.
  
  The cleanest fix: break out of the large Sync.defer block at the throw sites by using a flag/error
  accumulator, OR restructure to check the ByteView variant before entering the UTF-8 copy path.
  
  Actually the best path: replace `throw` with returning a sentinel and checking it, or restructure
  the Sync.defer to split at those points. But since this is inside a while loop, the real solution
  is: use a `var error: Maybe[String] = Absent` and check after the while loop, then return Abort.fail.
  
  For line 233 (unknown CP tag): same pattern.

### P5-W4: Dead val at ClassfileUnpickler.scala:626

In resolveThrowsList around line 1040-1058, there is no dead val visible.
The audit references line 626 but the current code at that area shows readRecordComponentAttributes.
Looking at the actual resolveThrowsList function (lines 1040-1058): no dead val.
The old dead val may have been removed in Phase 5b. Need to re-check.

Actually searching for nothingSym in ClassfileUnpickler: not present. Already cleaned up.

### P5-W5: recordComponents always Chunk.empty

Looking at current code: ClassfileUnpickler already parses Record attribute in readClassAttributes
and readRecordComponents (lines 510-647). The recordComponents field in ClassAttributes IS populated.
buildRecordComponents IS wired. Test 8 in JavaSymbolTest asserts x and y. So this was FIXED in Phase 5b.

### P5b-W1: Test 16 (Type.Array) synthesizes via internal API

UnifiedModelTest.scala test 16 uses Symbol.makeSymbol directly on LHS.
Fix: Add a classfile fixture with an int[] field (ArrayCarrier.java), embed it,
then load it and assert at least one Type.Array appears in field symbols' types.
But Phase 7 doesn't resolve field types... Actually the parseErasedDescriptorType already
returns Type.Array for "[I". But field types aren't stored in symbols yet (Phase 7).
For record components: PointRecord has int x, int y - no arrays.

Looking more carefully: the field type is parsed in resolveComponentType for record components.
We could add a record with an array field. But we need to embed the classfile.

Alternative: Check if ArrayCarrier fixture exists already.

### P5b-W2: Stale TODO comments in JavaAnnotationUnpickler.scala

Lines 113-114 and 120-121 have stale TODO comments. The code is correct, just the comments are wrong.
Fix: Remove the TODO lines.

### P5b-W3: buildEnclosingMethod Absent case returns wrong value

Line 843-844: when enclosingMethodIdx is Absent, returns Present((enclosingClassSym, Reflect.Name("")))
Should return Absent.

### P5b-W4: readAnnotations param drift

Plan says addrMap, impl uses home: ClasspathRef. Add scaladoc explaining the deviation.

### P5b-W5 (REAL BUG): Flags.fromJvmAccessFlags ACC_INTERFACE bit mapped to Flag.Abstract

Line 77: `if (acc & 0x0200) != 0 then bits |= Flag.Abstract.bit`
0x0200 = ACC_INTERFACE. Should be 0x0400 = ACC_ABSTRACT.
Fix: Change line 77 to check 0x0400 for Abstract. Keep line 78 (0x0200 -> Trait) as-is.

### P5b-W6: ACC_STATIC mapped to Flag.JavaDefined

Line 81: `if (acc & 0x0008) != 0 then bits |= Flag.JavaDefined.bit // ACC_STATIC`
Need to add Flag.Static to Reflect.Flag enum (next bit after PARAMalias = bit 43).
Then change line 81 to set Flag.Static.

## Call Site Audit for P5-W2

Files that call makeSymbol without explicit javaMetadata:
- ClassfileUnpickler.scala: makeUnresolvedSymbol, unresolvedType, buildPackageOwnerChain, buildPackageSymbol, resolveThrowsList, primType, descriptorToUnresolvedSymbol (JavaAnnotationUnpickler)
- JavaSignatures.scala: makeStub, classStub
- AstUnpickler.scala: all passes

All of these get Absent. The ones that pass Present explicitly: ClassfileUnpickler.buildResult (classSym), buildOneMemberSymbol.
