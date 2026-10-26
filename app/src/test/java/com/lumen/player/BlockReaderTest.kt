package com.lumen.player

import com.lumen.player.subtitle.ai.BlockReader
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BlockReaderTest {
    private val data = ByteArray(10_000) { (it % 251).toByte() }

    private fun reader(block: Int = 1_000) =
        BlockReader(data.size.toLong(), block) { pos -> ByteArrayInputStream(data, pos.toInt(), data.size - pos.toInt()) }

    private fun BlockReader.read(pos: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = readAt(pos + got, buf, got, size - got)
            if (n <= 0) break
            got += n
        }
        return buf.copyOf(got)
    }

    @Test fun smallForwardJumpsReuseOneConnection() {
        val r = reader()
        // 오디오만 뽑을 때처럼 조금씩 앞으로 건너뛰며 읽는다
        var pos = 0L
        while (pos < data.size - 16) {
            assertContentEquals(data.copyOfRange(pos.toInt(), pos.toInt() + 16), r.read(pos, 16))
            pos += 700
        }
        assertEquals(1, r.opens)
    }

    @Test fun backwardJumpReopensAndEndOfFileStops() {
        val r = reader()
        assertContentEquals(data.copyOfRange(9_990, 10_000), r.read(9_990, 50))
        assertContentEquals(data.copyOfRange(10, 30), r.read(10, 20))
        assertEquals(2, r.opens)
        assertEquals(-1, r.readAt(10_000, ByteArray(4), 0, 4))
    }

    @Test fun readsAcrossBlockBoundary() {
        val r = reader()
        assertContentEquals(data.copyOfRange(990, 2_010), r.read(990, 1_020))
    }
}
