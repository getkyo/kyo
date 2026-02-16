package java.security

import scala.annotation.tailrec
import scala.scalanative.libc.stdio.FILE
import scala.scalanative.libc.stdio.fclose
import scala.scalanative.libc.stdio.fopen
import scala.scalanative.libc.stdio.fread
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

class SecureRandom extends java.util.Random(0L):

    override def nextBytes(bytes: Array[Byte]): Unit =
        val len = bytes.length
        if len > 0 then
            val fp = fopen(c"/dev/urandom", c"r")
            if fp == null then
                throw new RuntimeException("Failed to open /dev/urandom")
            try
                Zone {
                    val buf  = alloc[Byte](len)
                    val read = fread(buf, 1.toCSize, len.toCSize, fp)
                    if read != len.toCSize then
                        throw new RuntimeException("Failed to read from /dev/urandom")
                    @tailrec def loop(i: Int): Unit =
                        if i < len then
                            bytes(i) = buf(i)
                            loop(i + 1)
                    loop(0)
                }
            finally
                val _ = fclose(fp) // can't use discard() — not in kyo package
            end try
        end if
    end nextBytes

    override protected def next(bits: Int): Int =
        val bytes = new Array[Byte](4)
        nextBytes(bytes)
        val n = ((bytes(0) & 0xff) << 24) |
            ((bytes(1) & 0xff) << 16) |
            ((bytes(2) & 0xff) << 8) |
            (bytes(3) & 0xff)
        n >>> (32 - bits)
    end next

    override def setSeed(seed: Long): Unit = ()
end SecureRandom
