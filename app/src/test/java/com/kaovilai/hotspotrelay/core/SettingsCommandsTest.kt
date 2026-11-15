package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.IphoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCommandsTest {

    private val personal = IphoneTarget(
        id = "a",
        label = "Personal",
        btSelector = "Personal iPhone",
        ssid = "Personal iPhone",
        password = "pw",
    )
    private val work = IphoneTarget(
        id = "b",
        label = "Work",
        btSelector = "Work iPhone",
        ssid = "Work iPhone",
        password = "pw",
    )
    private val spare = IphoneTarget(
        id = "c",
        label = "",
        btSelector = "Spare iPhone",
        ssid = "Spare iPhone",
        password = "pw",
        enabled = false,
    )
    private val settings = AppSettings(targets = listOf(personal, work, spare))

    @Test
    fun `disable by ssid`() {
        val outcome = SettingsCommands.apply(
            settings,
            SettingsCommand.SetEnabled(TargetSelector.BySsid("Work iPhone"), enabled = false),
        )

        assertTrue(outcome.ok)
        assertFalse(outcome.settings.targets.first { it.id == "b" }.enabled)
        assertTrue(outcome.settings.targets.first { it.id == "a" }.enabled)
    }

    @Test
    fun `disable by id, label, display name and index`() {
        val expectations = listOf(
            SettingsCommand.SetEnabled(TargetSelector.ById("a"), false),
            SettingsCommand.SetEnabled(TargetSelector.ByLabel("Work"), false),
            SettingsCommand.SetEnabled(TargetSelector.BySsid("Spare iPhone"), true),
            SettingsCommand.SetEnabled(TargetSelector.ByIndex(0), false),
        )

        expectations.forEach { command ->
            assertTrue(command.toString(), SettingsCommands.apply(settings, command).ok)
        }
        val byIndex = SettingsCommands.apply(settings, SettingsCommand.SetEnabled(TargetSelector.ByIndex(1), false))
        assertFalse(byIndex.settings.targets.first { it.id == "b" }.enabled)
    }

    @Test
    fun `unknown selector fails without losing settings`() {
        val outcome = SettingsCommands.apply(
            settings,
            SettingsCommand.SetEnabled(TargetSelector.BySsid("Nope"), true),
        )

        assertFalse(outcome.ok)
        assertEquals(settings, outcome.settings)
        assertTrue(outcome.message.contains("no iPhone matched"))
    }

    @Test
    fun `move reorders and clamps the destination`() {
        val moved = SettingsCommands.apply(
            settings,
            SettingsCommand.Move(TargetSelector.BySsid("Personal iPhone"), toIndex = 2),
        )

        assertEquals(listOf("b", "c", "a"), moved.settings.targets.map { it.id })

        val clamped = SettingsCommands.apply(
            settings,
            SettingsCommand.Move(TargetSelector.BySsid("Personal iPhone"), toIndex = 99),
        )
        assertEquals(listOf("b", "c", "a"), clamped.settings.targets.map { it.id })
    }

    @Test
    fun `move with a single target is a no-op success`() {
        val single = AppSettings(targets = listOf(personal))
        val outcome = SettingsCommands.apply(single, SettingsCommand.Move(TargetSelector.ById("a"), 0))

        assertTrue(outcome.ok)
        assertEquals(single, outcome.settings)
    }

    @Test
    fun `set order follows the given list and appends the rest`() {
        val outcome = SettingsCommands.apply(
            settings,
            SettingsCommand.SetOrder(listOf("Spare iPhone", "Personal")),
        )

        assertTrue(outcome.ok)
        assertEquals(listOf("c", "a", "b"), outcome.settings.targets.map { it.id })
    }

    @Test
    fun `set order ignores unknown keys and reports them`() {
        val outcome = SettingsCommands.apply(
            settings,
            SettingsCommand.SetOrder(listOf("Work", "Ghost")),
        )

        assertTrue(outcome.ok)
        assertEquals(listOf("b", "a", "c"), outcome.settings.targets.map { it.id })
        assertTrue(outcome.message.contains("Ghost"))
    }

    @Test
    fun `empty order is rejected`() {
        val outcome = SettingsCommands.apply(settings, SettingsCommand.SetOrder(emptyList()))

        assertFalse(outcome.ok)
        assertEquals(settings, outcome.settings)
    }

    @Test
    fun `rotate after is clamped`() {
        val low = SettingsCommands.apply(settings, SettingsCommand.SetRotateAfter(0))
        val high = SettingsCommands.apply(settings, SettingsCommand.SetRotateAfter(99))

        assertEquals(AppSettings.MIN_ROTATE_AFTER_ATTEMPTS, low.settings.rotateAfterAttempts)
        assertEquals(AppSettings.MAX_ROTATE_AFTER_ATTEMPTS, high.settings.rotateAfterAttempts)
    }

    @Test
    fun `start stop and query only carry a service action`() {
        val start = SettingsCommands.apply(settings, SettingsCommand.Start)
        val stop = SettingsCommands.apply(settings, SettingsCommand.Stop)
        val query = SettingsCommands.apply(settings, SettingsCommand.Query)

        assertEquals(CommandOutcome.ServiceAction.START, start.serviceAction)
        assertEquals(CommandOutcome.ServiceAction.STOP, stop.serviceAction)
        assertNull(query.serviceAction)
        assertEquals(settings, query.settings)
    }

    @Test
    fun `resolve index gives the position of a selector`() {
        assertEquals(1, SettingsCommands.resolveIndex(settings.targets, TargetSelector.BySsid("Work iPhone")))
        assertNull(SettingsCommands.resolveIndex(settings.targets, TargetSelector.BySsid("Nope")))
    }
}
