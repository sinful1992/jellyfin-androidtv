@file:JvmName("JavaCompat")

package org.jellyfin.androidtv.util.sdk.compat

import org.jellyfin.sdk.model.api.BaseItemDto
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

val BaseItemDto.canResume get() = (userData?.playbackPositionTicks ?: 0) > 0
