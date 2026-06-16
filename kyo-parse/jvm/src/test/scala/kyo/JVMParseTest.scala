package kyo

class JVMParseTest extends ParseTest(1 << 10) // doesn't work for 1 << 14, loop in Parse.runWith
