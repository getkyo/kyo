package kyo

class BrowserConsoleTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- consoleLogs ----

    "consoleLogs captures console.log messages" in {
        withBrowser {
            onPage("<html><body><div id='content'>hello</div></body></html>") {
                // Log some messages and read them back
                Browser.eval("console.log('message1'); console.log('message2'); 'ok'").andThen {
                    Browser.consoleLogs.map { logs =>
                        assert(logs.size == 2, s"Expected 2 log messages but got ${logs.size}: $logs")
                        assert(logs(0).message == "message1", s"Expected 'message1' but got '${logs(0).message}'")
                        assert(logs(0).level == Browser.ConsoleLevel.Log, s"Expected level=Log but got '${logs(0).level}'")
                        assert(logs(1).message == "message2", s"Expected 'message2' but got '${logs(1).message}'")
                        assert(logs(1).level == Browser.ConsoleLevel.Log, s"Expected level=Log but got '${logs(1).level}'")
                    }
                }
            }
        }
    }

    "consoleLogs clears after reading" in {
        withBrowser {
            onPage("<html><body><div>test</div></body></html>") {
                Browser.eval("console.log('first'); 'ok'").andThen {
                    // Read and clear
                    Browser.consoleLogs.map { logs1 =>
                        assert(logs1.size == 1, s"Expected 1 log but got ${logs1.size}")
                    }.andThen {
                        // Should be empty now
                        Browser.consoleLogs.map { logs2 =>
                            assert(logs2.isEmpty, s"Expected empty logs after clear but got ${logs2.size}")
                        }
                    }
                }
            }
        }
    }

    "consoleLogs returns the first console.log emitted at page load" in {
        withBrowser {
            // The script runs immediately at page load, before any consoleLogs call
            onPage("""<html><body>
                <script>console.log('at-load-message');</script>
            </body></html>""") {
                Browser.consoleLogs.map { logs =>
                    assert(
                        logs.exists(_.message == "at-load-message"),
                        s"Expected 'at-load-message' in first consoleLogs call but got: $logs"
                    )
                }
            }
        }
    }

    "consoleLogs across two reads returns disjoint batches and clears the buffer between them" in {
        withBrowser {
            onPage("<html><body><div>test</div></body></html>") {
                // Drain any logs from page load
                Browser.consoleLogs.unit.andThen {
                    Browser.eval("console.log('batch-one'); 'ok'").andThen {
                        Browser.consoleLogs.map { batch1 =>
                            assert(batch1.exists(_.message == "batch-one"), s"Expected 'batch-one' in first batch but got: $batch1")
                        }.andThen {
                            Browser.eval("console.log('batch-two'); 'ok'").andThen {
                                Browser.consoleLogs.map { batch2 =>
                                    assert(
                                        batch2.exists(_.message == "batch-two"),
                                        s"Expected 'batch-two' in second batch but got: $batch2"
                                    )
                                    assert(
                                        !batch2.exists(_.message == "batch-one"),
                                        s"Expected 'batch-one' to be cleared before second read but got: $batch2"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "consoleLogs captures warn and error entries in addition to log" in {
        withBrowser {
            onPage("<html><body><div>test</div></body></html>") {
                // Drain any logs from page load
                Browser.consoleLogs.unit.andThen {
                    Browser.eval("console.log('info-msg'); console.warn('warn-msg'); console.error('error-msg'); 'ok'").andThen {
                        Browser.consoleLogs.map { logs =>
                            assert(
                                logs.exists(m => m.level == Browser.ConsoleLevel.Log && m.message == "info-msg"),
                                s"Expected (Log, 'info-msg') in logs but got: $logs"
                            )
                            assert(
                                logs.exists(m => m.level == Browser.ConsoleLevel.Warn && m.message == "warn-msg"),
                                s"Expected (Warn, 'warn-msg') in logs but got: $logs"
                            )
                            assert(
                                logs.exists(m => m.level == Browser.ConsoleLevel.Error && m.message == "error-msg"),
                                s"Expected (Error, 'error-msg') in logs but got: $logs"
                            )
                        }
                    }
                }
            }
        }
    }

    "consoleLogs distinguishes log, warn, error levels with correct ordering" in {
        // ConsoleMessage is a typed value with an explicit level field, not a prefix-smuggled string.
        withBrowser {
            onPage("<html><body><div>levels</div></body></html>") {
                // Drain page-load chatter, then emit a known sequence.
                Browser.consoleLogs.unit.andThen {
                    Browser.eval("console.log('hi'); console.warn('warn-msg'); console.error('err-msg'); 'ok'").andThen {
                        Browser.consoleLogs.map { logs =>
                            assert(logs.size == 3, s"Expected exactly 3 entries but got ${logs.size}: $logs")
                            assert(logs(0).level == Browser.ConsoleLevel.Log && logs(0).message == "hi")
                            assert(logs(1).level == Browser.ConsoleLevel.Warn && logs(1).message == "warn-msg")
                            assert(logs(2).level == Browser.ConsoleLevel.Error && logs(2).message == "err-msg")
                        }
                    }
                }
            }
        }
    }

    "consoleLogs(Level.Error) filters the drain to errors only" in {
        withBrowser {
            onPage("<html><body><div>filter</div></body></html>") {
                Browser.consoleLogs.unit.andThen {
                    Browser.eval("console.log('l1'); console.warn('w1'); console.error('e1'); 'ok'").andThen {
                        Browser.consoleLogs(Browser.ConsoleLevel.Error).map { errors =>
                            assert(errors.size == 1, s"Expected 1 error-level entry but got ${errors.size}: $errors")
                            assert(errors.head.message == "e1", s"Expected 'e1' but got '${errors.head.message}'")
                            assert(errors.head.level == Browser.ConsoleLevel.Error)
                        }
                    }
                }
            }
        }
    }

    "consoleLogs inside withPopup captures page-load messages from the popup tab" in {
        withBrowser {
            onPage("""<html><body>
                <button id='openBtn' onclick="window.open('about:blank', '_blank');">Open</button>
            </body></html>""") {
                Browser.withPopup() {
                    Browser.click(Browser.Selector.css("#openBtn"))
                } {
                    onPage("""<html><body>
                        <script>console.log('popup-marker-load');</script>
                    </body></html>""") {
                        Browser.consoleLogs.map { logs =>
                            assert(
                                logs.exists(_.message == "popup-marker-load"),
                                s"Expected 'popup-marker-load' in popup consoleLogs on first call but got: $logs"
                            )
                        }
                    }
                }
            }
        }
    }

    "consoleLogs inside withNewTab captures page-load messages from the new tab" in {
        withBrowser {
            onPage("<html><body><div>parent</div></body></html>") {
                Browser.withNewTab {
                    onPage("""<html><body>
                        <script>console.log('newtab-marker-load');</script>
                    </body></html>""") {
                        Browser.consoleLogs.map { logs =>
                            assert(
                                logs.exists(_.message == "newtab-marker-load"),
                                s"Expected 'newtab-marker-load' in new-tab consoleLogs on first call but got: $logs"
                            )
                        }
                    }
                }
            }
        }
    }

    "consoleLogs inside withFork captures page-load messages from the forked tab" in {
        withBrowserOnLocalhost {
            Browser.withFork {
                onPage("""<html><body>
                    <script>console.log('clone-marker-load');</script>
                </body></html>""") {
                    Browser.consoleLogs.map { logs =>
                        assert(
                            logs.exists(_.message == "clone-marker-load"),
                            s"Expected 'clone-marker-load' in clone consoleLogs on first call but got: $logs"
                        )
                    }
                }
            }
        }
    }

end BrowserConsoleTest
