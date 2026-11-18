package com.redder.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BecomingNoisyReceiverTest {
    private lateinit var fakeRegistrar: FakeReceiverRegistrar
    private lateinit var dummyContext: Context

    @BeforeEach
    fun setUp() {
        fakeRegistrar = FakeReceiverRegistrar()
        dummyContext = MockContext()
    }

    // -------------------------------------------------------------------------
    // 1. Valid ACTION_AUDIO_BECOMING_NOISY invokes callback
    // -------------------------------------------------------------------------
    @Test
    fun `valid ACTION_AUDIO_BECOMING_NOISY invokes callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        assertTrue(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 2. Multiple valid action receptions invoke callback each time
    // -------------------------------------------------------------------------
    @Test
    fun `multiple valid action receptions invoke callback each time`() {
        var count = 0
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { count++ },
                registrar = fakeRegistrar,
            )

        repeat(5) {
            receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        assertEquals(5, count)
    }

    // -------------------------------------------------------------------------
    // 3. Action Intent.ACTION_HEADSET_PLUG does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `action HEADSET_PLUG does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(Intent.ACTION_HEADSET_PLUG)

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 4. Action Intent.ACTION_BATTERY_LOW does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `action BATTERY_LOW does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(Intent.ACTION_BATTERY_LOW)

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 5. Action AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `action SCO_AUDIO_STATE_UPDATED does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 6. Action custom or arbitrary string does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `arbitrary custom action does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived("CUSTOM_NOISY_ACTION")
        receiver.onActionReceived("com.redder.SOME_INTENT")

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 7. Null action string does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `null action string does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(null)

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 8. Empty action string does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `empty action string does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived("")

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 9. Whitespace-only action string does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `whitespace-only action string does not invoke callback`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived("   ")
        receiver.onActionReceived("\t\n")

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 10. Action case-sensitivity: lowercase does NOT invoke callback
    // -------------------------------------------------------------------------
    @Test
    fun `lowercase action string does not invoke callback due to case-sensitivity`() {
        var callbackInvoked = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackInvoked = true },
                registrar = fakeRegistrar,
            )

        val lowercaseAction = AudioManager.ACTION_AUDIO_BECOMING_NOISY.lowercase()
        receiver.onActionReceived(lowercaseAction)

        assertFalse(callbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 11. register() marks isRegistered as true
    // -------------------------------------------------------------------------
    @Test
    fun `register marks isRegistered as true`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        assertFalse(receiver.isRegistered)
        receiver.register(dummyContext)
        assertTrue(receiver.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 12. register() passes this receiver instance and filter to registrar
    // -------------------------------------------------------------------------
    @Test
    fun `register passes receiver and filter to registrar`() {
        val customFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
                filterProvider = { customFilter },
            )

        receiver.register(dummyContext)

        assertEquals(1, fakeRegistrar.registerCount)
        assertSame(receiver, fakeRegistrar.lastRegisteredReceiver)
        assertSame(customFilter, fakeRegistrar.lastRegisteredFilter)
    }

    // -------------------------------------------------------------------------
    // 13. Double register() is idempotent
    // -------------------------------------------------------------------------
    @Test
    fun `double register is idempotent and calls registrar only once`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.register(dummyContext)
        receiver.register(dummyContext)

        assertEquals(1, fakeRegistrar.registerCount)
        assertTrue(receiver.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 14. unregister() unregisters with registrar and marks isRegistered as false
    // -------------------------------------------------------------------------
    @Test
    fun `unregister unregisters with registrar and marks isRegistered as false`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.register(dummyContext)
        assertTrue(receiver.isRegistered)

        receiver.unregister(dummyContext)
        assertFalse(receiver.isRegistered)
        assertEquals(1, fakeRegistrar.unregisterCount)
        assertSame(receiver, fakeRegistrar.lastUnregisteredReceiver)
    }

    // -------------------------------------------------------------------------
    // 15. unregister() when not registered is safe no-op
    // -------------------------------------------------------------------------
    @Test
    fun `unregister when not registered is safe no-op`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.unregister(dummyContext)

        assertEquals(0, fakeRegistrar.unregisterCount)
        assertFalse(receiver.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 16. Double unregister() is idempotent
    // -------------------------------------------------------------------------
    @Test
    fun `double unregister is idempotent and unregisters only once`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.register(dummyContext)
        receiver.unregister(dummyContext)
        receiver.unregister(dummyContext)

        assertEquals(1, fakeRegistrar.unregisterCount)
        assertFalse(receiver.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 17. Re-registration cycle works properly
    // -------------------------------------------------------------------------
    @Test
    fun `re-registration cycle register unregister register works properly`() {
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.register(dummyContext)
        assertTrue(receiver.isRegistered)
        assertEquals(1, fakeRegistrar.registerCount)

        receiver.unregister(dummyContext)
        assertFalse(receiver.isRegistered)
        assertEquals(1, fakeRegistrar.unregisterCount)

        receiver.register(dummyContext)
        assertTrue(receiver.isRegistered)
        assertEquals(2, fakeRegistrar.registerCount)
    }

    // -------------------------------------------------------------------------
    // 18. Callback count tracking across mixed valid and invalid actions
    // -------------------------------------------------------------------------
    @Test
    fun `callback count tracks exact number across mixed valid and invalid actions`() {
        var callbackCount = 0
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { callbackCount++ },
                registrar = fakeRegistrar,
            )

        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        receiver.onActionReceived(Intent.ACTION_HEADSET_PLUG)
        receiver.onActionReceived(null)
        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        receiver.onActionReceived("RANDOM_ACTION")
        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        assertEquals(3, callbackCount)
    }

    // -------------------------------------------------------------------------
    // 19. createFilter() method existence and configuration
    // -------------------------------------------------------------------------
    @Test
    fun `createFilter returns non-null IntentFilter configured with ACTION_AUDIO_BECOMING_NOISY`() {
        val filter = BecomingNoisyReceiver.createFilter()
        assertNotNull(filter)
    }

    // -------------------------------------------------------------------------
    // 20. Integration scenario: simulated playback loop pauses on becoming noisy
    // -------------------------------------------------------------------------
    @Test
    fun `integration with PlaybackStateMachine pauses playback when noisy is detected`() {
        val stateMachine = PlaybackStateMachine()
        stateMachine.transition(PlaybackEvent.Load)
        stateMachine.transition(PlaybackEvent.BufferingStarted)
        stateMachine.transition(PlaybackEvent.FirstChunkReady)
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {
                    if (stateMachine.state.value is PlaybackState.Playing) {
                        stateMachine.transition(PlaybackEvent.Pause)
                    }
                },
                registrar = fakeRegistrar,
            )
        receiver.register(dummyContext)

        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    // -------------------------------------------------------------------------
    // 21. Integration scenario: becoming noisy while already Paused or Idle is guarded
    // -------------------------------------------------------------------------
    @Test
    fun `integration with PlaybackStateMachine when Idle or Paused does not trigger invalid transition`() {
        val stateMachine = PlaybackStateMachine()
        assertEquals(PlaybackState.Idle, stateMachine.state.value)

        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {
                    if (stateMachine.state.value is PlaybackState.Playing) {
                        stateMachine.transition(PlaybackEvent.Pause)
                    }
                },
                registrar = fakeRegistrar,
            )
        receiver.register(dummyContext)

        // Triggering noisy while in Idle
        assertDoesNotThrow {
            receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        assertEquals(PlaybackState.Idle, stateMachine.state.value)

        // Move to Paused
        stateMachine.transition(PlaybackEvent.Load)
        stateMachine.transition(PlaybackEvent.BufferingStarted)
        stateMachine.transition(PlaybackEvent.FirstChunkReady)
        stateMachine.transition(PlaybackEvent.Pause)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)

        // Triggering noisy while already Paused
        assertDoesNotThrow {
            receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    // -------------------------------------------------------------------------
    // 22. Multiple distinct BecomingNoisyReceiver instances operate independently
    // -------------------------------------------------------------------------
    @Test
    fun `multiple distinct receiver instances operate independently`() {
        val registrar1 = FakeReceiverRegistrar()
        val registrar2 = FakeReceiverRegistrar()
        var count1 = 0
        var count2 = 0

        val receiver1 =
            BecomingNoisyReceiver(
                onNoiseDetected = { count1++ },
                registrar = registrar1,
            )
        val receiver2 =
            BecomingNoisyReceiver(
                onNoiseDetected = { count2++ },
                registrar = registrar2,
            )

        receiver1.register(dummyContext)
        assertTrue(receiver1.isRegistered)
        assertFalse(receiver2.isRegistered)
        assertEquals(1, registrar1.registerCount)
        assertEquals(0, registrar2.registerCount)

        receiver2.register(dummyContext)
        assertTrue(receiver2.isRegistered)
        assertEquals(1, registrar2.registerCount)

        receiver1.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        assertEquals(1, count1)
        assertEquals(0, count2)

        receiver2.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        assertEquals(1, count1)
        assertEquals(1, count2)

        receiver1.unregister(dummyContext)
        assertFalse(receiver1.isRegistered)
        assertTrue(receiver2.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 23. unregister() handles IllegalArgumentException gracefully
    // -------------------------------------------------------------------------
    @Test
    fun `unregister handles IllegalArgumentException gracefully without propagating`() {
        fakeRegistrar.shouldThrowOnUnregister = IllegalArgumentException("Receiver not registered")
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {},
                registrar = fakeRegistrar,
            )

        receiver.register(dummyContext)
        assertTrue(receiver.isRegistered)

        assertDoesNotThrow {
            receiver.unregister(dummyContext)
        }
        assertFalse(receiver.isRegistered)
    }

    // -------------------------------------------------------------------------
    // 24. onReceive delegates to onActionReceived with intent action
    // -------------------------------------------------------------------------
    @Test
    fun `onReceive invokes callback when intent action is ACTION_AUDIO_BECOMING_NOISY`() {
        var noisyCalled = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { noisyCalled = true },
                registrar = fakeRegistrar,
            )

        val intent = FakeIntent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        receiver.onReceive(dummyContext, intent)

        assertTrue(noisyCalled)
    }

    // -------------------------------------------------------------------------
    // 25. onReceive ignores intent with non-matching action
    // -------------------------------------------------------------------------
    @Test
    fun `onReceive ignores intent with non-matching action`() {
        var noisyCalled = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { noisyCalled = true },
                registrar = fakeRegistrar,
            )

        val intent = FakeIntent(Intent.ACTION_HEADSET_PLUG)
        receiver.onReceive(dummyContext, intent)

        assertFalse(noisyCalled)
    }

    // -------------------------------------------------------------------------
    // 26. onReceive handles intent with null action
    // -------------------------------------------------------------------------
    @Test
    fun `onReceive handles intent with null action without error`() {
        var noisyCalled = false
        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = { noisyCalled = true },
                registrar = fakeRegistrar,
            )

        val intent = FakeIntent(null)
        assertDoesNotThrow {
            receiver.onReceive(dummyContext, intent)
        }
        assertFalse(noisyCalled)
    }

    // -------------------------------------------------------------------------
    // 27. ContextReceiverRegistrar implements ReceiverRegistrar interface
    // -------------------------------------------------------------------------
    @Test
    fun `ContextReceiverRegistrar implements ReceiverRegistrar interface`() {
        val mockContext = MockContext()
        val registrar: ReceiverRegistrar = ContextReceiverRegistrar(mockContext)
        assertNotNull(registrar)
    }

    // -------------------------------------------------------------------------
    // 28. ContextReceiverRegistrar delegates unregister to Context
    // -------------------------------------------------------------------------
    @Test
    fun `ContextReceiverRegistrar delegates unregister to Context`() {
        val mockContext = MockContext()
        val registrar = ContextReceiverRegistrar(mockContext)
        val receiver = BecomingNoisyReceiver(onNoiseDetected = {})

        registrar.unregisterReceiver(receiver)
        assertEquals(1, mockContext.unregisterCallCount)
        assertSame(receiver, mockContext.unregisteredReceiver)
    }

    // -------------------------------------------------------------------------
    // 29. unregister when not registered is safe no-op with default registrar
    // -------------------------------------------------------------------------
    @Test
    fun `unregister when not registered is safe no-op with default registrar`() {
        val mockContext = MockContext()
        val receiver = BecomingNoisyReceiver(onNoiseDetected = {})

        receiver.unregister(mockContext)
        assertFalse(receiver.isRegistered)
        assertEquals(0, mockContext.unregisterCallCount)
    }

    // -------------------------------------------------------------------------
    // 30. No auto-resume guarantee: receiver solely handles pause event
    // -------------------------------------------------------------------------
    @Test
    fun `no auto-resume occurs when audio output reconnects or action changes`() {
        val stateMachine = PlaybackStateMachine()
        stateMachine.transition(PlaybackEvent.Load)
        stateMachine.transition(PlaybackEvent.BufferingStarted)
        stateMachine.transition(PlaybackEvent.FirstChunkReady)
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        val receiver =
            BecomingNoisyReceiver(
                onNoiseDetected = {
                    if (stateMachine.state.value is PlaybackState.Playing) {
                        stateMachine.transition(PlaybackEvent.Pause)
                    }
                },
                registrar = fakeRegistrar,
            )
        receiver.register(dummyContext)

        // Disconnect triggers pause
        receiver.onActionReceived(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)

        // Headset plug in does NOT resume playback
        receiver.onActionReceived(Intent.ACTION_HEADSET_PLUG)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)

        // Bluetooth connect or other action does NOT resume playback
        receiver.onActionReceived(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    // -------------------------------------------------------------------------
    // Test Doubles
    // -------------------------------------------------------------------------

    private class FakeReceiverRegistrar : ReceiverRegistrar {
        var registerCount: Int = 0
        var unregisterCount: Int = 0
        var lastRegisteredReceiver: BroadcastReceiver? = null
        var lastRegisteredFilter: IntentFilter? = null
        var lastUnregisteredReceiver: BroadcastReceiver? = null
        var shouldThrowOnUnregister: RuntimeException? = null

        override fun registerReceiver(
            receiver: BroadcastReceiver,
            filter: IntentFilter,
        ) {
            registerCount++
            lastRegisteredReceiver = receiver
            lastRegisteredFilter = filter
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregisterCount++
            lastUnregisteredReceiver = receiver
            shouldThrowOnUnregister?.let { throw it }
        }
    }

    private class MockContext : ContextWrapper(null) {
        var registerCallCount = 0
        var unregisterCallCount = 0
        var registeredReceiver: BroadcastReceiver? = null
        var registeredFilter: IntentFilter? = null
        var unregisteredReceiver: BroadcastReceiver? = null
        var throwOnUnregister: RuntimeException? = null

        override fun getPackageName(): String = "com.redder"

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
        ): Intent? {
            registerCallCount++
            registeredReceiver = receiver
            registeredFilter = filter
            return null
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int,
        ): Intent? {
            registerCallCount++
            registeredReceiver = receiver
            registeredFilter = filter
            return null
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: android.os.Handler?,
        ): Intent? {
            registerCallCount++
            registeredReceiver = receiver
            registeredFilter = filter
            return null
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: android.os.Handler?,
            flags: Int,
        ): Intent? {
            registerCallCount++
            registeredReceiver = receiver
            registeredFilter = filter
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            unregisterCallCount++
            unregisteredReceiver = receiver
            throwOnUnregister?.let { throw it }
        }
    }

    private class FakeIntent(
        private val customAction: String? = null,
    ) : Intent() {
        override fun getAction(): String? = customAction
    }
}
