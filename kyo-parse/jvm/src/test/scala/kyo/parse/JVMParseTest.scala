package kyo.parse

class JVMParseTest extends ParseTest(1 << 13) // doesn't work for 1 << 14, loop in Parse.runWith
