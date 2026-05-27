package kyo.internal.tasty.reader

/** TASTy format constants, verbatim from dotty.tools.tasty.TastyFormat.
  *
  * Source: dotty/tools/tasty/TastyFormat.scala in tasty-core_3.
  *
  * Magic bytes (line 313): Array(0x5C, 0xA1, 0xAB, 0x1F), big-endian, MSB first. Version triple (lines 321-344): MajorVersion=28,
  * MinorVersion=8, ExperimentalVersion=0.
  *
  * AST tag layout:
  *   - Category 1 (1-59): tag only
  *   - Category 2 (60-89): tag + Nat
  *   - Category 3 (90-109): tag + AST
  *   - Category 4 (110-127): tag + Nat + AST
  *   - Category 5 (128-255): tag + Length + payload (length-prefixed nodes)
  */
object TastyFormat:

    // ── Magic bytes ──────────────────────────────────────────────────────────
    // TastyFormat.scala line 313
    val MagicBytes: Array[Int] = Array(0x5c, 0xa1, 0xab, 0x1f)

    // ── Version (TastyFormat.scala lines 321-344) ────────────────────────────
    final val MajorVersion: Int        = 28
    final val MinorVersion: Int        = 8
    final val ExperimentalVersion: Int = 0

    // ── Section name constants (TastyFormat.scala lines 394-397) ─────────────
    final val ASTsSection: String       = "ASTs"
    final val PositionsSection: String  = "Positions"
    final val CommentsSection: String   = "Comments"
    final val AttributesSection: String = "Attributes"

    // ── AST Tag constants ────────────────────────────────────────────────────

    // Category 1: tag only (1-59)
    final val UNITconst: Int     = 2
    final val FALSEconst: Int    = 3
    final val TRUEconst: Int     = 4
    final val NULLconst: Int     = 5
    final val PRIVATE: Int       = 6
    final val PROTECTED: Int     = 8
    final val ABSTRACT: Int      = 9
    final val FINAL: Int         = 10
    final val SEALED: Int        = 11
    final val CASE: Int          = 12
    final val IMPLICIT: Int      = 13
    final val LAZY: Int          = 14
    final val OVERRIDE: Int      = 15
    final val INLINEPROXY: Int   = 16
    final val INLINE: Int        = 17
    final val STATIC: Int        = 18
    final val OBJECT: Int        = 19
    final val TRAIT: Int         = 20
    final val ENUM: Int          = 21
    final val LOCAL: Int         = 22
    final val SYNTHETIC: Int     = 23
    final val ARTIFACT: Int      = 24
    final val MUTABLE: Int       = 25
    final val FIELDaccessor: Int = 26
    final val CASEaccessor: Int  = 27
    final val COVARIANT: Int     = 28
    final val CONTRAVARIANT: Int = 29
    final val HASDEFAULT: Int    = 31
    final val STABLE: Int        = 32
    final val MACRO: Int         = 33
    final val ERASED: Int        = 34
    final val OPAQUE: Int        = 35
    final val EXTENSION: Int     = 36
    final val GIVEN: Int         = 37
    final val PARAMsetter: Int   = 38
    final val EXPORTED: Int      = 39
    final val OPEN: Int          = 40
    final val PARAMalias: Int    = 41
    final val TRANSPARENT: Int   = 42
    final val INFIX: Int         = 43
    final val INVISIBLE: Int     = 44
    final val EMPTYCLAUSE: Int   = 45
    final val SPLITCLAUSE: Int   = 46
    final val TRACKED: Int       = 47
    final val SUBMATCH: Int      = 48
    final val INTO: Int          = 49

    // Category 2: tag + Nat (60-89)
    final val SHAREDterm: Int    = 60
    final val SHAREDtype: Int    = 61
    final val TERMREFdirect: Int = 62
    final val TYPEREFdirect: Int = 63
    final val TERMREFpkg: Int    = 64
    final val TYPEREFpkg: Int    = 65
    final val RECthis: Int       = 66
    final val BYTEconst: Int     = 67
    final val SHORTconst: Int    = 68
    final val CHARconst: Int     = 69
    final val INTconst: Int      = 70
    final val LONGconst: Int     = 71
    final val FLOATconst: Int    = 72
    final val DOUBLEconst: Int   = 73
    final val STRINGconst: Int   = 74
    final val IMPORTED: Int      = 75
    final val RENAMED: Int       = 76

    // Category 3: tag + AST (90-109)
    final val THIS: Int               = 90
    final val QUALTHIS: Int           = 91
    final val CLASSconst: Int         = 92
    final val BYNAMEtype: Int         = 93
    final val BYNAMEtpt: Int          = 94
    final val NEW: Int                = 95
    final val THROW: Int              = 96
    final val IMPLICITarg: Int        = 97
    final val PRIVATEqualified: Int   = 98
    final val PROTECTEDqualified: Int = 99
    final val RECtype: Int            = 100
    final val SINGLETONtpt: Int       = 101
    final val BOUNDED: Int            = 102
    final val EXPLICITtpt: Int        = 103
    final val ELIDED: Int             = 104

    // Category 4: tag + Nat + AST (110-127)
    final val IDENT: Int         = 110
    final val IDENTtpt: Int      = 111
    final val SELECT: Int        = 112
    final val SELECTtpt: Int     = 113
    final val TERMREFsymbol: Int = 114
    final val TERMREF: Int       = 115
    final val TYPEREFsymbol: Int = 116
    final val TYPEREF: Int       = 117
    final val SELFDEF: Int       = 118
    final val NAMEDARG: Int      = 119

    // Category 5: tag + Length + payload (128-255, length-prefixed nodes)
    final val firstLengthTreeTag: Int = 128 // == PACKAGE
    final val PACKAGE: Int            = 128
    final val VALDEF: Int             = 129
    final val DEFDEF: Int             = 130
    final val TYPEDEF: Int            = 131
    final val IMPORT: Int             = 132
    final val TYPEPARAM: Int          = 133
    final val PARAM: Int              = 134
    final val APPLY: Int              = 136
    final val TYPEAPPLY: Int          = 137
    final val TYPED: Int              = 138
    final val ASSIGN: Int             = 139
    final val BLOCK: Int              = 140
    final val IF: Int                 = 141
    final val LAMBDA: Int             = 142
    final val MATCH: Int              = 143
    final val RETURN: Int             = 144
    final val WHILE: Int              = 145
    final val TRY: Int                = 146
    final val INLINED: Int            = 147
    final val SELECTouter: Int        = 148
    final val REPEATED: Int           = 149
    final val BIND: Int               = 150
    final val ALTERNATIVE: Int        = 151
    final val UNAPPLY: Int            = 152
    final val ANNOTATEDtype: Int      = 153
    final val ANNOTATEDtpt: Int       = 154
    final val CASEDEF: Int            = 155
    final val TEMPLATE: Int           = 156
    final val SUPER: Int              = 157
    final val SUPERtype: Int          = 158
    final val REFINEDtype: Int        = 159
    final val REFINEDtpt: Int         = 160
    final val APPLIEDtype: Int        = 161
    final val APPLIEDtpt: Int         = 162
    final val TYPEBOUNDS: Int         = 163
    final val TYPEBOUNDStpt: Int      = 164
    final val ANDtype: Int            = 165
    final val ORtype: Int             = 167
    final val POLYtype: Int           = 169
    final val TYPELAMBDAtype: Int     = 170
    final val LAMBDAtpt: Int          = 171
    final val PARAMtype: Int          = 172
    final val ANNOTATION: Int         = 173
    final val TERMREFin: Int          = 174
    final val TYPEREFin: Int          = 175
    final val SELECTin: Int           = 176
    final val EXPORT: Int             = 177
    final val QUOTE: Int              = 178
    final val SPLICE: Int             = 179
    final val METHODtype: Int         = 180
    final val APPLYsigpoly: Int       = 181
    final val QUOTEPATTERN: Int       = 182
    final val SPLICEPATTERN: Int      = 183
    final val MATCHtype: Int          = 190
    final val MATCHtpt: Int           = 191
    final val MATCHCASEtype: Int      = 192
    final val FLEXIBLEtype: Int       = 193
    final val HOLE: Int               = 255

    // ── Name tags (TastyFormat.scala, NameTags inner class, lines ~401-429) ──
    object NameTags:
        final val UTF8: Int           = 1
        final val QUALIFIED: Int      = 2
        final val EXPANDED: Int       = 3
        final val EXPANDPREFIX: Int   = 4
        final val UNIQUE: Int         = 10
        final val DEFAULTGETTER: Int  = 11
        final val SUPERACCESSOR: Int  = 20
        final val INLINEACCESSOR: Int = 21
        final val BODYRETAINER: Int   = 22
        final val OBJECTCLASS: Int    = 23
        final val SIGNED: Int         = 63
        final val TARGETSIGNED: Int   = 62
    end NameTags

    // ── Attribute tag constants (TastyFormat.scala, lines 635-654) ──────────────
    // Category 1 (tags 1-32): boolean flags, no payload.
    final val SCALA2STANDARDLIBRARYattr: Int = 1 // file is part of Scala 2 stdlib
    final val EXPLICITNULLSattr: Int         = 2 // compiled with -Yexplicit-nulls
    final val CAPTURECHECKEDattr: Int        = 3 // compiled with -Ycc
    final val WITHPUREFUNSattr: Int          = 4 // compiled with -Ywith-pure-funs (internal, not surfaced)
    final val JAVAattr: Int                  = 5 // Java-originated TASTy file
    final val OUTLINEattr: Int               = 6 // outline TASTy (no bodies)

    // Category 3 (tags 129-160): tag + Utf8Ref (one Nat payload).
    final val SOURCEFILEattr: Int = 129 // source file name (Utf8Ref into name table)

    /** Returns true for Category 1 attribute tags (boolean flags, tags 1-32). */
    def isBooleanAttrTag(tag: Int): Boolean = tag >= 1 && tag <= 32

    /** Returns true for Category 3 attribute tags (Utf8Ref payload, tags 129-160). */
    def isStringAttrTag(tag: Int): Boolean = tag >= 129 && tag <= 160

    // ── Version compatibility (verbatim from TastyFormat.scala, lines 373-390) ──
    /** Returns true if the file version is readable by a compiler at the given version.
      *
      * Verbatim from dotty TastyFormat.isVersionCompatible:
      *   fileMajor == compilerMajor &&
      *     (  fileMinor == compilerMinor && fileExperimental == compilerExperimental
      *     || fileMinor <  compilerMinor && fileExperimental == 0
      *     )
      */
    def isVersionCompatible(
        fileMajor: Int,
        fileMinor: Int,
        fileExperimental: Int,
        compilerMajor: Int,
        compilerMinor: Int,
        compilerExperimental: Int
    ): Boolean =
        fileMajor == compilerMajor &&
            (fileMinor == compilerMinor && fileExperimental == compilerExperimental ||
                fileMinor < compilerMinor && fileExperimental == 0)

end TastyFormat
