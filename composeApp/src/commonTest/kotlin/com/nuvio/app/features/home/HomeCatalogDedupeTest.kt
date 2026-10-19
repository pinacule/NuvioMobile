package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogDedupeTest {

    private fun definition(
        manifestId: String,
        type: String,
        catalogId: String,
        addonName: String = manifestId,
    ) = HomeCatalogDefinition(
        key = "$manifestId:$type:$catalogId",
        defaultTitle = "title",
        catalogName = catalogId,
        addonName = addonName,
        manifestUrl = "https://$manifestId.example/manifest.json",
        type = type,
        catalogId = catalogId,
        supportsPagination = false,
        descriptorSignature = "sig-$manifestId",
    )

    @Test
    fun `exact duplicate keys collapse to one`() {
        val first = definition("com.linvo.cinemeta", "movie", "top")
        val duplicate = definition("com.linvo.cinemeta", "movie", "top")

        assertEquals(listOf(first), dedupeHomeCatalogDefinitions(listOf(first, duplicate)))
    }

    @Test
    fun `same catalog address from different addons collapses to first`() {
        val existing = definition("other.addon", "movie", "top", addonName = "Other")
        val cinemeta = definition("com.linvo.cinemeta", "movie", "top", addonName = "Cinemeta")

        // Existing installs win over the pre-installed Cinemeta.
        assertEquals(listOf(existing), dedupeHomeCatalogDefinitions(listOf(existing, cinemeta)))
    }

    @Test
    fun `different catalog ids are kept`() {
        val top = definition("com.linvo.cinemeta", "movie", "top")
        val year = definition("com.linvo.cinemeta", "movie", "year")

        assertEquals(listOf(top, year), dedupeHomeCatalogDefinitions(listOf(top, year)))
    }

    @Test
    fun `same catalog id across types is kept`() {
        val movieTop = definition("com.linvo.cinemeta", "movie", "top")
        val seriesTop = definition("com.linvo.cinemeta", "series", "top")

        assertEquals(listOf(movieTop, seriesTop), dedupeHomeCatalogDefinitions(listOf(movieTop, seriesTop)))
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList(), dedupeHomeCatalogDefinitions(emptyList()))
    }
}
