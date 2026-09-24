package dk.azp.jellybook.data.downloads

import dk.azp.jellybook.data.local.DownloadedBook
import dk.azp.jellybook.data.local.DownloadedPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun aBookStartedOnWifiRequiresWifi() {
        val decision = NetworkPolicy.decide(listOf(book("a", allowMetered = false)), emptySet(), metered = true)

        assertTrue(decision.requireUnmetered)
        assertFalse(decision.holdBackWifiOnly)
    }

    @Test
    fun aBookAllowedOnMobileDataLiftsTheRequirement() {
        val decision = NetworkPolicy.decide(listOf(book("a", allowMetered = true)), emptySet(), metered = true)

        assertFalse(decision.requireUnmetered)
        assertEquals(emptySet<String>(), decision.wifiOnlyPartIds)
    }

    @Test
    fun wifiOnlyBooksAreHeldBackWhileAnotherUsesMobileData() {
        val books = listOf(book("paid", allowMetered = true), book("wifi", allowMetered = false, parts = listOf("w1", "w2")))

        val decision = NetworkPolicy.decide(books, emptySet(), metered = true)

        assertFalse(decision.requireUnmetered)
        assertTrue(decision.holdBackWifiOnly)
        assertEquals(setOf("w1", "w2"), decision.wifiOnlyPartIds)
    }

    @Test
    fun wifiOnlyBooksAreReleasedOnWifi() {
        val books = listOf(book("paid", allowMetered = true), book("wifi", allowMetered = false))

        assertFalse(NetworkPolicy.decide(books, emptySet(), metered = false).holdBackWifiOnly)
    }

    @Test
    fun aFinishedBookNoLongerLiftsTheRequirement() {
        val books = listOf(book("paid", allowMetered = true, parts = listOf("p1")), book("wifi", allowMetered = false, parts = listOf("w1")))

        val decision = NetworkPolicy.decide(books, completedPartIds = setOf("p1"), metered = true)

        assertTrue(decision.requireUnmetered)
        assertFalse(decision.holdBackWifiOnly)
    }

    @Test
    fun finishedPartsAreLeftAlone() {
        val books = listOf(book("paid", allowMetered = true), book("wifi", allowMetered = false, parts = listOf("w1", "w2")))

        assertEquals(setOf("w2"), NetworkPolicy.decide(books, completedPartIds = setOf("w1"), metered = true).wifiOnlyPartIds)
    }

    @Test
    fun aBookWithoutPartsIsItsOwnPart() {
        val decision = NetworkPolicy.decide(listOf(book("paid", allowMetered = true), book("single", allowMetered = false, parts = emptyList())), emptySet(), true)

        assertEquals(setOf("single"), decision.wifiOnlyPartIds)
    }

    private fun book(id: String, allowMetered: Boolean, parts: List<String> = listOf("$id-1")) = DownloadedBook(
        itemId = id,
        title = id,
        partList = parts.mapIndexed { index, partId -> DownloadedPart(partId, partId, index, 0L, 1_000L, null, null) },
        downloadedAtEpochMs = 0L,
        allowMetered = allowMetered,
    )
}
