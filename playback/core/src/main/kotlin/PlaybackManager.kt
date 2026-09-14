package org.jellyfin.playback.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jellyfin.playback.core.backend.BackendService
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.plugin.PlayerService
import timber.log.Timber
import kotlin.reflect.KClass

class PlaybackManager internal constructor(
	val backend: PlayerBackend,
	private val services: MutableList<PlayerService>,
	val options: PlaybackManagerOptions,
	parentJob: Job? = null,
) {
	private companion object {
		/**
		 * How many events may wait for a collector. Enough for a run of unplayable entries to be
		 * reported in order, small enough that a stale one cannot surface much later.
		 */
		private const val EVENT_BUFFER_CAPACITY = 8
	}

	internal val backendService = BackendService(backend)

	private val _events = MutableSharedFlow<PlaybackEvent>(
		extraBufferCapacity = EVENT_BUFFER_CAPACITY,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	/**
	 * Things that happened during playback which the user should be told about.
	 *
	 * There are no replays and the buffer is small: an event nobody was listening for is dropped
	 * rather than shown late, because a message about an item that was skipped several items ago
	 * is worse than no message.
	 */
	val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

	internal fun emitEvent(event: PlaybackEvent) {
		_events.tryEmit(event)
	}

	private val job = SupervisorJob(parentJob)
	val state: PlayerState = MutablePlayerState(
		options = options,
		backendService = backendService,
		queue = getService()
	)

	init {
		services.forEach { it.initialize(this, state, Job(job)) }
	}

	fun addService(service: PlayerService) {
		Timber.i("Adding service $service")
		service.initialize(this, state, Job(job))
		services.add(service)
	}

	fun <T : PlayerService> getService(kclass: KClass<T>): T? {
		for (service in services) {
			@Suppress("UNCHECKED_CAST")
			if (kclass.isInstance(service)) return service as T
		}
		return null
	}

	inline fun <reified T : PlayerService> getService() = getService(T::class)

	fun removeService(service: PlayerService) {
		Timber.i("Removing service $service")
		service.coroutineScope.cancel()
		services.remove(service)
	}
}
