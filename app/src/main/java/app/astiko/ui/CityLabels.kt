package app.astiko.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.astiko.R
import app.astiko.data.model.City

/**
 * Localized display names for domain data.
 *
 * City.kt keeps Greek labels as the source of truth (persisted settings,
 * GPS matching). These mappings localize them for display through the
 * standard resource mechanism (values/ + values-en/).
 */

@StringRes
fun cityNameRes(city: City): Int =
    when (city) {
        City.ATHENS -> R.string.city_athens
        City.THESSALONIKI -> R.string.city_thessaloniki
        City.LARISSA -> R.string.city_larissa
        City.XANTHI -> R.string.city_xanthi
        City.IRAKLIO -> R.string.city_iraklio
        City.IOANNINA -> R.string.city_ioannina
        City.PATRA -> R.string.city_patra
        City.CHANIA -> R.string.city_chania
        City.VOLOS -> R.string.city_volos
        City.CORFU -> R.string.city_corfu
        City.SALAMINA -> R.string.city_salamina
        City.KAVALA -> R.string.city_kavala
        City.CHALKIDA -> R.string.city_chalkida
        City.SERRES -> R.string.city_serres
        City.KATERINI -> R.string.city_katerini
        City.MITILINI -> R.string.city_mitilini
        City.ALEXANDROUPOLI -> R.string.city_alexandroupoli
        City.PTOLEMAIDA -> R.string.city_ptolemaida
        City.KOZANI -> R.string.city_kozani
        City.LAMIA -> R.string.city_lamia
        City.AGRINIO -> R.string.city_agrinio
        City.CHIOS -> R.string.city_chios
        City.KOMOTINI -> R.string.city_komotini
        City.ARTA -> R.string.city_arta
        City.VEROIA -> R.string.city_veroia
        City.MESOLOGGI -> R.string.city_mesologgi
    }

@Composable
fun City.displayName(): String = stringResource(cityNameRes(this))

/** Operator label per city (onboarding cards + data-source rows). */
@StringRes
fun operatorNameRes(city: City): Int =
    when (city) {
        City.ATHENS -> R.string.op_oasa
        City.THESSALONIKI -> R.string.op_oseth
        City.LARISSA -> R.string.op_larissa
        City.XANTHI -> R.string.op_xanthi
        City.IRAKLIO -> R.string.op_iraklio
        City.IOANNINA -> R.string.op_ioannina
        City.PATRA -> R.string.op_patra
        City.CHANIA -> R.string.op_chania
        City.VOLOS -> R.string.op_volos
        City.CORFU -> R.string.op_corfu
        City.SALAMINA -> R.string.op_salamina
        City.KAVALA -> R.string.op_kavala
        City.CHALKIDA -> R.string.op_chalkida
        City.SERRES -> R.string.op_serres
        City.KATERINI -> R.string.op_katerini
        City.MITILINI -> R.string.op_mitilini
        City.ALEXANDROUPOLI -> R.string.op_alexandroupoli
        City.PTOLEMAIDA -> R.string.op_ptolemaida
        City.KOZANI -> R.string.op_kozani
        City.LAMIA -> R.string.op_lamia
        City.AGRINIO -> R.string.op_agrinio
        City.CHIOS -> R.string.op_chios
        City.KOMOTINI -> R.string.op_komotini
        City.ARTA -> R.string.op_arta
        City.VEROIA -> R.string.op_veroia
        City.MESOLOGGI -> R.string.op_mesologgi
    }

/**
 * The two lines of a city row: the name over the operator. Operator names
 * come from each agency's own site (oasa.gr / oseth.com.gr / the local
 * Αστικό ΚΤΕΛ site). Salamina is a plain ΚΤΕΛ, not an Αστικό one.
 */
@Composable
fun CityNameAndOperator(city: City) {
    Text(city.displayName(), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(operatorNameRes(city)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Data-source subtitle per city (info-screen rows). */
@StringRes
fun sourceSubtitleRes(city: City): Int =
    when (city) {
        City.ATHENS -> R.string.src_oasa
        City.THESSALONIKI -> R.string.src_oseth
        City.LARISSA -> R.string.src_larissa
        City.XANTHI -> R.string.src_xanthi
        City.IRAKLIO -> R.string.src_iraklio
        City.IOANNINA -> R.string.src_ioannina
        City.PATRA -> R.string.src_patra
        City.CHANIA -> R.string.src_chania
        City.VOLOS -> R.string.src_volos
        City.CORFU -> R.string.src_corfu
        City.SALAMINA -> R.string.src_salamina
        City.KAVALA -> R.string.src_kavala
        City.CHALKIDA -> R.string.src_chalkida
        City.SERRES -> R.string.src_serres
        City.KATERINI -> R.string.src_katerini
        City.MITILINI -> R.string.src_mitilini
        City.ALEXANDROUPOLI -> R.string.src_alexandroupoli
        City.PTOLEMAIDA -> R.string.src_ptolemaida
        City.KOZANI -> R.string.src_kozani
        City.LAMIA -> R.string.src_lamia
        City.AGRINIO -> R.string.src_agrinio
        City.CHIOS -> R.string.src_chios
        City.KOMOTINI -> R.string.src_komotini
        City.ARTA -> R.string.src_arta
        City.VEROIA -> R.string.src_veroia
        City.MESOLOGGI -> R.string.src_mesologgi
    }
