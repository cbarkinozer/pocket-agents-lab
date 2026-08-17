package com.pocketagentslab

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import org.json.JSONObject

class PocketNotificationListener : NotificationListenerService()

internal fun activeMediaController(context: Context): MediaController? {
    val manager = context.getSystemService(MediaSessionManager::class.java)
    val listener = ComponentName(context, PocketNotificationListener::class.java)
    return runCatching { manager.getActiveSessions(listener) }.getOrNull()
        ?.sortedByDescending { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        ?.firstOrNull()
}

internal fun getMediaInfo(context: Context): JSONObject {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty().contains(context.packageName)
    if (!enabled) {
        return JSONObject()
            .put("available", false)
            .put("reason", "Notification Access is required to read the active media session")
    }
    val controller = activeMediaController(context)
        ?: return JSONObject().put("available", true).put("active", false)
    val metadata = controller.metadata
    return JSONObject()
        .put("available", true)
        .put("active", true)
        .put("package", controller.packageName)
        .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
        .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
        .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
        .put("state", controller.playbackState?.state ?: 0)
}

internal fun controlActiveMedia(context: Context, action: String) {
    val controller = activeMediaController(context)
        ?: error("No active media session is available. Enable Notification Access and start media first.")
    when (action) {
        MEDIA_PLAY_PAUSE -> if (
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        ) controller.transportControls.pause() else controller.transportControls.play()
        MEDIA_NEXT -> controller.transportControls.skipToNext()
        MEDIA_PREVIOUS -> controller.transportControls.skipToPrevious()
        else -> error("Unsupported media action: $action")
    }
}
