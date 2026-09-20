package app.astiko.ui

import app.astiko.data.model.City
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

/**
 * The announcement links are hand-written URLs, so the tests pin what the
 * top bar icon relies on: every city except the two that publish nothing
 * returns a usable https link, and only Athens follows the UI language.
 */
class CityAnnouncementsTest {
    /** The operators with no page to open. */
    private val noAnnouncements = setOf(City.AGRINIO, City.ARTA)

    @Test
    fun everyCityExceptTheGapOnesHasALink() {
        val withoutLink = City.entries.filter { it.announcementsUrl("el") == null }.toSet()
        assertEquals(noAnnouncements, withoutLink)
    }

    @Test
    fun gapsReturnNullInBothLanguages() {
        noAnnouncements.forEach { city ->
            assertNull(city.announcementsUrl("el"))
            assertNull(city.announcementsUrl("en"))
        }
    }

    @Test
    fun linksAreHttpsAndWellFormed() {
        City.entries.mapNotNull { it.announcementsUrl("en") }.forEach { url ->
            val uri = URI.create(url)
            assertEquals("https", uri.scheme)
            assertNotNull(uri.host)
        }
    }

    @Test
    fun onlyAthensFollowsTheLanguage() {
        assertEquals(
            "https://www.oasa.gr/en/blog/category/announcements/",
            City.ATHENS.announcementsUrl("en"),
        )
        assertNotEquals(City.ATHENS.announcementsUrl("el"), City.ATHENS.announcementsUrl("en"))
        // A language that is neither Greek nor English lands on the Greek page.
        assertEquals(City.ATHENS.announcementsUrl("el"), City.ATHENS.announcementsUrl("de"))
        City.entries
            .filter { it != City.ATHENS }
            .forEach { city ->
                assertEquals(city.announcementsUrl("el"), city.announcementsUrl("en"))
            }
    }
}
