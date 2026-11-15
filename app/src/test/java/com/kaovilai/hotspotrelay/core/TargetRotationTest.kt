package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.IphoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetRotationTest {

    private fun target(id: String, enabled: Boolean = true) = IphoneTarget(
        id = id,
        btSelector = "iPhone $id",
        ssid = "SSID $id",
        password = "secret",
        enabled = enabled,
    )

    private val a = target("a")
    private val b = target("b")
    private val c = target("c")

    @Test
    fun `empty target list has no current target`() {
        assertNull(TargetRotation().current(emptyList()))
    }

    @Test
    fun `first target is the default and stays current`() {
        val rotation = TargetRotation()

        assertEquals(a, rotation.current(listOf(a, b, c)))
        assertEquals(a, rotation.current(listOf(a, b, c)))
    }

    @Test
    fun `advance cycles through the list and wraps`() {
        val rotation = TargetRotation()
        val targets = listOf(a, b, c)

        rotation.current(targets)
        rotation.advance(targets)
        assertEquals(b, rotation.current(targets))
        rotation.advance(targets)
        assertEquals(c, rotation.current(targets))
        rotation.advance(targets)
        assertEquals(a, rotation.current(targets))
    }

    @Test
    fun `advance follows priority order of the supplied list`() {
        val rotation = TargetRotation()
        val targets = listOf(c, a, b) // user reordered

        rotation.current(targets)
        rotation.advance(targets)
        assertEquals(a, rotation.current(targets))
    }

    @Test
    fun `disabled targets are skipped because the caller filters them`() {
        val rotation = TargetRotation()
        val active = listOf(a, c) // b disabled/removed

        rotation.current(active)
        rotation.advance(active)
        assertEquals(c, rotation.current(active))
        rotation.advance(active)
        assertEquals(a, rotation.current(active))
    }

    @Test
    fun `select pins the rotation to a target`() {
        val rotation = TargetRotation()
        val targets = listOf(a, b, c)

        rotation.select(c)
        assertEquals(c, rotation.current(targets))
        rotation.advance(targets)
        assertEquals(a, rotation.current(targets))
    }

    @Test
    fun `removing the current target falls back to the first`() {
        val rotation = TargetRotation()
        rotation.select(b)

        assertEquals(a, rotation.current(listOf(a, c)))
    }

    @Test
    fun `reset returns to the top of the list`() {
        val rotation = TargetRotation()
        val targets = listOf(a, b)
        rotation.current(targets)
        rotation.advance(targets)
        assertEquals(b, rotation.current(targets))

        rotation.reset()
        assertEquals(a, rotation.current(targets))
    }
}
