package app.astiko.data.model

import app.astiko.util.haversineKm

enum class City(
    val label: String,
    val provider: Provider,
    val lat: Double,
    val lon: Double,
    val population: Int,
) {
    ATHENS("Αθήνα", Provider.OASA, 37.9757, 23.7341, 3_146_164),
    THESSALONIKI("Θεσσαλονίκη", Provider.OSETh, 40.6329, 22.9398, 802_392),
    LARISSA("Λάρισα", Provider.CITYBUS, 39.6387, 22.4161, 146_374),
    XANTHI("Ξάνθη", Provider.CITYBUS_XANTHI, 41.1369, 24.8868, 58_749),
    IRAKLIO("Ηράκλειο", Provider.CITYBUS_IRAKLIO, 35.3382, 25.1370, 162_600),
    IOANNINA("Ιωάννινα", Provider.CITYBUS_IOANNINA, 39.6647, 20.8535, 88_226),
    PATRA("Πάτρα", Provider.CITYBUS_PATRA, 38.2459, 21.7358, 196_937),
    CHANIA("Χανιά", Provider.CITYBUS_CHANIA, 35.5128, 24.0174, 90_185),
    VOLOS("Βόλος", Provider.CITYBUS_VOLOS, 39.3615, 22.9449, 127_441),
    CORFU("Κέρκυρα", Provider.CITYBUS_CORFU, 39.6236, 19.9244, 39_673),
    SALAMINA("Σαλαμίνα", Provider.CITYBUS_SALAMINA, 37.9650, 23.5274, 34_850),
    KAVALA("Καβάλα", Provider.CITYBUS_KAVALA, 40.9364, 24.4053, 51_948),
    CHALKIDA("Χαλκίδα", Provider.CITYBUS_CHALKIDA, 38.4616, 23.6017, 80_672),
    SERRES("Σέρρες", Provider.CITYBUS_SERRES, 41.0902, 23.5531, 58_398),
    KATERINI("Κατερίνη", Provider.CITYBUS_KATERINI, 40.2698, 22.5101, 61_557),
    MITILINI("Μυτιλήνη", Provider.CITYBUS_MITILINI, 39.1003, 26.5557, 31_714),
    ALEXANDROUPOLI("Αλεξανδρούπολη", Provider.CITYBUS_ALEXANDROUPOLI, 40.8468, 25.8792, 59_476),
    PTOLEMAIDA("Πτολεμαΐδα", Provider.CITYBUS_PTOLEMAIDA, 40.5137, 21.6758, 31_536),
    KOZANI("Κοζάνη", Provider.CITYBUS_KOZANI, 40.3021, 21.7882, 42_139),
    LAMIA("Λαμία", Provider.CITYBUS_LAMIA, 38.8979, 22.4353, 47_532),
    AGRINIO("Αγρίνιο", Provider.CITYBUS_AGRINIO, 38.6214, 21.4078, 89_689),
    CHIOS("Χίος", Provider.CITYBUS_CHIOS, 38.3725, 26.1375, 50_358),
    KOMOTINI("Κομοτηνή", Provider.CITYBUS_KOMOTINI, 41.1194, 25.4054, 65_243),
    ARTA("Άρτα", Provider.CITYBUS_ARTA, 39.1601, 20.9856, 41_599),
    VEROIA("Βέροια", Provider.CITYBUS_VEROIA, 40.5215, 22.2037, 62_656),
    MESOLOGGI("Μεσολόγγι", Provider.CITYBUS_MESOLOGGI, 38.3714, 21.4315, 32_048),

    ;

    companion object {
        /**
         * City picker order by population,
         * largest first. Declaration order stays untouched. It is
         * the persistence/GPS base order.
         */
        val pickerOrder: List<City> = entries.sortedByDescending { it.population }

        fun nearestCity(
            lat: Double,
            lon: Double,
        ): City = entries.minBy { haversineKm(lat, lon, it.lat, it.lon) }
    }
}
