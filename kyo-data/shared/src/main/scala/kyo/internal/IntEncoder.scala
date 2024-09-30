package kyo.internal

object IntEncoder:
    private val charsStart    = ' '
    private val charsEnd      = '~'
    private val charsReserved = Set('[', ']', '(', ')', ';', ',')

    private val table =
        val array = new Array[Int](charsEnd - charsStart + 1)
        var char  = charsStart
        var value = 0
        while char <= charsEnd do
            if !charsReserved.contains(char) then
                array(char - charsStart) = value
                value += 1
            else
                array(char - charsStart) = -1
            end if
            char = (char + 1).toChar
        end while
        array
    end table

    def encode(i: Int): Char =
        val idx = table.indexOf(i)
        if idx < 0 || i < 0 then
            throw new Exception(s"Encoded tag 'Int($i)' exceeds supported range: ${table(0)} to ${table(table.length - 1)}")
        (idx + charsStart).toChar
    end encode

    def decode(c: Char): Int =
        table(c - charsStart)

    def encodeHash(hash: Int): Char =
        encode(Math.abs(hash) % table(table.length - 1))

end IntEncoder
