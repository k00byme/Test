package com.k00byme.chessassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FenPositionTest {
    @Test
    fun parsesInitialPosition() {
        val parsed = FenPosition.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals('w', parsed.activeColor)
        assertEquals("KQkq", parsed.castling)
        assertEquals(1, parsed.fullmoveNumber)
    }

    @Test
    fun rejectsMalformedRank() {
        assertThrows(IllegalArgumentException::class.java) {
            FenPosition.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPPP/RNBQKBNR w KQkq - 0 1")
        }
    }

    @Test
    fun rejectsMissingBlackKing() {
        assertThrows(IllegalArgumentException::class.java) {
            FenPosition.parse("8/8/8/8/8/8/4K3/8 w - - 0 1")
        }
    }
}
