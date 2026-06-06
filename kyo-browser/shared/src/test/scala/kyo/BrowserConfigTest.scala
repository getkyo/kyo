package kyo

class BrowserConfigTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- Browser.LaunchConfig.default values ----

    "LaunchConfig.default has expected executable" in {
        assert(Browser.LaunchConfig.default.executable == "chromium")
    }

    "LaunchConfig.default is headless" in {
        assert(Browser.LaunchConfig.default.headless == true)
    }

    "LaunchConfig.default has expected launchTimeout" in {
        assert(Browser.LaunchConfig.default.launchTimeout == 90.seconds)
    }

    // ---- Browser.SessionConfig.default values ----

    "SessionConfig.default has expected retrySchedule" in {
        assert(Browser.SessionConfig.default.retrySchedule == Schedule.fixed(100.millis).maxDuration(8.seconds))
    }

    "default retry schedule has a finite max duration" in {
        // The show string includes ".maxDuration(" when a MaxDuration combinator is present.
        assert(
            Browser.SessionConfig.default.retrySchedule.show.contains("maxDuration"),
            s"expected retrySchedule to contain maxDuration but got: ${Browser.SessionConfig.default.retrySchedule.show}"
        )
    }

    "SessionConfig.default has expected loadSchedule" in {
        assert(Browser.SessionConfig.default.loadSchedule == Schedule.fixed(100.millis).maxDuration(5.seconds))
    }

    "SessionConfig.default has expected mutationQuiescenceWindow" in {
        assert(Browser.SessionConfig.default.mutationQuiescenceWindow == 50.millis)
    }

    "SessionConfig.default has expected mutationSettlementTimeout" in {
        assert(Browser.SessionConfig.default.mutationSettlementTimeout == 2.seconds)
    }

    "SessionConfig.default has expected mutationFirstMutationGrace" in {
        assert(Browser.SessionConfig.default.mutationFirstMutationGrace == 100.millis)
    }

    "SessionConfig.default has expected networkIdleWindow" in {
        assert(Browser.SessionConfig.default.networkIdleWindow == 500.millis)
    }

    // ---- Integration: retry-schedule budget ----

    "retrying assertion on nonexistent element aborts within the configured budget" in {
        withBrowser {
            onPage("<div>nothing here</div>") {
                val fastSchedule  = Schedule.fixed(50.millis).maxDuration(500.millis)
                val neverSelector = Browser.Selector.id("nonexistent-element-12345")
                val start         = java.lang.System.currentTimeMillis()
                Browser.withConfig(_.retrySchedule(fastSchedule)) {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(neverSelector)
                    }
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) =>
                            assert(elapsed < 5000L, s"expected abort within budget, took ${elapsed}ms")
                        case other => fail(s"expected BrowserElementNotFoundException, got $other")
                    end match
                }
            }
        }
    }

    "assertExists with custom retry schedule honors that schedule's maxDuration" in {
        withBrowser {
            onPage("<div>nothing here</div>") {
                val fastSchedule  = Schedule.fixed(50.millis).maxDuration(200.millis)
                val neverSelector = Browser.Selector.id("nonexistent-xyz-99999")
                val start         = java.lang.System.currentTimeMillis()
                Browser.withConfig(_.retrySchedule(fastSchedule)) {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(neverSelector)
                    }
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) =>
                            assert(elapsed < 3000L, s"custom schedule maxDuration should fire within a reasonable bound, took ${elapsed}ms")
                        case other => fail(s"expected BrowserElementNotFoundException, got $other")
                    end match
                }
            }
        }
    }

    // ---- LaunchConfig builder methods ----

    "executable returns updated launch config" in {
        val cfg = Browser.LaunchConfig.default.executable("/usr/bin/chromium-browser")
        assert(cfg.executable == "/usr/bin/chromium-browser")
        assert(cfg.headless == Browser.LaunchConfig.default.headless)
    }

    "headless returns updated launch config" in {
        val cfg = Browser.LaunchConfig.default.headless(false)
        assert(cfg.headless == false)
        assert(cfg.executable == Browser.LaunchConfig.default.executable)
    }

    "launchTimeout returns updated launch config" in {
        val cfg = Browser.LaunchConfig.default.launchTimeout(5.seconds)
        assert(cfg.launchTimeout == 5.seconds)
    }

    // ---- SessionConfig builder methods ----

    "retrySchedule returns updated session config" in {
        val s   = Schedule.fixed(10.millis).take(5)
        val cfg = Browser.SessionConfig.default.retrySchedule(s)
        assert(cfg.retrySchedule == s)
        assert(cfg.loadSchedule == Browser.SessionConfig.default.loadSchedule)
    }

    "loadSchedule returns updated session config" in {
        val s   = Schedule.fixed(200.millis).maxDuration(10.seconds)
        val cfg = Browser.SessionConfig.default.loadSchedule(s)
        assert(cfg.loadSchedule == s)
        assert(cfg.retrySchedule == Browser.SessionConfig.default.retrySchedule)
    }

    "mutationQuiescenceWindow returns updated session config" in {
        val cfg = Browser.SessionConfig.default.mutationQuiescenceWindow(25.millis)
        assert(cfg.mutationQuiescenceWindow == 25.millis)
    }

    "mutationSettlementTimeout returns updated session config" in {
        val cfg = Browser.SessionConfig.default.mutationSettlementTimeout(500.millis)
        assert(cfg.mutationSettlementTimeout == 500.millis)
    }

    // ---- LaunchConfig factory methods ----

    "LaunchConfig.chromium uses chromium as executable" in {
        val cfg = Browser.LaunchConfig.chromium()
        assert(cfg.executable == "chromium")
        assert(cfg.headless == true)
    }

    "LaunchConfig.chromium with custom path sets executable" in {
        val cfg = Browser.LaunchConfig.chromium("/opt/chromium/chromium")
        assert(cfg.executable == "/opt/chromium/chromium")
    }

    "LaunchConfig.chrome uses google-chrome as executable" in {
        val cfg = Browser.LaunchConfig.chrome()
        assert(cfg.executable == "google-chrome")
    }

    "LaunchConfig.chrome with custom path sets executable" in {
        val cfg = Browser.LaunchConfig.chrome("/usr/bin/google-chrome-stable")
        assert(cfg.executable == "/usr/bin/google-chrome-stable")
    }

    // ---- withConfig scoping (integration) ----

    "currentConfig returns default session config at outermost scope" in {
        withBrowser {
            Browser.configLocal.use { cfg =>
                assert(cfg.retrySchedule == Browser.SessionConfig.default.retrySchedule)
                assert(cfg.loadSchedule == Browser.SessionConfig.default.loadSchedule)
            }
        }
    }

    "withConfig installs session config for the duration of the block" in {
        val custom = Browser.SessionConfig.default.retrySchedule(Schedule.fixed(10.millis).take(2))
        withBrowser {
            Browser.withConfig(custom) {
                Browser.configLocal.use { cfg =>
                    assert(cfg.retrySchedule == Schedule.fixed(10.millis).take(2))
                }
            }
        }
    }

    "withConfig restores outer session config after the block" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(Schedule.fixed(10.millis).take(2))) {
                Sync.defer(())
            }.andThen {
                Browser.configLocal.use { cfg =>
                    assert(cfg.retrySchedule == Browser.SessionConfig.default.retrySchedule)
                }
            }
        }
    }

    "nested withConfig applies innermost session config" in {
        val outer = Schedule.fixed(30.millis).take(4)
        val inner = Schedule.fixed(10.millis).take(2)
        withBrowser {
            Browser.withConfig(_.retrySchedule(outer)) {
                Browser.withConfig(_.retrySchedule(inner)) {
                    Browser.configLocal.use { cfg =>
                        assert(cfg.retrySchedule == inner)
                    }
                }
            }
        }
    }

    "nested withConfig restores outer session config after inner block" in {
        val outer = Schedule.fixed(30.millis).take(4)
        val inner = Schedule.fixed(10.millis).take(2)
        withBrowser {
            Browser.withConfig(_.retrySchedule(outer)) {
                Browser.withConfig(_.retrySchedule(inner)) {
                    Sync.defer(())
                }.andThen {
                    Browser.configLocal.use { cfg =>
                        assert(cfg.retrySchedule == outer)
                    }
                }
            }
        }
    }

    "withConfig(f) applies f to current session config, not default" in {
        val base = Browser.SessionConfig.default.mutationQuiescenceWindow(25.millis)
        withBrowser {
            Browser.withConfig(base) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(10.millis).take(2))) {
                    Browser.configLocal.use { cfg =>
                        // retrySchedule updated, other fields preserved from base
                        assert(cfg.retrySchedule == Schedule.fixed(10.millis).take(2))
                        assert(cfg.mutationQuiescenceWindow == 25.millis)
                    }
                }
            }
        }
    }

    "retrySchedule affects retry count for non-existent element" in {
        withBrowser {
            onPage("<div>nothing</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(Browser.Selector.css("#never"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.selector.contains("#never"))
                        case other => fail(s"expected BrowserElementNotFoundException, got $other")
                }
            }
        }
    }

    // ---- builder pure tests for additional session config fields ----

    // builder propagates `networkIdleWindow` into `SessionConfig` and preserves siblings.
    "networkIdleWindow returns updated session config" in {
        val cfg = Browser.SessionConfig.default.networkIdleWindow(750.millis)
        assert(cfg.networkIdleWindow == 750.millis, s"expected 750ms but got ${cfg.networkIdleWindow}")
        // Other fields preserved.
        assert(cfg.retrySchedule == Browser.SessionConfig.default.retrySchedule)
        assert(cfg.loadSchedule == Browser.SessionConfig.default.loadSchedule)
        assert(cfg.mutationQuiescenceWindow == Browser.SessionConfig.default.mutationQuiescenceWindow)
    }

    // builder propagates `mutationFirstMutationGrace` into `SessionConfig` and preserves siblings.
    "mutationFirstMutationGrace returns updated session config" in {
        val cfg = Browser.SessionConfig.default.mutationFirstMutationGrace(250.millis)
        assert(cfg.mutationFirstMutationGrace == 250.millis, s"expected 250ms but got ${cfg.mutationFirstMutationGrace}")
        // Other fields preserved.
        assert(cfg.mutationQuiescenceWindow == Browser.SessionConfig.default.mutationQuiescenceWindow)
        assert(cfg.mutationSettlementTimeout == Browser.SessionConfig.default.mutationSettlementTimeout)
        assert(cfg.networkIdleWindow == Browser.SessionConfig.default.networkIdleWindow)
    }

    // ---- setup-failure surfacing ----

    "BrowserSetupException is raised when launch executable is missing" in {
        // The setup channel (NOT the connection channel) must surface the failure: the executable does not exist,
        // so no CDP session ever exists to drop.
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                Browser.run(Browser.LaunchConfig.chromium("/no/such/binary").launchTimeout(2.seconds)) {
                    Kyo.unit
                }
            }
        }.map {
            case Result.Success(_)                               => fail("expected setup failure for missing executable")
            case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("/no/such/binary"))
            case Result.Failure(other) =>
                fail(s"expected BrowserSetupFailedException but got ${other.getClass.getName}: $other")
            case Result.Panic(ex) => fail(s"expected Failure, got Panic: ${ex.getMessage}")
        }
    }

end BrowserConfigTest
