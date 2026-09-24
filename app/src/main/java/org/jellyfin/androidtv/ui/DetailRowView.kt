package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.widget.FrameLayout
import org.jellyfin.androidtv.databinding.ViewRowDetailsBinding
import org.jellyfin.androidtv.util.coil.EdgeFadeTransformation

class DetailRowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	val binding = ViewRowDetailsBinding.inflate(LayoutInflater.from(context), this, true)

	/**
	 * Keeps track of the last selected button and reselect it when navigating back to the buttons row.
	 */
	private val buttonsHierarchyChangeListener = object : OnHierarchyChangeListener {

		private var lastFocusedButton: View? = null

		private val focusChangeListener = OnFocusChangeListener { view, hasFocus ->
			// Restore last focused button when navigating back to button row
			if (hasFocus && lastFocusedButton != null) {
				lastFocusedButton?.requestFocus()
				lastFocusedButton = null
			}
			view.post {
				// Store last focused button when navigating away from button row
				if (binding.fdButtonRow.focusedChild == null) {
					lastFocusedButton = view
				}
			}
		}

		override fun onChildViewAdded(parent: View?, child: View?) {
			child?.onFocusChangeListener = focusChangeListener
		}

		override fun onChildViewRemoved(parent: View?, child: View?) {
			child?.onFocusChangeListener = null
		}
	}

	init {
		binding.fdButtonRow.setOnHierarchyChangeListener(buttonsHierarchyChangeListener)
		binding.mainImage.clipToOutline = true

		// Transparent until a fifth of the way in and solid from about three fifths: the
		// description stops (details_main_end_with_art) just past where the picture starts. The
		// bottom half fades out before the rows below, and the top is held down to about a third
		// where the clock and the home button sit over it (neither reads on a bright picture at
		// half strength; the home button has no shadow at all).
		binding.fdBackdrop.transformation = EdgeFadeTransformation(
			leftClear = 0.18f,
			leftSolid = 0.62f,
			bottomSolid = 0.55f,
			topAlpha = 0.15f,
			topSolid = 0.35f,
		)
	}
}
