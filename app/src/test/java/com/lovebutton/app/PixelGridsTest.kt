package com.lovebutton.app

import com.lovebutton.app.widget.PixelGrids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelGridsTest {

    @Test
    fun `every message icon has a grid`() {
        listOf("heart", "bubble", "paw", "call").forEach { name ->
            assertTrue("missing grid: $name", PixelGrids.GRIDS.containsKey(name))
        }
    }

    @Test
    fun `every grid is rectangular`() {
        // A ragged grid would silently shift cells when rendered, and the bug
        // would look like bad art rather than bad data.
        PixelGrids.GRIDS.forEach { (name, rows) ->
            val width = rows.first().length
            rows.forEach { row ->
                assertEquals("$name has a ragged row", width, row.length)
            }
        }
    }

    @Test
    fun `every grid is square and uses only known cell characters`() {
        PixelGrids.GRIDS.forEach { (name, rows) ->
            assertEquals("$name is not square", rows.size, rows.first().length)
            rows.forEach { row ->
                row.forEach { ch ->
                    assertTrue("$name has unknown cell '$ch'", ch in "Xsdo.")
                }
            }
        }
    }

    @Test
    fun `the heart grid has solid cells`() {
        val heart = PixelGrids.GRIDS.getValue("heart")
        assertTrue(heart.sumOf { row -> row.count { it == 'X' || it == 's' } } > 40)
    }
}
