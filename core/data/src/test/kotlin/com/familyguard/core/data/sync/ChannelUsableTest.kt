package com.familyguard.core.data.sync

import io.github.jan.supabase.realtime.RealtimeChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: signing out drops the realtime websocket
 * (`RealtimeImpl.disconnectOnSessionLoss`), and that flips **every** channel
 * to UNSUBSCRIBED. The cached channel is then dead, so [SyncEngine] has to
 * build a fresh one on the next sign-in instead of reusing it — otherwise the
 * parent dashboard is stuck on "Sync: reconnecting…" for the rest of the
 * process's life.
 */
class ChannelUsableTest {

    @Test
    fun subscribedChannelIsReused() {
        assertTrue(channelUsable(RealtimeChannel.Status.SUBSCRIBED))
    }

    @Test
    fun subscribingChannelIsReused() {
        assertTrue(channelUsable(RealtimeChannel.Status.SUBSCRIBING))
    }

    @Test
    fun unsubscribedChannelIsRebuilt() {
        assertFalse(channelUsable(RealtimeChannel.Status.UNSUBSCRIBED))
    }

    @Test
    fun unsubscribingChannelIsRebuilt() {
        assertFalse(channelUsable(RealtimeChannel.Status.UNSUBSCRIBING))
    }

    @Test
    fun missingChannelIsBuilt() {
        assertFalse(channelUsable(null))
    }
}
