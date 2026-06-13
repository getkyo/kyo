package kyo.internal.tasty.classfile

/** JVM classfile constants: constant-pool tags, access-flag masks, and attribute name strings.
  *
  * Source: JVM Specification (JVMS) Chapter 4.
  */
object ClassfileFormat:

    // Magic constant
    val Magic: Int = 0xcafebabe

    // Minimum supported major/minor version (Java 1.1)
    val MinMajorVersion: Int = 45
    val MinMinorVersion: Int = 3

    // Constant-pool tag values (JVMS §4.4)
    val CONSTANT_Utf8: Int               = 1
    val CONSTANT_Integer: Int            = 3
    val CONSTANT_Float: Int              = 4
    val CONSTANT_Long: Int               = 5
    val CONSTANT_Double: Int             = 6
    val CONSTANT_Class: Int              = 7
    val CONSTANT_String: Int             = 8
    val CONSTANT_Fieldref: Int           = 9
    val CONSTANT_Methodref: Int          = 10
    val CONSTANT_InterfaceMethodref: Int = 11
    val CONSTANT_NameAndType: Int        = 12
    val CONSTANT_MethodHandle: Int       = 15
    val CONSTANT_MethodType: Int         = 16
    val CONSTANT_Dynamic: Int            = 17
    val CONSTANT_InvokeDynamic: Int      = 18
    val CONSTANT_Module: Int             = 19
    val CONSTANT_Package: Int            = 20

    // Class-level access flags (JVMS §4.1 Table 4.1-B)
    val ACC_PUBLIC: Int     = 0x0001
    val ACC_FINAL: Int      = 0x0010
    val ACC_SUPER: Int      = 0x0020
    val ACC_INTERFACE: Int  = 0x0200
    val ACC_ABSTRACT: Int   = 0x0400
    val ACC_SYNTHETIC: Int  = 0x1000
    val ACC_ANNOTATION: Int = 0x2000
    val ACC_ENUM: Int       = 0x4000
    // ACC_RECORD (0x0010) shares the bit with ACC_FINAL at class level; detect via Record attribute.

    // Field-level access flags (JVMS §4.5 Table 4.5-A)
    val ACC_PRIVATE: Int   = 0x0002
    val ACC_PROTECTED: Int = 0x0004
    val ACC_STATIC: Int    = 0x0008
    val ACC_VOLATILE: Int  = 0x0040
    val ACC_TRANSIENT: Int = 0x0080

    // Method-level additional flags (JVMS §4.6 Table 4.6-A)
    val ACC_SYNCHRONIZED: Int = 0x0020
    val ACC_BRIDGE: Int       = 0x0040
    val ACC_VARARGS: Int      = 0x0080
    val ACC_NATIVE: Int       = 0x0100
    val ACC_STRICT: Int       = 0x0800

    // Attribute name strings
    val AttrSignature: String                   = "Signature"
    val AttrInnerClasses: String                = "InnerClasses"
    val AttrEnclosingMethod: String             = "EnclosingMethod"
    val AttrRecord: String                      = "Record"
    val AttrExceptions: String                  = "Exceptions"
    val AttrCode: String                        = "Code"
    val AttrRuntimeVisibleAnnotations: String   = "RuntimeVisibleAnnotations"
    val AttrRuntimeInvisibleAnnotations: String = "RuntimeInvisibleAnnotations"
    // Scala 2 pickle attributes embedded in .class files
    val AttrScalaSig: String = "ScalaSig"
    val AttrScala: String    = "Scala"

    // JVM 11+ nest-based access control (JVMS §4.7.28 / §4.7.29)
    val AttrNestHost: String    = "NestHost"
    val AttrNestMembers: String = "NestMembers"

    // JVM 17+ sealed-class permitted subclasses (JVMS §4.7.31)
    val AttrPermittedSubclasses: String = "PermittedSubclasses"

    // JVM 8+ method parameter names and access flags (JVMS §4.7.24)
    val AttrMethodParameters: String = "MethodParameters"

    // JVM 8+ bootstrap methods table for invokedynamic (JVMS §4.7.23)
    val AttrBootstrapMethods: String = "BootstrapMethods"

    // JVM 8+ runtime-visible/invisible type annotations (JVMS §4.7.20 / §4.7.21)
    val AttrRuntimeVisibleTypeAnnotations: String   = "RuntimeVisibleTypeAnnotations"
    val AttrRuntimeInvisibleTypeAnnotations: String = "RuntimeInvisibleTypeAnnotations"

end ClassfileFormat
