package com.hermes.vpngateclient

import com.hermes.vpngateclient.data.model.ServerSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSelectionTest {
    private data class Relay(val id: String, val ping: Int, val score: Double)

    @Test
    fun plausibleLowestPingWinsOverHigherQualityScore() {
        val selected = ServerSelection.bestReportedPing(
            listOf(
                Relay("fast", ping = 34, score = 20.0),
                Relay("high-score", ping = 82, score = 99.0)
            ),
            Relay::ping,
            Relay::score
        )

        assertEquals("fast", selected?.id)
    }

    @Test
    fun unknownPingNeverBeatsAReportedPing() {
        val selected = ServerSelection.bestReportedPing(
            listOf(
                Relay("unknown", ping = 0, score = 100.0),
                Relay("known", ping = 41, score = 10.0)
            ),
            Relay::ping,
            Relay::score
        )

        assertEquals("known", selected?.id)
    }

    @Test
    fun implausiblePingIsUsedOnlyWhenThereIsNoPlausibleSample() {
        val plausibleSelected = ServerSelection.bestReportedPing(
            listOf(Relay("implausible", ping = 2, score = 100.0), Relay("plausible", ping = 29, score = 1.0)),
            Relay::ping,
            Relay::score
        )
        val fallbackSelected = ServerSelection.bestReportedPing(
            listOf(Relay("unknown", ping = 0, score = 100.0), Relay("positive", ping = 8, score = 1.0)),
            Relay::ping,
            Relay::score
        )

        assertEquals("plausible", plausibleSelected?.id)
        assertEquals("positive", fallbackSelected?.id)
    }

    @Test
    fun qualityScoreBreaksTiesWhenAllPingsAreUnknown() {
        val selected = ServerSelection.bestReportedPing(
            listOf(Relay("lower", ping = 0, score = 4.0), Relay("higher", ping = 0, score = 8.0)),
            Relay::ping,
            Relay::score
        )

        assertEquals("higher", selected?.id)
        assertNull(ServerSelection.bestReportedPing(emptyList<Relay>(), Relay::ping, Relay::score))
    }
}
