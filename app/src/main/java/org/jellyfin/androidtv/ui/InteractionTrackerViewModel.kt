package org.jellyfin.androidtv.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import kotlin.time.Duration.Companion.milliseconds

class InteractionTrackerViewModel(
	private val userPreferences: UserPreferences,
) : ViewModel() {
	// Screensaver

	private var timer: Job? = null
	private var locks = 0

	// Preferences

	private val inAppEnabled get() = userPreferences[UserPreferences.screensaverInAppEnabled]
	private val timeout get() = userPreferences[UserPreferences.screensaverInAppTimeout].milliseconds

	// State

	private val _screensaverVisible = MutableStateFlow(false)
	val visible get() = _screensaverVisible.asStateFlow()

	private val _keepScreenOn = MutableStateFlow(inAppEnabled)
	val keepScreenOn get() = _keepScreenOn.asStateFlow()

	var activityPaused: Boolean = false
		set(value) {
			field = value
			notifyInteraction(canCancel = true)
		}

	init {
		notifyInteraction(canCancel = true)
	}

	fun notifyInteraction(canCancel: Boolean) {
		// Cancel pending screensaver timer (if any)
		timer?.cancel()

		// Hide screensaver when interacted with allowed cancellation or when disabled
		if (_screensaverVisible.value && (canCancel || !inAppEnabled || activityPaused)) {
			_screensaverVisible.value = false
		}

		// Create new timer to show screensaver when enabled
		if (inAppEnabled && !activityPaused && locks == 0) {
			timer = viewModelScope.launch {
				delay(timeout)
				_screensaverVisible.value = true
			}
		}

		// Update KEEP_SCREEN_ON flag value
		_keepScreenOn.value = inAppEnabled || locks > 0
	}

	/**
	 * Create a lock that prevents the screensaver for running until the returned function is called
	 * or the lifecycle is destroyed.
	 *
	 * @return Function to cancel the lock
	 */
	fun addLifecycleLock(lifecycle: Lifecycle): () -> Unit {
		if (lifecycle.currentState == Lifecycle.State.DESTROYED) return {}

		val lock = ScreensaverLock(lifecycle)
		lock.activate()
		return lock::cancel
	}

	private inner class ScreensaverLock(private val lifecycle: Lifecycle) : LifecycleEventObserver {
		private var active: Boolean = false

		override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
			if (event == Lifecycle.Event.ON_DESTROY) cancel()
		}

		fun activate() {
			if (active) return
			lifecycle.addObserver(this)
			locks++
			notifyInteraction(canCancel = true)
			active = true
		}

		fun cancel() {
			if (!active) return
			locks--
			lifecycle.removeObserver(this)
			notifyInteraction(canCancel = false)
			active = false
		}
	}
}
