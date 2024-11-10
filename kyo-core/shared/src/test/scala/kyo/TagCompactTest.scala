package kyo

import kyo.kernel.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.compatible.Assertion

class TagCompactTest extends Test:

    "Tag is compact" - {
        "Tag[Effect]" in {
            assert(Tag[Effect].raw == "!=q;!T0;!U1;!V2;")
        }
        "Tag[ArrowEffect[Const[Unit], Const[Unit]]]" in {
            assert(Tag[ArrowEffect[Const[Unit], Const[Unit]]].raw == "!?s;!=q;!T0;!U1;!V2;[-!+a;!W3;!U1;!V2;,+!+a;!W3;!U1;!V2;]")
        }
        "Tag[ContextEffect[Int]]" in {
            assert(Tag[ContextEffect[Int]].raw == "!>r;!=q;!T0;!U1;!V2;[+!Y5;!W3;!U1;!V2;]")
        }
        "Tag[Abort[Int]]" in {
            assert(Tag[Abort[Int]].raw == "!@t;!?s;!=q;!T0;!U1;!V2;[-!Y5;!W3;!U1;!V2;]")
        }
        "Tag[Async.Join]" in {
            assert(Tag[Async.Join].raw == "!Au;!?s;!=q;!T0;!U1;!V2;")
        }
        "Tag[Emit[Int]]" in {
            assert(Tag[Emit[Int]].raw == "!Bv;!?s;!=q;!T0;!U1;!V2;[=!Y5;!W3;!U1;!V2;]")
        }
    }
end TagCompactTest
