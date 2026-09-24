package dk.azp.jellybook.data.downloads

import dk.azp.jellybook.data.local.DownloadedBook

/**
 * Which network each unfinished download may use. A book started on Wi-Fi (or with no network at all) waits for Wi-Fi;
 * a book the user chose to download over mobile data carries on over it.
 *
 * Media3 applies one network requirement to every download, so the requirement is Wi-Fi unless some unfinished book may
 * use mobile data. In that mixed case the Wi-Fi-only books are held back one by one while the network is metered, so they
 * do not ride along with the one the user agreed to pay for.
 */
object NetworkPolicy {

    data class Decision(
        val requireUnmetered: Boolean,
        /** Parts of unfinished Wi-Fi-only books; each is either held back or released. */
        val wifiOnlyPartIds: Set<String>,
        val holdBackWifiOnly: Boolean,
    )

    fun decide(books: List<DownloadedBook>, completedPartIds: Set<String>, metered: Boolean): Decision {
        val unfinished = books.filter { book -> book.partIds().any { it !in completedPartIds } }
        val anyMayUseMetered = unfinished.any { it.allowMetered }
        return Decision(
            requireUnmetered = !anyMayUseMetered,
            wifiOnlyPartIds = unfinished.filterNot { it.allowMetered }.flatMap { it.partIds() }.filterNot { it in completedPartIds }.toSet(),
            holdBackWifiOnly = anyMayUseMetered && metered,
        )
    }

    fun DownloadedBook.partIds(): List<String> = partList.map { it.itemId }.ifEmpty { listOf(itemId) }
}
