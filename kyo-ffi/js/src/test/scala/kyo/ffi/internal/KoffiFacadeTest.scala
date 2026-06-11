package kyo.ffi.internal

import kyo.ffi.FfiKoffiVersionMismatch
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** Structural shape tests for [[KoffiFacade]] + behavioural tests for [[KoffiAbiProbe]].
  *
  * The koffi npm package is not installed in the test environment; the Scala.js linker refuses to include any reachable call that
  * transitively imports the `koffi` module. For [[KoffiFacade]] itself the spec therefore exercises only what can be validated without
  * linking into `Koffi`:
  *
  *   - [[KoffiFn]] is a usable case class (fields + equality).
  *   - The types on [[KoffiFacade]] are declared (`classOf[KoffiFacade.OutHolder]` resolves; [[KoffiFn]] is a public case class).
  *
  * Runtime semantics, `outInt → decode`, `register → invoke → unregister`, `errno`, etc., are validated in scripted integration
  * tests with a real Node + koffi install. The Scala method signatures themselves are compile-time enforced at every call site in the
  * generated emitter output (see `JsEmitterSpec`).
  *
  * The [[KoffiAbiProbe]] suite drives [[KoffiAbiProbe.probe]] directly with a `sjs.Dynamic.literal` stand-in for koffi. `probe` never calls
  * back into the real `Koffi` object, so these tests run without a koffi install.
  */
class KoffiFacadeTest extends Test:

    "KoffiFn" - {
        "carries scalaName, cSymbol, result, and args" in {
            val fn = KoffiFn("tcpClose", "tcp_close", "void", Seq[sjs.Any]("int".asInstanceOf[sjs.Any]))
            assert(fn.scalaName == "tcpClose")
            assert(fn.cSymbol == "tcp_close")
            assert(fn.result == "void")
            assert(fn.args.size == 1)
            // args are `js.Any` (opaque); comparing via toString is the only Scala-level witness for string-typed entries.
            assert(fn.args.head.toString == "int")
        }

        "case-class equality is structural" in {
            val a = KoffiFn("x", "y", "int", Seq[sjs.Any]("int".asInstanceOf[sjs.Any]))
            val b = KoffiFn("x", "y", "int", Seq[sjs.Any]("int".asInstanceOf[sjs.Any]))
            assert(a == b)
        }

        "supports varied type signatures" in {
            val fns = Seq(
                KoffiFn("add", "add_ints", "int", Seq[sjs.Any]("int".asInstanceOf[sjs.Any], "int".asInstanceOf[sjs.Any])),
                KoffiFn("now", "now_ms", "int64", Seq.empty[sjs.Any]),
                KoffiFn("greet", "greet_s", "void", Seq[sjs.Any]("string".asInstanceOf[sjs.Any])),
                KoffiFn(
                    "tcpRead",
                    "tcp_read",
                    "int",
                    Seq[sjs.Any]("int".asInstanceOf[sjs.Any], "void*".asInstanceOf[sjs.Any], "int".asInstanceOf[sjs.Any])
                )
            )
            assert(fns.size == 4)
            assert(fns.map(_.result) == Seq("int", "int64", "void", "int"))
        }
    }

    "KoffiFacade types declared" - {
        "OutHolder is a public nested type" in {
            // classOf[...] does not require the linker to instantiate the class. It only requires the metadata to exist; if OutHolder
            // were removed from KoffiFacade this would fail to compile.
            val c: Class[KoffiFacade.OutHolder] = classOf[KoffiFacade.OutHolder]
            assert((c: AnyRef) != null)
        }

        "KoffiFn fields have the expected Scala-level types" in {
            // Compile-time: declaring these vals locks in the field types on KoffiFn.
            val fn: KoffiFn         = KoffiFn("n", "s", "r", Seq[sjs.Any]("a".asInstanceOf[sjs.Any]))
            val _scalaName: String  = fn.scalaName
            val _cSymbol: String    = fn.cSymbol
            val _result: String     = fn.result
            val _args: Seq[sjs.Any] = fn.args
            val _tuple: (String, String, String, Seq[sjs.Any]) =
                (_scalaName, _cSymbol, _result, _args)
            assert(_tuple._1 == "n")
        }
    }

    "KoffiAbiProbe" - {

        // Helper: build a synthetic koffi literal that carries the given version plus a function for every required method. Tests
        // selectively drop fields to exercise failure modes.
        def koffiLike(version: sjs.Any, drop: Set[String] = Set.empty): sjs.Dynamic =
            val lit = sjs.Dynamic.literal()
            if !sjs.isUndefined(version) then lit.updateDynamic("version")(version)
            KoffiAbiProbe.RequiredMethods.foreach { m =>
                if !drop.contains(m) then
                    // A `() => ()` cast to js.Function is enough, probe only checks typeof === "function".
                    val fn: sjs.Function0[Unit] = () => ()
                    lit.updateDynamic(m)(fn.asInstanceOf[sjs.Any])
            }
            lit
        end koffiLike

        "accepts koffi 2.7.0 with every required method present" in {
            // `probe` returns Unit and throws FfiKoffiVersionMismatch on reject; returning normally is the accept signal.
            KoffiAbiProbe.probe(koffiLike("2.7.0"))
            succeed
        }

        "accepts koffi 2.7.9 (current pinned patch)" in {
            KoffiAbiProbe.probe(koffiLike("2.7.9"))
            succeed
        }

        "accepts koffi 2.8.x (minor bump within the ^2.7 range)" in {
            KoffiAbiProbe.probe(koffiLike("2.8.1"))
            succeed
        }

        "accepts koffi 2.12.0 (minor ≥ 7)" in {
            KoffiAbiProbe.probe(koffiLike("2.12.0"))
            succeed
        }

        "accepts koffi 2.7.0-rc.1 (prerelease suffix on supported core)" in {
            KoffiAbiProbe.probe(koffiLike("2.7.0-rc.1"))
            succeed
        }

        "rejects koffi 2.6.x (too old, below ^2.7 floor)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("2.6.5"))
            }
            assert(ex.getMessage.contains("2.6.5"))
            assert(ex.getMessage.contains("^2.7"))
        }

        "rejects koffi 3.0.0 (major above range)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("3.0.0"))
            }
            assert(ex.getMessage.contains("3.0.0"))
            assert(ex.getMessage.contains("^2.7"))
        }

        "rejects koffi 1.x (major below range)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("1.99.0"))
            }
            assert(ex.getMessage.contains("1.99.0"))
        }

        "rejects koffi with missing .version field" in {
            // no version property at all
            val lit = sjs.Dynamic.literal()
            KoffiAbiProbe.RequiredMethods.foreach { m =>
                val fn: sjs.Function0[Unit] = () => ()
                lit.updateDynamic(m)(fn.asInstanceOf[sjs.Any])
            }
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(lit)
            }
            assert(ex.getMessage.contains("unknown"))
            assert(ex.getMessage.contains("^2.7"))
        }

        "rejects koffi where .version is not a string (koffi shape drift)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike(42))
            }
            assert(ex.getMessage.contains("unknown"))
        }

        "rejects koffi where .version is a garbled string" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("not.a.version"))
            }
            assert(ex.getMessage.contains("not.a.version"))
        }

        "rejects koffi missing `register` (callback API drift)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("2.7.9", drop = Set("register")))
            }
            assert(ex.getMessage.contains("register"))
            assert(ex.getMessage.contains("2.7.9"))
        }

        "rejects koffi missing `struct` (struct API drift)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("2.7.9", drop = Set("struct")))
            }
            assert(ex.getMessage.contains("struct"))
        }

        "rejects koffi missing `as` (F8b variadic pin helper)" in {
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(koffiLike("2.7.9", drop = Set("as")))
            }
            assert(ex.getMessage.contains("as"))
        }

        "rejects koffi missing each individual required method by name" in {
            // Covers load, errno, proto, pointer, unregister, pack, union, the remainder not called out above.
            val targeted = Seq("load", "errno", "proto", "pointer", "unregister", "pack", "union")
            targeted.foreach { m =>
                val ex = intercept[FfiKoffiVersionMismatch] {
                    KoffiAbiProbe.probe(koffiLike("2.7.9", drop = Set(m)))
                }
                assert(ex.getMessage.contains(m))
            }
        }

        "rejects koffi where a required method is present but not a function" in {
            val lit = sjs.Dynamic.literal()
            lit.updateDynamic("version")("2.7.9")
            KoffiAbiProbe.RequiredMethods.foreach { m =>
                if m == "register" then lit.updateDynamic(m)("not a function".asInstanceOf[sjs.Any])
                else
                    val fn: sjs.Function0[Unit] = () => ()
                    lit.updateDynamic(m)(fn.asInstanceOf[sjs.Any])
            }
            val ex = intercept[FfiKoffiVersionMismatch] {
                KoffiAbiProbe.probe(lit)
            }
            assert(ex.getMessage.contains("register"))
        }

        "RequiredMethods covers the full kyo-ffi call surface" in {
            // Lockstep guard: if kyo-ffi starts calling a new koffi export at runtime, RequiredMethods must be updated in the same PR or
            // this assertion makes the omission visible.
            val expected = Seq(
                "load",
                "errno",
                "proto",
                "pointer",
                "register",
                "unregister",
                "struct",
                "pack",
                "union",
                "as"
            )
            assert(KoffiAbiProbe.RequiredMethods.toSet == expected.toSet)
        }

        "FfiKoffiVersionMismatch is a subclass of FfiUnsupported (shared catch surface)" in {
            val ex: kyo.ffi.FfiUnsupported = new FfiKoffiVersionMismatch("msg")
            assert(ex.getMessage == "msg")
        }
    }
end KoffiFacadeTest
