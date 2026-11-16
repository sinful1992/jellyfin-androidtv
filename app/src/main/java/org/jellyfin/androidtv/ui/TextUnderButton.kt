package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import org.jellyfin.androidtv.R
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import org.jellyfin.androidtv.databinding.TextUnderButtonBinding
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.dp

class TextUnderButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	val binding = TextUnderButtonBinding.inflate(LayoutInflater.from(context), this, true)

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		if (AndroidVersion.isAtLeastO) defaultFocusHighlightEnabled = false
	}

	fun setLabel(text: String?) {
		binding.label.isVisible = text != null
		binding.label.text = text
		binding.imageButton.contentDescription = text
	}

	fun setIcon(@DrawableRes resource: Int, maxHeight: Int? = null) {
		binding.imageButton.setImageResource(resource)
		binding.imageButton.maxHeight = maxHeight ?: Int.MAX_VALUE
	}

	fun setPadding(padding: Int?) {
		binding.imageButton.setPadding(padding?.dp(context) ?: 0)
	}

	/**
	 * Draw as a pill: icon and label side by side on one line, white when focused. The details page
	 * uses this so its few buttons read as actions on the art rather than a toolbar of grey boxes.
	 */
	fun setPill() {
		val height = 36.dp(context)
		background = ContextCompat.getDrawable(context, R.drawable.details_pill_button)
		val content = ContextCompat.getColorStateList(context, R.color.details_pill_content)

		binding.root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, height, Gravity.CENTER_VERTICAL)
		binding.root.setPadding(16.dp(context), 0, 18.dp(context), 0)

		binding.imageButton.apply {
			background = null
			setPadding(0)
			maxHeight = Int.MAX_VALUE
			layoutParams = RelativeLayout.LayoutParams(16.dp(context), 16.dp(context)).apply {
				addRule(RelativeLayout.CENTER_VERTICAL)
				addRule(RelativeLayout.ALIGN_PARENT_START)
			}
			ImageViewCompat.setImageTintList(this, content)
		}

		binding.label.apply {
			layoutParams = RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.WRAP_CONTENT,
				RelativeLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				addRule(RelativeLayout.END_OF, R.id.imageButton)
				addRule(RelativeLayout.CENTER_VERTICAL)
				marginStart = 7.dp(context)
			}
			alpha = 1f
			maxLines = 1
			setPadding(0, 0, 0, 0)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
			setTextColor(content)
		}
	}

	companion object {
		@JvmStatic
		@Suppress("LongParameterList")
		fun create(
			context: Context,
			@DrawableRes icon: Int,
			maxHeight: Int? = null,
			padding: Int? = null,
			label: String? = null,
			onClickListener: OnClickListener
		) = TextUnderButton(context).apply {
			setLabel(label)
			setIcon(icon, maxHeight)
			setPadding(padding)
			setOnClickListener(onClickListener)
		}
	}
}
