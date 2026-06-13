package kyo.ffi.codegen

import kyo.ffi.codegen.model.*

/** Allowlist warnings emitted by [[FfiGenerator.collectWarnings]] must be grouped by source trait. The user sees one `[<traitFQN>]` header
  * per binding then all that trait's warnings indented underneath, instead of a flat interleaved list.
  */
class FfiGeneratorWarningGroupTest extends kyo.test.Test[Any]:

    private def mkBlockingMethod(name: String, sym: String): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = sym,
            params = Nil,
            returnShape = ReturnShape.Primitive(TypeRef.IntT),
            blocking = false,
            hasArrayParam = false,
            callbackKind = CallbackKind.None
        )

    private def mkSyncMethod(name: String, sym: String): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = sym,
            params = Nil,
            returnShape = ReturnShape.Primitive(TypeRef.IntT),
            blocking = false,
            hasArrayParam = false,
            callbackKind = CallbackKind.None
        )

    private def mkBlockingSiblingMethod(name: String, sym: String): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = sym,
            params = Nil,
            returnShape = ReturnShape.Primitive(TypeRef.IntT),
            blocking = true,
            hasArrayParam = false,
            callbackKind = CallbackKind.None
        )

    private def mkTransientCallbackMethod(name: String, sym: String): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = sym,
            params = List(ParamSpec("cb", TypeRef.FnPtrT(Nil, TypeRef.UnitT))),
            returnShape = ReturnShape.Void,
            blocking = false,
            hasArrayParam = false,
            callbackKind = CallbackKind.Transient
        )

    private def mkTrait(fqcn: String, simple: String, methods: List[MethodSpec]): TraitSpec =
        TraitSpec(
            fqcn = fqcn,
            simpleName = simple,
            packageName = fqcn.stripSuffix("." + simple),
            library = "lib",
            methods = methods,
            structs = Nil,
            companion = None
        )

    "warnings are grouped under one header per source trait" in {
        // Two traits, each with two warnings (one blocking-allowlist + one retention-allowlist miss).
        // Output must contain a `[traitFqn]` header for each trait and only one of each header.
        val tA = mkTrait(
            "kyo.test.A",
            "A",
            List(
                mkBlockingMethod("readA", "read"),
                mkTransientCallbackMethod("registerA", "atexit")
            )
        )
        val tB = mkTrait(
            "kyo.test.B",
            "B",
            List(
                mkBlockingMethod("writeB", "write"),
                mkTransientCallbackMethod("registerB", "signal")
            )
        )

        val warnings = FfiGenerator.collectWarnings(Seq(tA, tB), FfiGenerator.Config.default)
        val joined   = warnings.mkString("\n")

        assert(joined.contains("[kyo.test.A]"))
        assert(joined.contains("[kyo.test.B]"))
        assert(joined.split("\\[kyo\\.test\\.A\\]").length == 2) // header appears exactly once
        assert(joined.split("\\[kyo\\.test\\.B\\]").length == 2)
        assert(joined.contains("readA"))
        assert(joined.contains("registerA"))
        assert(joined.contains("writeB"))
        assert(joined.contains("registerB"))
    }

    "all warnings under a header come before the next trait's header" in {
        // Order: trait A header → both A entries → trait B header → both B entries (no interleaving).
        val tA = mkTrait(
            "kyo.test.A",
            "A",
            List(
                mkBlockingMethod("readA", "read"),
                mkTransientCallbackMethod("registerA", "atexit")
            )
        )
        val tB = mkTrait(
            "kyo.test.B",
            "B",
            List(
                mkBlockingMethod("writeB", "write")
            )
        )

        val warnings = FfiGenerator.collectWarnings(Seq(tA, tB), FfiGenerator.Config.default)
        val joined   = warnings.mkString("\n")

        val aHeaderIdx   = joined.indexOf("[kyo.test.A]")
        val readAIdx     = joined.indexOf("readA")
        val registerAIdx = joined.indexOf("registerA")
        val bHeaderIdx   = joined.indexOf("[kyo.test.B]")
        val writeBIdx    = joined.indexOf("writeB")

        assert(aHeaderIdx >= 0)
        assert(readAIdx > aHeaderIdx)
        assert(registerAIdx > aHeaderIdx)
        assert(bHeaderIdx > aHeaderIdx)
        assert(readAIdx < bHeaderIdx)
        assert(registerAIdx < bHeaderIdx)
        assert(writeBIdx > bHeaderIdx)
    }

    "single trait still emits a header" in {
        val tA       = mkTrait("kyo.test.A", "A", List(mkBlockingMethod("readA", "read")))
        val warnings = FfiGenerator.collectWarnings(Seq(tA), FfiGenerator.Config.default)
        val joined   = warnings.mkString("\n")
        assert(joined.contains("[kyo.test.A]"))
        assert(joined.contains("readA"))
    }

    "no warnings → empty result" in {
        val tA = mkTrait("kyo.test.A", "A", Nil)
        assert(FfiGenerator.collectWarnings(Seq(tA), FfiGenerator.Config.default).isEmpty)
    }

    "a non-blocking binding of an allowlisted symbol is NOT warned when the same symbol has a @Ffi.blocking sibling in the trait" in {
        // `send` is blocking-allowlisted. A trait that binds it both ways (a `@Ffi.blocking send` and a synchronous `sendNow` over the same
        // `send` symbol) is the intentional synchronous-companion pattern, not the accidental annotation omission the heuristic targets, so
        // the synchronous binding must not warn. The blocking sibling carries the parking/GC contract.
        val t = mkTrait(
            "kyo.test.Sock",
            "Sock",
            List(
                mkBlockingSiblingMethod("send", "send"),
                mkSyncMethod("sendNow", "send")
            )
        )
        assert(FfiGenerator.collectWarnings(Seq(t), FfiGenerator.Config.default).isEmpty)
    }

    "a non-blocking binding of an allowlisted symbol with NO blocking sibling still warns" in {
        // The suppression is narrow: it requires a blocking sibling over the SAME symbol. A lone non-blocking `recv` (no blocking sibling)
        // is the accidental-omission case and must still warn.
        val t = mkTrait(
            "kyo.test.Sock",
            "Sock",
            List(mkSyncMethod("recv", "recv"))
        )
        val joined = FfiGenerator.collectWarnings(Seq(t), FfiGenerator.Config.default).mkString("\n")
        assert(joined.contains("recv"))
        assert(joined.contains("blocking allowlist"))
    }
end FfiGeneratorWarningGroupTest
