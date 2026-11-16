package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long the focus has to rest on a card before the top of the screen follows it. Long enough that
 * holding Right down a row fetches nothing on the way, short enough to feel immediate on a single press.
 */
private val FollowFocusDelay = 120.milliseconds

private val NoHeroRow: StateFlow<HomeHeroRow?> = MutableStateFlow(null)
private val NoFocusedItem: StateFlow<BaseItemDto?> = MutableStateFlow(null)

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		LaunchedEffect(rowsFocusRequester) { rowsFocusRequester.requestFocus() }

		var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }

		Box(modifier = Modifier.fillMaxSize()) {
			HomeTop(rowsSupportFragment) { shown, panel ->
				HomeBackdrop(item = shown)

				Column {
					MainToolbar(MainToolbarActiveButton.Home)

					panel()

					HomeRows(
						rowsFocusRequester = rowsFocusRequester,
						rowsSupportFragment = { rowsSupportFragment },
						onFragment = { rowsSupportFragment = it },
						modifier = Modifier
							.fillMaxWidth()
							.weight(1f),
					)
				}
			}
		}
	}

	@Composable
	private fun HomeRows(
		rowsFocusRequester: FocusRequester,
		rowsSupportFragment: () -> HomeRowsFragment?,
		onFragment: (HomeRowsFragment) -> Unit,
		modifier: Modifier,
	) {
		// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
		// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
		// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
		AndroidFragment<HomeRowsFragment>(
			modifier = modifier
				.focusGroup()
				.focusRequester(rowsFocusRequester)
				.focusProperties {
					onExit = {
						val isFirstRowSelected = rowsSupportFragment()?.selectedPosition?.let { it <= 0 } ?: false
						if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
							cancelFocusChange()
						} else {
							rowsSupportFragment()?.selectedPosition = 0
							rowsSupportFragment()?.verticalGridView?.clearFocus()
						}
					}
				},
			maxLifecycle = Lifecycle.State.RESUMED,
			onUpdate = onFragment,
		)
	}

	/**
	 * Works out what the top of the screen shows and hands it to [content], with the fixed info block
	 * as `panel` for the content to place.
	 *
	 * The hero's title while the hero row is the one selected; otherwise the focused card's, once the
	 * focus has rested on it for [FollowFocusDelay].
	 */
	@Composable
	private fun HomeTop(
		rows: HomeRowsFragment?,
		content: @Composable (shown: BaseItemDto?, panel: @Composable () -> Unit) -> Unit,
	) {
		val heroRow by (rows?.heroRow ?: NoHeroRow).collectAsState()
		val focusedItem by (rows?.focusedItem ?: NoFocusedItem).collectAsState()

		val row = heroRow
		val selection = row?.selection?.collectAsState()?.value
		val heroSelected = row?.selected?.collectAsState()?.value ?: false
		val focus = row?.focus?.collectAsState()?.value ?: HomeHeroFocus.Play
		val focused = row?.focused?.collectAsState()?.value ?: false

		val heroActive = selection != null && heroSelected
		val target = if (heroActive) selection?.item else focusedItem

		var shown by remember { mutableStateOf<BaseItemDto?>(null) }
		LaunchedEffect(target?.id, heroActive) {
			if (!heroActive) delay(FollowFocusDelay)
			shown = homeHeroNextShown(target, shown)
		}

		content(shown) {
			HomeHeroPanel(
				item = shown,
				selection = selection,
				heroActive = heroActive,
				focus = focus,
				focused = focused,
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}
}
