@file:JvmName("JavaCompat")

package org.jellyfin.androidtv.util.sdk.compat

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.UserItemDataDto

fun BaseItemDto.copyWithDisplayPreferencesId(
	displayPreferencesId: String?
) = copy(
	displayPreferencesId = displayPreferencesId,
)

fun BaseItemDto.copyWithUserData(
	userData: UserItemDataDto?,
) = copy(
	userData = userData,
)

fun MediaSourceInfo.getVideoStream() = mediaStreams?.firstOrNull {
	it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO
}

val BaseItemDto.canResume get() = (userData?.playbackPositionTicks ?: 0) > 0
