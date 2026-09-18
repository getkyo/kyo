package kyo.ai

import kyo.*
import kyo.ai.decider.TypeSafeDecider

class DeciderConfigTest extends kyo.test.Test[Any]:

    "Provider.all lists every decision provider, each with its backend" in {
        assert(DeciderConfig.Provider.all.map(_.name) == Chunk("typesafe"))
        assert(DeciderConfig.TypeSafe.backend eq TypeSafeDecider)
    }

    "every provider lists its entries and its default is one of them, as stable values" in {
        DeciderConfig.Provider.all.foreach { p =>
            assert(p.entries.nonEmpty, s"${p.name} must list its catalog entries")
            assert(p.entries.exists(_ eq p.default), s"${p.name}'s default must be a listed entry, the same value")
            assert(p.default eq p.default, s"${p.name}'s default is one value, not allocated per call")
            p.entries.foreach { entry =>
                assert(entry.provider eq p)
                assert(entry.apiKey.isEmpty, s"${p.name} catalog entries are pure: key absent")
                assert(entry.apiUrl == p.baseUrl)
                assert(
                    entry.timeout.isEmpty && entry.meter.isEmpty && entry.retrySchedule.isEmpty,
                    s"${p.name} entries inherit transport settings"
                )
            }
        }
        succeed
    }

    "the TypeSafe provider names its endpoint, key variable and models" in {
        assert(DeciderConfig.TypeSafe.name == "typesafe")
        assert(DeciderConfig.TypeSafe.keyName == "TYPESAFE_API_KEY")
        assert(DeciderConfig.TypeSafe.baseUrl == "https://api.typesafe.ai/v1")
        assert(DeciderConfig.TypeSafe.default eq DeciderConfig.TypeSafe.jevLatest)
        assert(DeciderConfig.TypeSafe.jevLatest.modelName == "jev-latest")
        assert(DeciderConfig.TypeSafe.jevPreview.modelName == "jev-preview")
    }

    "builders are copy-on-write" in {
        val base    = DeciderConfig.TypeSafe.default
        val changed = base.apiKey("k").modelName("jev-preview").apiUrl("http://localhost:1/v1")
        assert(changed.apiKey == Present("k"))
        assert(changed.modelName == "jev-preview")
        assert(changed.apiUrl == "http://localhost:1/v1")
        assert(base.apiKey.isEmpty && base.modelName == "jev-latest")
    }

    "transport builders set the decider's own settings, absent by default" in {
        val schedule = Schedule.fixed(1.second).take(2)
        val changed  = DeciderConfig.TypeSafe.default.timeout(3.seconds).meter(Meter.Noop).retrySchedule(schedule)
        assert(changed.timeout == Present(3.seconds))
        assert(changed.meter.exists(_ eq Meter.Noop))
        assert(changed.retrySchedule == Present(schedule))
        assert(DeciderConfig.TypeSafe.default.timeout.isEmpty)
        assert(DeciderConfig.TypeSafe.default.meter.isEmpty)
        assert(DeciderConfig.TypeSafe.default.retrySchedule.isEmpty)
    }

    "Config carries no decider by default, sets one with the builder, and clears it with Absent" in {
        val cfg = Config.Anthropic.default
        assert(cfg.decider.isEmpty)
        assert(cfg.decider(DeciderConfig.TypeSafe.jevPreview).decider == Present(DeciderConfig.TypeSafe.jevPreview))
        assert(cfg.decider(DeciderConfig.TypeSafe.jevPreview).decider(Absent).decider.isEmpty)
    }

    "credentialed fills the decider key from a system property first" in {
        val sys = System(new TestUnsafeSystem(
            envVars = Map("TYPESAFE_API_KEY" -> "from-env"),
            properties = Map("TYPESAFE_API_KEY" -> "from-prop")
        ))
        System.let(sys)(Config.credentialed(Config.Anthropic.default.decider(DeciderConfig.TypeSafe.default))).map { cfg =>
            assert(cfg.decider.flatMap(_.apiKey) == Present("from-prop"))
        }
    }

    "credentialed falls back to the environment" in {
        val sys = System(new TestUnsafeSystem(envVars = Map("TYPESAFE_API_KEY" -> "from-env")))
        System.let(sys)(Config.credentialed(Config.Anthropic.default.decider(DeciderConfig.TypeSafe.default))).map { cfg =>
            assert(cfg.decider.flatMap(_.apiKey) == Present("from-env"))
        }
    }

    "credentialed leaves the key absent when nothing is set and keeps an explicit key" in {
        val sys = System(new TestUnsafeSystem())
        for
            absent <- System.let(sys)(Config.credentialed(Config.Anthropic.default.decider(DeciderConfig.TypeSafe.default)))
            kept   <- System.let(sys)(Config.credentialed(Config.Anthropic.default.decider(DeciderConfig.TypeSafe.default.apiKey("mine"))))
        yield
            assert(absent.decider.exists(_.apiKey.isEmpty))
            assert(kept.decider.flatMap(_.apiKey) == Present("mine"))
        end for
    }

    "credentialed reads no decider key when no decider is set" in {
        val sys = System(new TestUnsafeSystem(properties = Map("TYPESAFE_API_KEY" -> "never")))
        System.let(sys)(Config.credentialed(Config.Anthropic.default)).map { cfg =>
            assert(cfg.decider.isEmpty)
        }
    }

    "credentialed keeps an explicit provider key and org, and fills absent ones" in {
        val sys = System(new TestUnsafeSystem(properties = Map("OPENAI_API_KEY" -> "from-prop", "OPENAI_API_KEY_ORG" -> "org-prop")))
        for
            kept   <- System.let(sys)(Config.credentialed(Config.OpenAI.default.apiKey("mine").apiOrg("my-org")))
            filled <- System.let(sys)(Config.credentialed(Config.OpenAI.default))
        yield
            assert(kept.apiKey == Present("mine"))
            assert(kept.apiOrg == Present("my-org"))
            assert(filled.apiKey == Present("from-prop"))
            assert(filled.apiOrg == Present("org-prop"))
        end for
    }

    "Config.default enables the decider whose key is present, credentialed, and none without a key" in {
        val both = System(new TestUnsafeSystem(properties = Map("OPENAI_API_KEY" -> "k", "TYPESAFE_API_KEY" -> "t")))
        val one  = System(new TestUnsafeSystem(properties = Map("OPENAI_API_KEY" -> "k")))
        for
            withKey    <- System.let(both)(Config.default)
            withoutKey <- System.let(one)(Config.default)
        yield
            assert(withKey.provider.name == "OpenAI")
            assert(withKey.apiKey == Present("k"))
            assert(withKey.decider.exists(_.provider eq DeciderConfig.TypeSafe), s"decider: ${withKey.decider}")
            assert(withKey.decider.flatMap(_.apiKey) == Present("t"))
            assert(withoutKey.provider.name == "OpenAI")
            assert(withoutKey.decider.isEmpty, "no decider key, the completion provider decides")
        end for
    }

    private class TestUnsafeSystem(
        envVars: Map[String, String] = Map.empty,
        properties: Map[String, String] = Map.empty
    ) extends System.Unsafe:
        def env(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(envVars.get(name))
        def property(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(properties.get(name))
        def lineSeparator()(using AllowUnsafe): String      = "\n"
        def userName()(using AllowUnsafe): String           = "test"
        def operatingSystem()(using AllowUnsafe): System.OS = System.OS.Unknown
        def architecture()(using AllowUnsafe): System.Arch  = System.Arch.Unknown
        def availableProcessors()(using AllowUnsafe): Int   = 1
    end TestUnsafeSystem

end DeciderConfigTest
