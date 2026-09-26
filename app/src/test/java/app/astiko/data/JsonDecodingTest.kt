package app.astiko.data

import app.astiko.data.oasa.OasaStopDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The optional-entry decode the adapters use on their response arrays. The
 * contract is the boundary: one malformed row answers null and drops itself,
 * while the rows around it still decode.
 */
class JsonDecodingTest {
    @Test
    fun `a well formed entry decodes`() {
        val dto =
            testJson.decodeOrNull(
                jsonObj("StopCode" to "1234", "StopDescr" to "TEST"),
                OasaStopDto.serializer(),
            )

        assertEquals("1234", dto?.stopCode)
        assertEquals("TEST", dto?.stopDescr)
    }

    @Test
    fun `a malformed entry answers null`() {
        // An object where the DTO expects a string: no tolerance setting
        // can coerce that, which is what a shape change on the wire looks like.
        val malformed = jsonObj("StopCode" to "1234", "StopLat" to jsonObj("nested" to 1))

        assertNull(testJson.decodeOrNull(malformed, OasaStopDto.serializer()))
    }

    @Test
    fun `one bad row does not fail the page around it`() {
        val page =
            jsonArr(
                jsonObj("StopCode" to "1"),
                jsonObj("StopLat" to jsonObj("nested" to 1)),
                jsonObj("StopCode" to "3"),
            )

        val codes =
            page.mapNotNull { testJson.decodeOrNull(it, OasaStopDto.serializer())?.stopCode }

        assertEquals(listOf("1", "3"), codes)
    }
}
