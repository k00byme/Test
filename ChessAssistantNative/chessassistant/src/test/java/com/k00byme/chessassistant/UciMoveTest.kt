package com.k00byme.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UciMoveTest {
    @Test
    fun parsesNormalMove() {
        val move = UciMove.parse("e2e4")
        assertEquals(4, move.from.file)
        assertEquals(2, move.from.rank)
        assertEquals(4, move.to.file)
        assertEquals(4, move.to.rank)
    }

    @Test
    fun parsesPromotion() {
        assertEquals('q', UciMove.parse("a7a8q").promotion)
    }

    @Test
    fun rejectsBadSquare() {
        assertThrows(IllegalArgumentException::class.java) { UciMove.parse("i2e4") }
    }
}
