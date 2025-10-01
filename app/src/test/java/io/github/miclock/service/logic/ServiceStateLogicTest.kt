package io.github.miclock.service.logic

import com.google.common.truth.Truth.assertThat
import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStateLogicTest {

    private lateinit var stateFlow: MutableStateFlow<ServiceState>

    @Before
    fun setUp() {
        stateFlow = MutableStateFlow(ServiceState())
    }

    @Test
    fun `initial state should have correct defaults`() = runTest {
        // Given: Fresh service state
        val state = stateFlow.value

        // Then
        assertThat(state.isRunning).isFalse()
        assertThat(state.isPausedBySilence).isFalse()
        assertThat(state.currentDeviceAddress).isNull()
    }

    @Test
    fun `state transition from stopped to running`() = runTest {
        // When
        stateFlow.value = stateFlow.value.copy(isRunning = true)

        // Then
        val state = stateFlow.value
        assertThat(state.isRunning).isTrue()
        assertThat(state.isPausedBySilence).isFalse()
        assertThat(state.currentDeviceAddress).isNull()
    }

    @Test
    fun `state transition to paused by silence`() = runTest {
        // Given: Service is running
        stateFlow.value = stateFlow.value.copy(
            isRunning = true,
            currentDeviceAddress = "AudioRecord",
        )

        // When: Service is silenced
        stateFlow.value = stateFlow.value.copy(isPausedBySilence = true)

        // Then
        val state = stateFlow.value
        assertThat(state.isRunning).isTrue() // Still running
        assertThat(state.isPausedBySilence).isTrue() // But paused
        assertThat(state.currentDeviceAddress).isEqualTo("AudioRecord")
    }
}
