package kyo.fixtures

/** Fixture for testing all 28 previously-uncovered Tree ADT variants.
  *
  * Each section is annotated with which Tree variant(s) it generates in TASTy.
  *
  * Scaladoc: 8-35 lines.
  */

// ── TypeDef ──────────────────────────────────────────────────────────────────
// TypeDef appears when a class body contains a type alias or abstract type member.
// TYPEDEF tag -> TypeDef (rhs is a type, not a TEMPLATE).

trait HasTypeDef:
    type MyType = Int
    type AnotherType <: String
end HasTypeDef

// ── Template + SelfDef ───────────────────────────────────────────────────────
// Template appears as the body of any class/trait. SelfDef appears when a class
// has an explicit self-type annotation.

trait SelfDefFixture:
    self: HasTypeDef =>
    def useType: MyType
end SelfDefFixture

// ── Super ─────────────────────────────────────────────────────────────────────
// Super appears in super.method() calls (SUPER tag in TASTy body).

class SuperFixtureBase:
    def baseX: Int = 1
end SuperFixtureBase

class SuperFixture extends SuperFixtureBase:
    override def baseX: Int = super.baseX + 1
end SuperFixture

// ── RefinedType ───────────────────────────────────────────────────────────────
// RefinedType (REFINEDtype tag) appears when a type is refined with an extra member.
// e.g. val x: PlainClass { def extra: Int } = ...

def takeRefined(v: PlainClass { def extra: Int }): Int = v.x

// ── AndType ───────────────────────────────────────────────────────────────────
// AndType (ANDtype tag) appears in intersection types used as type arguments
// or in method bodies. They appear in tree-form inside method bodies.

def useAndType(v: HasTypeDef & SomeTrait): Int = v.compute

// ── OrType ────────────────────────────────────────────────────────────────────
// OrType (ORtype tag) appears in union types in method bodies.

def useOrType(v: PlainClass | SomeCaseClass): Int =
    v match
        case p: PlainClass    => p.x
        case s: SomeCaseClass => s.count

// ── SuperType ─────────────────────────────────────────────────────────────────
// SuperType (SUPERtype tag) appears in super.method() calls in TASTy body encoding.
// It encodes the "from" part of super[T].method.

trait SuperTypeFixtureBase:
    def baseMethod: Int = 1
end SuperTypeFixtureBase

class SuperTypeFixture extends SuperTypeFixtureBase:
    def callSuper: Int = super[SuperTypeFixtureBase].baseMethod
end SuperTypeFixture

// ── AnnotatedType ─────────────────────────────────────────────────────────────
// AnnotatedType (ANNOTATEDtype tag) appears when a type is annotated in tree position,
// e.g. inside a method body.

import scala.annotation.unchecked.uncheckedVariance

def annotatedTypeMethod[A](x: A @uncheckedVariance): A = x

// ── RecType + RecThisAddr ─────────────────────────────────────────────────────
// RecType appears with recursive type patterns. In Scala 3, recursive class/trait
// types may produce a RECtype node in the body encoding.
// RecThisAddr (RECthis tag) references the enclosing RecType frame.

trait RecFixture[A]:
    def self: RecFixture[A]
end RecFixture

// ── IdentTpt + SelectTpt ─────────────────────────────────────────────────────
// IdentTpt and SelectTpt appear in type-tree positions (template parents, type ascriptions).
// IdentTpt: simple type name in type position (IDENTtpt tag).
// SelectTpt: qualified type name in type position (SELECTtpt tag).

class UseIdentTpt extends java.io.Serializable

// ── SingletonTpt ──────────────────────────────────────────────────────────────
// SingletonTpt (SINGLETONtpt tag) appears for singleton types like `x.type`.

def singletonType(x: SomeObject.type): Int = 42

// ── ByNameTpt ────────────────────────────────────────────────────────────────
// ByNameTpt (BYNAMEtpt tag) appears in the type tree for by-name parameters.

def byNameParam(x: => Int): Int = x

// ── ByNameType ───────────────────────────────────────────────────────────────
// ByNameType (BYNAMEtype tag) appears in method type signatures for by-name params.
// Also appears in body trees when a by-name argument is passed.

def passThrough(f: => String): String = f

// ── FlexibleType ─────────────────────────────────────────────────────────────
// FlexibleType (FLEXIBLEtype tag) appears in Scala 3.3+ for nullability-flexible types
// from Java interop. These appear in method bodies that use Java APIs.

def useJavaString(s: String): Int = s.length

// ── TypeRefPkg ────────────────────────────────────────────────────────────────
// TypeRefPkg (TYPEREFpkg tag) appears for references to top-level packages as types.
// This appears in "isInstanceOf" checks or other package-level references.

def checkInstance(x: Any): Boolean = x.isInstanceOf[java.lang.String]

// ── TypeRefDirect ─────────────────────────────────────────────────────────────
// TypeRefDirect (TYPEREFdirect tag) appears for direct type references to symbols
// in the same file or closely related files.

class TypeRefDirectFixture:
    def self: TypeRefDirectFixture = this
end TypeRefDirectFixture

// ── TypeRefSymbol ─────────────────────────────────────────────────────────────
// TypeRefSymbol (TYPEREFsymbol tag) appears in type trees referencing symbols
// by their address in the same TASTy section.

class TypeRefSymbolFixture[A <: PlainClass]:
    def get: A = ???
end TypeRefSymbolFixture

// ── TypeRefTree ───────────────────────────────────────────────────────────────
// TypeRefTree (TYPEREF tag) appears in type trees for qualified type references
// where the qualifier is a tree path.

def typeRefTreeMethod: scala.collection.immutable.List[Int] = List(1, 2, 3)

// ── TermRefSymbol ─────────────────────────────────────────────────────────────
// TermRefSymbol (TERMREFsymbol tag) appears in term-position references to symbols
// by their TASTy address, usually for local val references in method bodies.

def termRefSymbolMethod: Int =
    val local = 42
    local + 1

// ── Imported + Renamed ────────────────────────────────────────────────────────
// These appear in import selectors. IMPORTED (tag for the selected name),
// RENAMED (tag for the rename target in "import X as Y").

import scala.collection.mutable.ArrayBuffer as AB
import scala.collection.mutable.ListBuffer

def useImported: AB[Int] =
    val buf = AB[Int]()
    buf += 1
    buf
end useImported

// ── SelectOuter ──────────────────────────────────────────────────────────────
// SelectOuter (SELECTouter tag) appears when an inner class accesses an outer class member.
// This is a JVM-level outer-class reference.

class OuterForSelectOuter(val outerVal: Int):
    class Inner:
        def getOuter: Int = outerVal
    end Inner
end OuterForSelectOuter

// ── ExplicitTpt + Elided ──────────────────────────────────────────────────────
// ExplicitTpt appears when a val/def has an explicit type annotation.
// Elided appears when the type is inferred and represented by the compiler.

val explicitTypedVal: Int = 42

// ── Bounded ──────────────────────────────────────────────────────────────────
// Bounded (BOUNDED tag) appears for bounded wildcards in type trees.

def boundedWildcard(xs: java.util.List[? <: Number]): Int = xs.size
