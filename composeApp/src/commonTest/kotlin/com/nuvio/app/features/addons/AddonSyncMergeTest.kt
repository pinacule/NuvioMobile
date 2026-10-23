package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonSyncMergeTest {

    private fun item(
        url: String,
        name: String = url,
        enabled: Boolean = true,
        sortOrder: Int = 0,
    ) = AddonPushItem(url = url, name = name, enabled = enabled, sortOrder = sortOrder)

    private fun urls(items: List<AddonPushItem>): List<String> = items.map { it.url }

    @Test
    fun `no pending changes anywhere returns the server list`() {
        val synced = listOf(item("a", sortOrder = 0), item("b", sortOrder = 1))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = synced,
            syncedItems = synced,
        )
        assertEquals(listOf("a", "b"), urls(merged))
    }

    @Test
    fun `pending local add survives the pull`() {
        val synced = listOf(item("a"))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = listOf(item("a"), item("new")),
            syncedItems = synced,
        )
        assertEquals(listOf("a", "new"), urls(merged))
    }

    @Test
    fun `pending local removal wins over the server list`() {
        val synced = listOf(item("a", sortOrder = 0), item("b", sortOrder = 1))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = listOf(item("a")),
            syncedItems = synced,
        )
        assertEquals(listOf("a"), urls(merged))
    }

    @Test
    fun `addon added by another device is kept after the local items`() {
        val synced = listOf(item("a"))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = listOf(item("a"), item("other-device")),
            localItems = listOf(item("a"), item("mine")),
            syncedItems = synced,
        )
        assertEquals(listOf("a", "mine", "other-device"), urls(merged))
    }

    @Test
    fun `untouched local item removed by another device is dropped`() {
        val synced = listOf(item("a", sortOrder = 0), item("b", sortOrder = 1))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = listOf(item("a")),
            localItems = synced,
            syncedItems = synced,
        )
        assertEquals(listOf("a"), urls(merged))
    }

    @Test
    fun `local edit wins over the server version`() {
        val synced = listOf(item("a", enabled = true))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = listOf(item("a", enabled = false)),
            syncedItems = synced,
        )
        assertEquals(listOf(false), merged.map { it.enabled })
    }

    @Test
    fun `local edit resurrects an item another device removed`() {
        val synced = listOf(item("a", name = "Old name"))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = emptyList(),
            localItems = listOf(item("a", name = "Renamed locally")),
            syncedItems = synced,
        )
        assertEquals(listOf("a"), urls(merged))
        assertEquals("Renamed locally", merged.single().name)
    }

    @Test
    fun `local reorder wins and sort orders are reindexed`() {
        val synced = listOf(item("a", sortOrder = 0), item("b", sortOrder = 1))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = listOf(item("b", sortOrder = 0), item("a", sortOrder = 1)),
            syncedItems = synced,
        )
        assertEquals(listOf("b", "a"), urls(merged))
        assertEquals(listOf(0, 1), merged.map { it.sortOrder })
    }

    @Test
    fun `blank local name does not erase the server name`() {
        val synced = listOf(item("a", name = "Torrentio"))
        val merged = mergeServerAddonsWithPendingLocal(
            serverItems = synced,
            localItems = listOf(item("a", name = "", enabled = false)),
            syncedItems = synced,
        )
        assertEquals("Torrentio", merged.single().name)
        assertEquals(false, merged.single().enabled)
    }
}
