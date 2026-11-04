package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainViewModelStatusTest {

    private val connecting = MainStatus.Connecting("The core is still connecting")

    @Test
    fun aStartOrStopSignalSetsThePlainState() {
        assertEquals(
            MainStatus.Connected,
            MainViewModel.runningStatus(MainStatus.Disconnected, wasRunning = false, running = true, clearTestingText = false)
        )
        assertEquals(
            MainStatus.Disconnected,
            MainViewModel.runningStatus(MainStatus.Connected, wasRunning = true, running = false, clearTestingText = false)
        )
        assertEquals(
            MainStatus.Connected,
            MainViewModel.runningStatus(connecting, wasRunning = true, running = true, clearTestingText = true)
        )
        assertEquals(
            MainStatus.Disconnected,
            MainViewModel.runningStatus(connecting, wasRunning = true, running = false, clearTestingText = true)
        )
    }

    @Test
    fun aRepeatedSignalKeepsTestTextButEndsConnecting() {
        val progress = MainStatus.TestProgress("3 / 10")
        assertEquals(progress, MainViewModel.runningStatus(progress, wasRunning = true, running = true, clearTestingText = false))

        val result = MainStatus.ConnectionTest(ConnectionTestResult(delayMillis = 120L, errorMessage = ""))
        assertEquals(result, MainViewModel.runningStatus(result, wasRunning = true, running = true, clearTestingText = false))

        assertEquals(
            MainStatus.Connected,
            MainViewModel.runningStatus(connecting, wasRunning = true, running = true, clearTestingText = false)
        )
        assertEquals(
            MainStatus.Disconnected,
            MainViewModel.runningStatus(connecting, wasRunning = false, running = false, clearTestingText = false)
        )
    }

    @Test
    fun aServiceFoundGoneOnAStateQueryEndsWhateverTheScreenWasLeftShowing() {
        // The query nobody acknowledged arrives as a plain "not running" signal, without clearing test text.
        val left = listOf(MainStatus.Connected, connecting, MainStatus.TestProgress("3 / 10"))
        for (status in left) {
            assertEquals(
                MainStatus.Disconnected,
                MainViewModel.runningStatus(status, wasRunning = true, running = false, clearTestingText = false)
            )
        }
        // A screen that already shows a stopped service keeps what it shows.
        assertEquals(
            MainStatus.Disconnected,
            MainViewModel.runningStatus(MainStatus.Disconnected, wasRunning = false, running = false, clearTestingText = false)
        )
    }

    @Test
    fun theServicesOwnTestShowsWhileNoTestOfTheUsersRuns() {
        val result = ConnectionTestResult(delayMillis = 85L, country = "DE", ipAddress = "203.0.113.7")
        assertEquals(MainStatus.Testing, MainViewModel.autoTestStartedStatus(MainStatus.Connected, running = true, testing = false))
        assertEquals(MainStatus.Connected, MainViewModel.autoTestStartedStatus(MainStatus.Connected, running = true, testing = true))
        assertEquals(MainStatus.Disconnected, MainViewModel.autoTestStartedStatus(MainStatus.Disconnected, running = false, testing = false))

        assertEquals(MainStatus.ConnectionTest(result), MainViewModel.autoTestResultStatus(MainStatus.Testing, running = true, testing = false, result))
        // A test the user started, or a test of many configs, keeps the status line.
        val progress = MainStatus.TestProgress("3 / 10")
        assertEquals(progress, MainViewModel.autoTestResultStatus(progress, running = true, testing = true, result))
        assertEquals(MainStatus.Disconnected, MainViewModel.autoTestResultStatus(MainStatus.Disconnected, running = false, testing = false, result))
    }

    @Test
    fun aCancelledTestOfTheServicesEndsOnlyItsOwnTestingStatus() {
        assertEquals(MainStatus.Connected, MainViewModel.autoTestCancelledStatus(MainStatus.Testing, running = true, testing = false))
        assertEquals(MainStatus.Testing, MainViewModel.autoTestCancelledStatus(MainStatus.Testing, running = true, testing = true))
        val shown = MainStatus.ConnectionTest(ConnectionTestResult(delayMillis = 85L))
        assertEquals(shown, MainViewModel.autoTestCancelledStatus(shown, running = true, testing = false))
        assertEquals(MainStatus.Disconnected, MainViewModel.autoTestCancelledStatus(MainStatus.Disconnected, running = false, testing = false))
    }
}
