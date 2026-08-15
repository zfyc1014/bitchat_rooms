package com.bitchat.android.ui

import android.os.Build
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelManagerTest {
    private lateinit var state: ChatState
    private lateinit var channelManager: ChannelManager

    @Before
    fun setUp() {
        AppStateStore.clear()
        state = ChatState(TestScope())
        channelManager = ChannelManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            coroutineScope = TestScope()
        )
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `switching into a channel clears in-channel private mode`() {
        state.setChannelPrivateModePeer("alice")
        state.setShowChannelPrivateModePicker(true)

        channelManager.switchToChannel("#general")

        assertNull(state.getChannelPrivateModePeerValue())
        assertFalse(state.getShowChannelPrivateModePickerValue())
    }

    @Test
    fun `switching away from a channel clears in-channel private mode`() {
        channelManager.switchToChannel("#general")
        state.setChannelPrivateModePeer("alice")

        channelManager.switchToChannel(null)

        assertNull(state.getChannelPrivateModePeerValue())
    }

    @Test
    fun `leaving the current channel clears in-channel private mode`() {
        channelManager.switchToChannel("#general")
        state.setChannelPrivateModePeer("alice")
        state.setShowChannelPrivateModePicker(true)

        channelManager.leaveChannel("#general")

        assertNull(state.getCurrentChannelValue())
        assertNull(state.getChannelPrivateModePeerValue())
        assertFalse(state.getShowChannelPrivateModePickerValue())
    }

    @Test
    fun `leaving a different channel keeps in-channel private mode intact`() {
        channelManager.switchToChannel("#general")
        state.setChannelPrivateModePeer("alice")

        channelManager.leaveChannel("#other")

        assertEquals("alice", state.getChannelPrivateModePeerValue())
    }

    @Test
    fun `clearing all channels clears in-channel private mode`() {
        channelManager.switchToChannel("#general")
        state.setChannelPrivateModePeer("alice")
        state.setShowChannelPrivateModePicker(true)

        channelManager.clearAllChannels()

        assertNull(state.getChannelPrivateModePeerValue())
        assertFalse(state.getShowChannelPrivateModePickerValue())
    }
}
