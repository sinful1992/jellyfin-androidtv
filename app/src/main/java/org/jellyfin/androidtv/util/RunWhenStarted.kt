package org.jellyfin.androidtv.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Runs UI work at once while [lifecycle] is started, and otherwise keeps the newest piece of work until the
 * next start instead of dropping it.
 *
 * A details page opened while the app is stopped (the TV switched off during playback) got its item from the
 * server while it was not started, threw it away, and stayed black with an empty list until Back (task 148).
 */
class RunWhenStarted(private val lifecycle: Lifecycle) : DefaultLifecycleObserver {
	private var held: (() -> Unit)? = null

	val isHeld: Boolean get() = held != null

	init {
		lifecycle.addObserver(this)
	}

	/** Run [action] now when started; else hold it, replacing anything held before, until the next start. */
	fun run(action: () -> Unit) {
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
			held = null
			action()
		} else if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
			held = action
		}
	}

	/** Forget held work, for a page that builds its view again and loads afresh. */
	fun clear() {
		held = null
	}

	override fun onStart(owner: LifecycleOwner) {
		val action = held ?: return
		held = null
		action()
	}

	override fun onDestroy(owner: LifecycleOwner) {
		held = null
	}
}
