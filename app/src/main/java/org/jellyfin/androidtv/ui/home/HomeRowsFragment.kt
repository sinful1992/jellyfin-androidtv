package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The height of a home screen card. With a 16:9 box that puts four and a half of them across the
 * usable width of a 960dp screen, leaving the last one cut by the edge to say the row goes on.
 */
private const val HOME_CARD_HEIGHT = 104

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val playbackHelper by inject<PlaybackHelper>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val hero by lazy { HomeFragmentHeroRow(lifecycleScope, api, navigationRepository, playbackHelper) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// The hero is a row like any other as far as leanback is concerned, so that it scrolls away
		// under the cards rather than sitting over them, but it is not a row of cards and is not
		// presented like one.
		adapter = MutableObjectAdapter<Row>(ClassPresenterSelector().apply {
			addClassPresenter(HomeHeroRow::class.java, HomeHeroRowPresenter(hero::onAction))
			addClassPresenter(Row::class.java, PositionableListRowPresenter())
		})

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Start out with default sections
			val homesections = userSettingPreferences.activeHomesections

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Actually add the sections
			for (section in homesections) when (section) {
				HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded(userViewsRepository.views.first()))
				HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(HomeFragmentViewsRow(small = false))
				HomeSectionType.LIBRARY_BUTTONS -> rows.add(HomeFragmentViewsRow(small = true))
				HomeSectionType.RESUME -> rows.add(helper.loadResumeVideo())
				HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
				HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
				HomeSectionType.ACTIVE_RECORDINGS -> Unit // Live TV is not supported
				HomeSectionType.NEXT_UP -> rows.add(helper.loadNextUp())
				HomeSectionType.LIVE_TV -> Unit // Live TV is not supported

				HomeSectionType.NONE -> Unit
			}

			// Add sections to layout
			withContext(Dispatchers.Main) {
				// The home screen is a stack of mixed rows: Continue watching holds episodes next
				// to films, Next up holds series artwork next to episode stills. Sizing each card
				// from its own artwork gives those rows a ragged top and bottom edge and lets the
				// focused card grow over its neighbour. One wide box for all of them instead, at a
				// height that fits four and a half across so the row reads as continuing offscreen.
				val cardPresenter = CardPresenter(
					showInfo = true,
					imageType = ImageType.THUMB,
					staticHeight = HOME_CARD_HEIGHT,
					uniformAspect = false,
					fixedAspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				)

				// Add rows in order
				// The hero loads its own title and inserts itself at the top once it has one, so a
				// library with nothing new in it gets the rows on their own rather than a gap.
				hero.refresh(adapter as MutableObjectAdapter<Row>, onAdded = ::onHeroAdded)
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Wire up Live TV sibling rows so the On Now row removes the buttons row when empty
				@Suppress("UNCHECKED_CAST")
				val rowsAdapter = adapter as MutableObjectAdapter<Row>
				for (i in 0 until rowsAdapter.size()) {
				}
			}
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach {
						refreshRows(force = true, delayed = false)
						// Something has just been added, which is the one thing the hero is about.
						refreshHero()
					}
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
			refreshHero()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}
		}
	}

	/**
	 * Draw the hero's shortlist again.
	 *
	 * The row refresh above only reaches rows of cards, and the hero is not one, so it is asked
	 * separately. The title on show and the order of the strip both carry across the new list, so
	 * coming back from a title lands back on it rather than on whatever the shortlist was dealt
	 * next.
	 *
	 * The insert is worth watching for here as well as on the first draw. A library with nothing
	 * unplayed in it opens without a hero at all, and the refresh that follows the first thing
	 * being added is the one that puts it on the screen — an insert like any other, and it needs
	 * the focus moving onto it just the same.
	 */
	private fun refreshHero() {
		@Suppress("UNCHECKED_CAST")
		hero.refresh(adapter as MutableObjectAdapter<Row>, onAdded = ::onHeroAdded)
	}

	/**
	 * Put the viewer on the hero the moment it arrives.
	 *
	 * The hero fetches its shortlist over the network and only inserts itself at the top once it
	 * has one, by which time leanback has handed the focus to whatever row happened to be first —
	 * Continue watching, usually — and the insert simply pushes that row down a place with the
	 * focus still on it. So the app opened on a card in the second row down, which is not what the
	 * top of the screen is for.
	 *
	 * Only while the viewer is still at the top of the screen. Further down than that and they went
	 * there deliberately, so they are left where they are. The row the insert displaced counts as
	 * the top, which does mean a single press landing inside the load window is overridden — that
	 * window is a fraction of a second, and the alternative is opening on a card every time.
	 */
	private fun onHeroAdded() {
		if (selectedPosition > 1) return

		// Leanback holds the selection as a number, and the number did not change: it was already 0
		// and the rows simply moved down underneath it. So it believes the top row is selected
		// already, asking it to select position 0 does nothing at all, and the focus stays on the
		// card it was sitting on — which is now a row further down. It has to be taken by hand.
		//
		// Not here, though. The insert has only just reached the adapter and the row has no view
		// yet, so this waits for the frame the row is first laid out in and takes the focus then.
		val grid = verticalGridView ?: return
		grid.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
			override fun onPreDraw(): Boolean {
				// Nothing to hand it to yet — wait for the next frame rather than give up, because
				// the row is laid out a frame or two after the insert reaches the adapter.
				val hero = getRowViewHolder(0)?.view
				if (hero == null && grid.isAttachedToWindow) return true

				grid.viewTreeObserver.removeOnPreDrawListener(this)
				hero?.requestFocus()
				return true
			}
		})
	}

	/** Whether [row] is the one the viewer is on, rather than one still filling itself in below. */
	private fun isSelectedRow(row: Row?): Boolean {
		val position = selectedPosition
		if (position < 0 || position >= adapter.size()) return false
		return adapter.get(position) === row
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			// A row announces its first item as it finishes loading, whether or not it is the row
			// being looked at, so a home screen still filling in fires a burst of these from rows
			// nobody is on. Only the row actually selected has any business saying what the screen
			// is doing.
			if (!isSelectedRow(row)) return

			// The hero has no items in the leanback sense, so nothing is passed here when it is
			// selected — the row itself is what says the viewer has arrived on it. The card closes
			// when they leave, because leanback keeps the selected row a fixed distance down the
			// screen and moving off the hero scrolls it up without taking it away. See
			// HomeHeroRow.selected.
			hero.row?.setSelected(row is HomeHeroRow)

			if (row !is ListRow || item !is BaseRowItem) {
				currentItem = null
			} else {
				currentItem = item
				currentRow = row

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))
			}

			// Nothing on this screen puts a picture behind the app, the hero included: it draws its
			// own artwork inside its own card, on the same ground every row below it sits on. A
			// backdrop following the card under the pointer turned a walk along a row into a
			// slideshow, and a backdrop that only the top row set turned one press down into the
			// screen going black.
			backgroundService.clearBackgrounds()
		}
	}
}
