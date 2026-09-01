package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.element.elementFlow
import org.jellyfin.playback.core.queue.QueueEntry

private val selectedAudioStreamIndexKey = ElementKey<Int>("SelectedAudioStreamIndex")
private val selectedSubtitleStreamIndexKey = ElementKey<Int>("SelectedSubtitleStreamIndex")

/**
 * Get or set the audio track the user picked for this [QueueEntry], as a media source index. A null
 * value means no explicit choice was made and the server default applies.
 *
 * Setting this does not change what is playing. The stream has to be resolved again for the change
 * to take effect.
 */
var QueueEntry.selectedAudioStreamIndex by element(selectedAudioStreamIndexKey)

/**
 * Get the flow of [selectedAudioStreamIndex].
 * @see selectedAudioStreamIndex
 */
val QueueEntry.selectedAudioStreamIndexFlow by elementFlow(selectedAudioStreamIndexKey)

/**
 * Get or set the subtitle track the user picked for this [QueueEntry], as a media source index.
 * A null value means no explicit choice was made and the server default applies;
 * [MediaStreamSubtitleTrack.INDEX_NONE] means subtitles were explicitly turned off.
 *
 * Setting this does not change what is playing. The stream has to be resolved again for the change
 * to take effect.
 */
var QueueEntry.selectedSubtitleStreamIndex by element(selectedSubtitleStreamIndexKey)

/**
 * Get the flow of [selectedSubtitleStreamIndex].
 * @see selectedSubtitleStreamIndex
 */
val QueueEntry.selectedSubtitleStreamIndexFlow by elementFlow(selectedSubtitleStreamIndexKey)
