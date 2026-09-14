package org.jellyfin.playback.core.backend

import androidx.core.view.doOnDetach
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView

/**
 * Service keeping track of the current playback backend and its related surface view.
 *
 * A backend is required to build one and switching replaces it, so there is always exactly one.
 * It used to be null until the manager's initialiser switched the first one in, which left every
 * caller writing a null check for a window no caller could observe.
 */
class BackendService(initialBackend: PlayerBackend) {
	private var _backend: PlayerBackend = initialBackend
	val backend: PlayerBackend get() = _backend

	private var listeners = mutableListOf<PlayerBackendEventListener>()
	private var _surfaceView: PlayerSurfaceView? = null
	private var _subtitleView: PlayerSubtitleView? = null

	init {
		attach(initialBackend)
	}

	fun switchBackend(backend: PlayerBackend) {
		_backend.stop()
		_backend.setListener(null)
		_backend.setSurfaceView(null)
		_backend.setSubtitleView(null)

		_backend = attach(backend)
	}

	private fun attach(backend: PlayerBackend) = backend.apply {
		_surfaceView?.let(::setSurfaceView)
		_subtitleView?.let(::setSubtitleView)
		setListener(BackendEventListener())
	}

	fun attachSurfaceView(surfaceView: PlayerSurfaceView) {
		// Remove existing surface view
		if (_surfaceView != null) {
			_backend.setSurfaceView(null)
		}

		// Apply new surface view
		_surfaceView = surfaceView.apply {
			_backend.setSurfaceView(surfaceView)

			// Automatically detach
			doOnDetach {
				if (surfaceView == _surfaceView) {
					_surfaceView = null
					_backend.setSurfaceView(null)
				}
			}
		}
	}

	fun attachSubtitleView(subtitleView: PlayerSubtitleView) {
		// Remove existing surface view
		if (_subtitleView != null) {
			_backend.setSubtitleView(null)
		}

		// Apply new surface view
		_subtitleView = subtitleView.apply {
			_backend.setSubtitleView(subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _subtitleView) {
					_subtitleView = null
					_backend.setSubtitleView(null)
				}
			}
		}
	}

	fun addListener(listener: PlayerBackendEventListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: PlayerBackendEventListener) {
		listeners.remove(listener)
	}

	inner class BackendEventListener : PlayerBackendEventListener() {
		private fun <T> callListeners(
			body: PlayerBackendEventListener.() -> T
		): List<T> = listeners.map { listener -> listener.body() }

		override fun onPlayStateChange(state: PlayState) {
			callListeners { onPlayStateChange(state) }
		}

		override fun onVideoSizeChange(width: Int, height: Int) {
			callListeners { onVideoSizeChange(width, height) }
		}

		override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
			callListeners { onMediaStreamEnd(mediaStream) }
		}
	}
}
