package examples.ledger.db

import Index.*
import examples.ledger.*
import java.lang.System as JSystem
import java.lang.reflect.Field
import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode.READ_WRITE
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import kyo.{Result as _, *}
import sun.misc.Unsafe

trait Index:

    def transaction(
        account: Int,
        amount: Int,
        desc: String
    ): Result < Sync

    def statement(
        account: Int
    ): Statement < Sync

end Index

object Index:

    val init: Index < (Env[DB.Config] & Sync) = direct {
        val cfg  = Env.get[DB.Config].now
        val file = open(cfg.workingDir + "/index.dat").now
        Sync(Live(file)).now
    }

    final class Live(file: FileChannel) extends Index:
        private val descSize           = 10
        private val transactionSize    = 32
        private val transactionHistory = 10
        private val entrySize          = Integer.BYTES * 3 + transactionSize * transactionHistory
        private val paddedEntrySize    = 1024
        private val fileSize           = paddedEntrySize * limits.size

        private val address: Long =
            val buffer       = file.map(READ_WRITE, 0, fileSize)
            val addressField = buffer.getClass().getDeclaredMethod("address");
            addressField.setAccessible(true)
            addressField.invoke(buffer).asInstanceOf[Long];
        end address

        private case class Buffers(
            descChars: Array[Char] = new Array[Char](10),
            statement: Array[Byte] = new Array[Byte](entrySize)
        )

        private val buffers = ThreadLocal.withInitial(() => Buffers())

        def transaction(account: Int, amount: Int, desc: String) =
            Sync {
                val descChars  = this.descChars(desc)
                val limit      = limits(account)
                val offset     = address + (account * paddedEntrySize)
                val timestamp  = JSystem.currentTimeMillis()
                var newBalance = 0
                while !unsafe.compareAndSwapInt(null, offset, 0, 1) do {} // busy wait
                try
                    val balance = unsafe.getInt(offset + 4)
                    newBalance = balance + amount
                    if newBalance >= -limit then
                        unsafe.putInt(offset + 4, newBalance)
                        val tail = unsafe.getInt(offset + 8)
                        unsafe.putInt(offset + 8, tail + 1)
                        val toffset = (offset + 12) + (tail % transactionHistory) * transactionSize
                        unsafe.putLong(toffset, timestamp)
                        unsafe.putInt(toffset + 8, amount)
                        unsafe.copyMemory(
                            descChars,
                            Unsafe.ARRAY_CHAR_BASE_OFFSET,
                            null,
                            toffset + 12,
                            Character.BYTES * descSize
                        )
                    end if
                finally
                    unsafe.putOrderedInt(null, offset, 0)
                end try
                if newBalance >= -limit then
                    Processed(limit, newBalance)
                else
                    Denied
                end if
            }

        private def descChars(desc: String): Array[Char] =
            val b    = buffers.get().descChars
            var i    = 0
            val size = Math.min(10, desc.size)
            while i < size do
                b(i) = desc.charAt(i)
                i += 1
            while i < descSize do
                b(i) = 0
                i += 1
            b
        end descChars

        def statement(account: Int) =
            Sync {
                val limit     = limits(account)
                val offset    = address + (account * paddedEntrySize)
                val statement = this.buffers.get().statement
                while !unsafe.compareAndSwapInt(null, offset, 0, 1) do {} // busy wait
                try
                    unsafe.copyMemory(
                        null,
                        offset,
                        statement,
                        Unsafe.ARRAY_BYTE_BASE_OFFSET,
                        entrySize
                    )
                finally
                    unsafe.putOrderedInt(null, offset, 0)
                end try
                decode(limit, statement)
            }

        private def decode(limit: Int, statement: Array[Byte]) =
            val offset       = Unsafe.ARRAY_BYTE_BASE_OFFSET
            val balance      = unsafe.getInt(statement, offset + 4)
            val tail         = unsafe.getInt(statement, offset + 8)
            val size         = Math.min(tail, transactionHistory)
            val transactions = new Array[Transaction](size)
            if size > 0 then
                var pos = (tail) % size
                var i   = 0
                while i < size do
                    val toffset   = (offset + 12) + (pos * transactionSize)
                    val timestamp = unsafe.getLong(statement, toffset)
                    val amount    = unsafe.getInt(statement, toffset + 8)
                    var descSize  = 0
                    while unsafe.getChar(
                            statement,
                            toffset + 12 + (descSize * Character.BYTES)
                        ) != 0
                    do
                        descSize += 1
                    end while
                    val descChars = new Array[Char](descSize)
                    unsafe.copyMemory(
                        statement,
                        toffset + 12,
                        descChars,
                        Unsafe.ARRAY_CHAR_BASE_OFFSET,
                        Character.BYTES * descSize
                    )
                    transactions(size - 1 - i) = new Transaction(
                        amount.abs,
                        if amount < 0 then "d" else "c",
                        Some(new String(descChars)),
                        Some(Instant.ofEpochMilli(timestamp))
                    )
                    pos = (pos + 1) % transactionHistory
                    i += 1
                end while
            end if
            Statement(
                Balance(balance, Instant.now(), limit),
                transactions.toIndexedSeq
            )
        end decode

    end Live

    private def open(filePath: String) =
        Sync {
            FileChannel
                .open(
                    Paths.get(filePath),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE
                )
        }

    private val unsafe: Unsafe =
        val f: Field = classOf[Unsafe].getDeclaredField("theUnsafe")
        f.setAccessible(true)
        f.get(null).asInstanceOf[Unsafe]
    end unsafe

    private val limits =
        List(
            Integer.MAX_VALUE, // warmup account
            100000,
            80000,
            1000000,
            10000000,
            500000
        ).toArray

end Index
