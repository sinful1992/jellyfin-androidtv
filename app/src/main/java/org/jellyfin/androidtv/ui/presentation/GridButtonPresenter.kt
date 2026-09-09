package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.item.ItemCardUnfocusedScrim
import org.jellyfin.androidtv.ui.composable.item.itemCardFocusRing
import org.jellyfin.androidtv.ui.composable.item.cardFocusScale
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem

class GridButtonPresenter @JvmOverloads constructor(
	private val width: Int = 110,
	private val imageHeight: Int = 110,
) : Presenter() {
	private class ComposeViewWrapper(composeView: ComposeView) : FrameLayout(composeView.context) {
		init {
			isFocusable = true
			isFocusableInTouchMode = true
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			addView(composeView)
		}

		// Hack to prevent Compose crash with leanback presenters
		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			if (isAttachedToWindow) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			else setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
		}
	}

	inner class ViewHolder(
		composeView: ComposeView,
	) : Presenter.ViewHolder(ComposeViewWrapper(composeView)) {
		private val _button = MutableStateFlow<GridButton?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val button by _button.collectAsState()
				val focused by _focused.collectAsState()

				button?.let { value -> Content(value, focused) }
			}

			// Leanback no longer zooms anything (see card_scale_focus in dimens.xml), so a tile
			// that does not draw its own focus state shows nothing at all when it is focused.
			// The wrapper is what takes focus, not the ComposeView inside it — the wrapper blocks
			// its descendants — so this listener has to go on the wrapper.
			_focused.value = view.isFocused
			view.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(value: GridButton) {
			_button.value = value
			_focused.value = view.isFocused
		}

		fun unbind() {
			_button.value = null
			_focused.value = false
		}

		@Composable
		private fun Content(value: GridButton, focused: Boolean) {
			val shape = RoundedCornerShape(4.dp)

			Box(
				modifier = Modifier
					.width(width.dp)
					.cardFocusScale(focused)
					.clip(shape)
					.background(colorResource(R.color.button_default_normal_background))
					.itemCardFocusRing(focused = focused, shape = shape)
			) {
				if (value.imageRes != null) {
					Image(
						painter = painterResource(value.imageRes),
						contentDescription = value.text,
						contentScale = ContentScale.Crop,
						modifier = Modifier.size(width.dp, imageHeight.dp)
					)
				}

				// Over the image, under the label: the tile is mostly its label, and dimming that
				// would take the word away rather than push the picture back. Sized to the image
				// rather than to the tile, because the tile's own height is whatever its content
				// comes to and there is nothing here to ask for it.
				ItemCardUnfocusedScrim(
					focused = focused,
					modifier = Modifier.size(width.dp, imageHeight.dp),
				)

				Text(
					text = value.text,
					style = JellyfinTheme.typography.label.copy(
						color = colorResource(R.color.button_default_normal_text),
					),
					modifier = Modifier
						.padding(15.dp, 10.dp)
						.align(Alignment.BottomStart)
				)
			}
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			isFocusable = true
		}

		return ViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return

		when (item) {
			is GridButtonBaseRowItem -> viewHolder.bind(item.gridButton)
			is GridButton -> viewHolder.bind(item)
		}
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
		if (viewHolder !is ViewHolder) return

		viewHolder.unbind()
	}

	override fun onViewAttachedToWindow(viewHolder: Presenter.ViewHolder) = Unit
}
