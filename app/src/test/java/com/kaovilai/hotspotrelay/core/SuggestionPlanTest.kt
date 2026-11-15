package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.IphoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionPlanTest {

    private fun target(
        id: String,
        ssid: String = "SSID $id",
        password: String = "secret",
        enabled: Boolean = true,
    ) = IphoneTarget(
        id = id,
        btSelector = "iPhone $id",
        ssid = ssid,
        password = password,
        enabled = enabled,
    )

    private fun settings(vararg targets: IphoneTarget) = AppSettings(targets = targets.toList())

    @Test
    fun `priority-only reorder never replaces a suggestion`() {
        val a = target("a")
        val b = target("b")
        val plan = SuggestionPlanner.plan(settings(a, b), settings(b, a))

        assertTrue(plan.forget.isEmpty())
        assertTrue(plan.register.isEmpty())
        assertEquals(listOf(b, a), plan.ensure)
    }

    @Test
    fun `disabling a target forgets only its own ssid`() {
        val a = target("a")
        val b = target("b")
        val plan = SuggestionPlanner.plan(settings(a, b), settings(a, b.copy(enabled = false)))

        assertEquals(setOf("SSID b"), plan.forget.map { it.ssid }.toSet())
        assertTrue(plan.register.isEmpty())
        assertEquals(listOf(a), plan.ensure)
    }

    @Test
    fun `ssid still used by another enabled target is not forgotten`() {
        val a = target("a", ssid = "shared")
        val b = target("b", ssid = "shared")
        val plan = SuggestionPlanner.plan(settings(a, b), settings(a, b.copy(enabled = false)))

        assertTrue(plan.forget.isEmpty())
        assertEquals(listOf(a), plan.ensure)
    }

    @Test
    fun `re-enabling a target registers it`() {
        val a = target("a")
        val disabled = a.copy(enabled = false)
        val plan = SuggestionPlanner.plan(settings(disabled), settings(a))

        assertEquals(listOf(a), plan.register)
        assertTrue(plan.ensure.isEmpty())
    }

    @Test
    fun `changed password registers again`() {
        val a = target("a", password = "old")
        val changed = a.copy(password = "new")
        val plan = SuggestionPlanner.plan(settings(a), settings(changed))

        assertEquals(listOf(changed), plan.register)
    }

    @Test
    fun `unchanged active target is ensured and never replaced`() {
        val a = target("a")
        val plan = SuggestionPlanner.plan(settings(a, target("b")), settings(a))

        assertEquals(listOf(a), plan.ensure)
        assertTrue(plan.register.isEmpty())
        assertEquals(setOf("SSID b"), plan.forget.map { it.ssid }.toSet())
    }

    @Test
    fun `enabling a higher-priority target with the same ssid replaces the old credentials`() {
        val enabled = target("a", ssid = "shared", password = "old")
        val disabled = target("b", ssid = "shared", password = "new", enabled = false)
        val promoted = disabled.copy(enabled = true)
        val plan = SuggestionPlanner.plan(
            settings(enabled, disabled),
            settings(promoted, enabled),
        )

        // One suggestion per SSID: the highest-priority enabled target wins, and
        // its different password needs replacing the suggestion.
        assertEquals(listOf(promoted), plan.register)
        assertTrue(plan.ensure.isEmpty())
        assertTrue(plan.forget.isEmpty())
    }

    @Test
    fun `disabling the first of two same-ssid targets registers the remaining password`() {
        val first = target("a", ssid = "shared", password = "old")
        val second = target("b", ssid = "shared", password = "new")
        val plan = SuggestionPlanner.plan(
            settings(first, second),
            settings(first.copy(enabled = false), second),
        )

        // activeBefore is deduplicated by SSID, so the remaining target's
        // different password is registered instead of being skipped.
        assertEquals(listOf(second), plan.register)
        assertTrue(plan.forget.isEmpty())
    }

    @Test
    fun `forgotten targets keep their credentials for API 29 removal`() {
        val removed = target("a", password = "secret")
        val plan = SuggestionPlanner.plan(settings(removed), settings())

        assertEquals(listOf(removed.ssid to removed.password), plan.forget.map { it.ssid to it.password })
    }

    @Test
    fun `incomplete targets are never registered`() {
        val incomplete = target("a").copy(password = "", enabled = false)
        val plan = SuggestionPlanner.plan(
            settings(incomplete),
            settings(incomplete.copy(enabled = true)),
        )

        // Without credentials the entry is not active, so any suggestion for its
        // SSID is revoked rather than (re)registered.
        assertTrue(plan.register.isEmpty())
        assertTrue(plan.ensure.isEmpty())
        assertEquals(setOf("SSID a"), plan.forget.map { it.ssid }.toSet())
    }
}
