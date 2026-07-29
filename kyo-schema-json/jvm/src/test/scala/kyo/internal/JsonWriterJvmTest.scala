package kyo.internal

class JsonWriterJvmTest extends kyo.test.Test[Any]:

    "resultString works without opening java.lang" in {
        assert(!classOf[String].getModule.isOpen("java.lang", getClass.getModule))

        val ascii = JsonWriter()
        ascii.string("hello")
        assert(ascii.resultString == "\"hello\"")

        val utf8 = JsonWriter()
        utf8.string("héllo")
        assert(utf8.resultString == "\"héllo\"")
    }
end JsonWriterJvmTest
