# Phase 13 decisions

## Symbol construction strategy (tests 1-4)

Synthetic Symbol instances were built using `Tasty.Symbol.make` with `Tasty.Symbol.TastyOrigin.empty`
and a fresh `new ClasspathRef` per symbol. This matches the pattern used in SubtypeTest,
TypeOpsTest, and CommentsUnpicklerTest. No classpath I/O is needed for binaryName or
isPackageObject, so this is strictly cross-platform and avoids the fixture-classpath
overhead used by the existing tests 3-5.

Owner chains for binaryName tests use:
- root: Package("", owner=null)
- intermediate packages: Package(name, owner)
- leaf: Class(name, owner)

`computeBinaryName` walks the owner chain and switches from '/' to '$' when the previous
owner kind is Class, Trait, or Object. The null-owner root terminates the walk because the
loop condition includes `cur.owner != null`.

## jvmOnly tags

No jvmOnly tags applied. All six new tests use only in-memory synthetic Symbol construction
or Tasty.Annotation/Tasty.Type value constructors. No JAR loading, no real classpath I/O,
no platform-specific APIs.

## Annotation.unapply and Maybe/Present rule

`Tasty.Annotation.unapply` has return type `Some[(Type, Chunk[Byte])]` (standard library).
Per the feedback rule against using Option/Some/None, the test wraps the result with
`Maybe.fromOption(Tasty.Annotation.unapply(a))` and then pattern-matches on
`Present((tpe, pickle))`. This satisfies the constraint while correctly exercising the
extractor.

## Scalafmt reformatted files

The scalafmt pre-compile hook reformatted TastySymbolTest.scala, TastyAnnotationTest.scala,
and TastyTypeTest.scala. The reformatted versions are the current state of the files.
