package com.familyguard.core.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryDiffTest {

    private val now = "2026-11-01T10:00:00Z"

    private fun prev(pkg: String, firstSeen: String = "2026-10-01T00:00:00Z", removedAt: String? = null) =
        InventoryDiff.Previous(pkg, firstSeen, removedAt)

    @Test
    fun `new package is inserted with first_seen = now`() {
        val result = InventoryDiff.reconcile(previous = emptyList(), scan = listOf("com.new.app"), now = now)
        assertEquals(listOf("com.new.app"), result.upserts.map { it.packageName })
        assertEquals(now, result.upserts.single().firstSeen)
        assertTrue(result.softDeletes.isEmpty())
    }

    @Test
    fun `existing live package is refreshed and keeps first_seen`() {
        val previous = listOf(prev("com.old.app", firstSeen = "2026-09-01T00:00:00Z"))
        val result = InventoryDiff.reconcile(previous, listOf("com.old.app"), now)
        assertEquals("2026-09-01T00:00:00Z", result.upserts.single().firstSeen)
        assertTrue(result.softDeletes.isEmpty())
    }

    @Test
    fun `missing live package is soft-deleted`() {
        val previous = listOf(prev("com.gone.app"))
        val result = InventoryDiff.reconcile(previous, scan = emptyList(), now = now)
        assertTrue(result.upserts.isEmpty())
        assertEquals(listOf("com.gone.app"), result.softDeletes)
    }

    @Test
    fun `reappearing package clears removed_at but keeps first_seen`() {
        val previous = listOf(prev("com.back.app", firstSeen = "2026-08-01T00:00:00Z", removedAt = "2026-10-20T00:00:00Z"))
        val result = InventoryDiff.reconcile(previous, listOf("com.back.app"), now)
        // It is an upsert (removed_at cleared), and first_seen predates the reinstall.
        assertEquals("2026-08-01T00:00:00Z", result.upserts.single().firstSeen)
        assertTrue(result.softDeletes.isEmpty())
    }

    @Test
    fun `already-removed package absent from scan is a no-op`() {
        val previous = listOf(prev("com.dead.app", removedAt = "2026-10-01T00:00:00Z"))
        val result = InventoryDiff.reconcile(previous, scan = emptyList(), now = now)
        assertTrue(result.upserts.isEmpty())
        assertTrue(result.softDeletes.isEmpty())
        assertTrue(result.isNoOp)
    }

    @Test
    fun `label or version change is an upsert not a removal`() {
        // A label/version change surfaces as the same package in the scan; the
        // diff emits an upsert (refresh) and never a soft-delete.
        val previous = listOf(prev("com.renamed.app"))
        val result = InventoryDiff.reconcile(previous, listOf("com.renamed.app"), now)
        assertEquals(1, result.upserts.size)
        assertTrue(result.softDeletes.isEmpty())
    }

    @Test
    fun `mixed scan reconciles insert and removal together`() {
        val previous = listOf(prev("com.stay.app"), prev("com.gone.app"))
        val result = InventoryDiff.reconcile(previous, scan = listOf("com.stay.app", "com.new.app"), now = now)
        assertEquals(setOf("com.stay.app", "com.new.app"), result.upserts.map { it.packageName }.toSet())
        assertEquals(listOf("com.gone.app"), result.softDeletes)
    }
}
