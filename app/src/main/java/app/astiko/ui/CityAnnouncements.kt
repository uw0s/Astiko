package app.astiko.ui

import app.astiko.data.model.City

/**
 * The operator's announcements page for this city.
 */
fun City.announcementsUrl(lang: String): String? =
    when (this) {
        City.ATHENS -> oasaAnnouncements(lang)
        City.THESSALONIKI -> "https://oseth.com.gr/el/nea-anakoinoseis"
        City.LARISSA -> "https://ktelast-larisas.gr/anakoinoseis"
        City.XANTHI -> "https://astikoxanthis.gr/ανακοινώσεις/"
        City.IRAKLIO -> "https://astiko-irakleiou.gr/anakoinoseis/"
        City.IOANNINA -> "https://astiko-ioannina.gr/anakoinoseis/"
        City.PATRA -> "https://www.astikopatras.gr/anakoinoseis"
        City.CHANIA -> "https://chaniabus.gr/anakoinoseis"
        City.VOLOS -> "https://astikovolou.gr/category/anakoinoseis/"
        City.CORFU -> "https://astikoktelkerkyras.gr/ta-nea-mas/"
        City.SALAMINA -> "https://ktelsalaminas.gr/anakoinoseis/"
        City.KAVALA -> "https://astiko-kavalas.gr/anakoinoseis/"
        City.CHALKIDA -> "https://astikochalkidas.gr/anakoinoseis/"
        City.SERRES -> "https://astikoktelserron.gr/anakoinoseis"
        City.KATERINI -> "https://astika-katerinis.gr/anakoinoseis"
        City.MITILINI -> "https://astika-mitilinis.gr/anakoinoseis/"
        City.ALEXANDROUPOLI -> "https://astikoktel.gr/anakoinoseis/"
        City.PTOLEMAIDA -> "https://astikaktelptolemaidas.gr/enimerosi/"
        City.KOZANI -> "https://astikoktelkozanis.gr/anakoinoseis/"
        City.LAMIA -> "https://astikoktellamias.gr/nea-amp-anakoinoseis/"
        City.AGRINIO -> null
        City.CHIOS -> "https://chioscitybus.gr/anakoinoseis/"
        City.KOMOTINI -> "https://astikakomotinis.gr/deltia-tipou/"
        City.ARTA -> null
        City.VEROIA -> "https://astikoverias.gr/"
        City.MESOLOGGI -> "https://astiko-messolonghi.gr/anakoinoseis/"
    }

/** OASA is the only operator with both a Greek and an English page. */
private fun oasaAnnouncements(lang: String): String =
    if (lang.startsWith("en")) {
        "https://www.oasa.gr/en/blog/category/announcements/"
    } else {
        "https://www.oasa.gr/blog/category/ανακοινώσεις/"
    }
