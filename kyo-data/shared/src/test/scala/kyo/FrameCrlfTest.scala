package kyo

// This file is intentionally checked out with CRLF line terminators on every
// platform (.gitattributes pins `eol=crlf`) and excluded from scalafmt
// (project.excludeFilters) so the terminators survive formatting. It guards
// Frame derivation against CRLF sources: the compiler's position offsets index
// its raw chars, so a derivation that normalizes the memoized content shifts
// every offset by the count of preceding CRs. Mid-file derivations then read
// wrong offsets (the first test pins the extracted values), and a derivation
// within the trailing stretch of the file, where the shifted offset passes the
// shortened content, fails the whole compile with a
// StringIndexOutOfBoundsException (the end-of-file derivation covers that).
class FrameCrlfTest extends kyo.test.Test[Any]:

    def deriveHere(using frame: Frame): Frame = frame

    "derivation mid-file extracts callee, position, and snippet" in {
        assert(midFileFrame.calleeName == "deriveHere")
        assert(midFileFrame.show == "Frame(FrameCrlfTest.scala:27:41, kyo.FrameCrlfTest, ?, val midFileFrame: Frame = deriveHere)")
    }

    "derivation at the end of a CRLF file compiles and extracts" in {
        assert(endOfFileFrame.calleeName == "deriveHere")
        assert(endOfFileFrame.show == "Frame(FrameCrlfTest.scala:69:43, kyo.FrameCrlfTest, ?, val endOfFileFrame: Frame = deriveHere)")
    }

    val midFileFrame: Frame = deriveHere

    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    // filler: keeps the end-of-file derivation inside the trailing stretch
    val endOfFileFrame: Frame = deriveHere
end FrameCrlfTest
