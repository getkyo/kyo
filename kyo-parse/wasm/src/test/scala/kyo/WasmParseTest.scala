package kyo

// WASM runs on a small call stack like the JS backend, so it uses the same conservative
// parse depth as JSParseTest rather than the 1 << 13 the JVM and Native handle.
class WasmParseTest extends ParseTest(1 << 5)
