package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.leanback.widget.RowPresenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.DetailRowView
import org.jellyfin.androidtv.ui.home.HeroMetaLine
import org.jellyfin.androidtv.ui.home.homeHeroMetaOf
import org.jellyfin.androidtv.ui.itemdetail.MyDetailsOverviewRow
import org.jellyfin.androidtv.util.InfoLayoutHelper
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

class MyDetailsOverviewRowPresenter(
	private val markdownRenderer: MarkdownRenderer,
) : RowPresenter() {
	class ViewHolder(
		private val detailRowView: DetailRowView,
		private val markdownRenderer: MarkdownRenderer,
	) : RowPresenter.ViewHolder(detailRowView) {
		private val binding get() = detailRowView.binding

		/** The details page's "Ends" time for the one-line meta, kept current by the page's clock. */
		private var endsText by mutableStateOf<String?>(null)
		private var metaItem by mutableStateOf<BaseItemDto?>(null)

		/**
		 * The one-line meta shown with the art: year, runtime, rating, stars, ends. Created once and
		 * reused, so a rebind does not start a new composition.
		 */
		private val metaView by lazy {
			ComposeView(view.context).apply {
				setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
				setContent {
					val item = metaItem
					if (item != null) {
						val meta = homeHeroMetaOf(item)
						HeroMetaLine(meta = meta, episodeName = null, trailing = endsText)
					}
				}
			}
		}

		fun setItem(row: MyDetailsOverviewRow) {
			setTitle(row.item.name)

			val withArt = row.backdropUrl != null
			val endsLabel = view.context.getString(R.string.lbl_ends)
			endsText = row.infoItem3?.takeIf { it.label == endsLabel }?.value?.let { "$endsLabel $it" }

			if (withArt) {
				// Replaces the row of chips (tomato score, full date, codec badges) with one line.
				if (metaView.parent !== binding.fdMainInfoRow) {
					binding.fdMainInfoRow.removeAllViews()
					(metaView.parent as? ViewGroup)?.removeView(metaView)
					binding.fdMainInfoRow.addView(metaView)
				}
				metaItem = row.item
				binding.fdGenreRow.text = row.item.genres?.take(DETAILS_GENRES_WITH_ART)?.joinToString(", ")
			} else {
				if (metaView.parent === binding.fdMainInfoRow) binding.fdMainInfoRow.removeView(metaView)
				metaItem = null
				InfoLayoutHelper.addInfoRow(view.context, row.item, row.item.mediaSources?.getOrNull(row.selectedMediaSourceIndex), binding.fdMainInfoRow, false)
				binding.fdGenreRow.text = row.item.genres?.joinToString(" / ")
			}

			binding.infoTitle1.text = row.infoItem1?.label
			binding.infoValue1.text = row.infoItem1?.value

			binding.infoTitle2.text = row.infoItem2?.label
			binding.infoValue2.text = row.infoItem2?.value

			binding.infoTitle3.text = row.infoItem3?.label
			binding.infoValue3.text = row.infoItem3?.value

			setArtwork(row)

			setSummary(row.summary)
			binding.fdSummaryText.maxLines = if (withArt) DETAILS_SUMMARY_LINES_WITH_ART else DETAILS_SUMMARY_LINES

			if (row.item.type == BaseItemKind.PERSON) {
				binding.fdSummaryText.maxLines = 9
				binding.fdGenreRow.isVisible = false
			}

			binding.fdButtonRow.removeAllViews()
			for (button in row.actions) {
				val parent = button.parent
				if (parent is ViewGroup) parent.removeView(button)

				binding.fdButtonRow.addView(button)
			}
		}

		/**
		 * With a picture, it fills the top right, the logo moves into the title's place and the
		 * text column narrows to stop where the picture fades in. Without one the page is laid out
		 * as it always was, the logo or primary image in the right-hand column. Every view is set
		 * both ways on every bind: the row is bound again when the page refreshes.
		 */
		private fun setArtwork(row: MyDetailsOverviewRow) {
			val resources = view.resources
			val backdrop = row.backdropUrl
			val logo = row.logoUrl.takeIf { backdrop != null }
			val episode = row.item.type == BaseItemKind.EPISODE

			// With art the text runs from the page's left margin: the Directed by / Runs / Ends gutter
			// goes, and Ends moves into the meta line.
			binding.guideMainStart.setGuidelineBegin(
				resources.getDimensionPixelSize(
					if (backdrop != null) R.dimen.details_main_start_with_art else R.dimen.details_main_start
				)
			)
			for (info in listOf(
				binding.infoTitle1, binding.infoValue1,
				binding.infoTitle2, binding.infoValue2,
				binding.infoTitle3, binding.infoValue3,
			)) info.isVisible = backdrop == null
			// Every details button is a pill, with or without art, so they always need a gap.
			binding.fdButtonRow.showDividers = android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE

			binding.fdBackdrop.isVisible = backdrop != null
			if (backdrop != null) binding.fdBackdrop.load(backdrop)
			else binding.fdBackdrop.setImageDrawable(null)

			binding.mainImage.isVisible = backdrop == null
			if (backdrop == null) binding.mainImage.load(row.imageDrawable, null, null, 1.0, 0)
			else binding.mainImage.setImageDrawable(null)

			val mainEndWithArt = resources.getDimensionPixelSize(R.dimen.details_main_end_with_art)
			binding.guideMainEnd.setGuidelineEnd(
				if (backdrop != null) mainEndWithArt else resources.getDimensionPixelSize(R.dimen.details_main_end)
			)
			binding.guideTitleEnd.setGuidelineEnd(
				if (backdrop != null) mainEndWithArt else resources.getDimensionPixelSize(R.dimen.details_title_end)
			)

			// An episode keeps its own name under the series logo; anything else is named by it.
			binding.fdLogo.isVisible = logo != null
			binding.fdLogo.contentDescription = row.item.seriesName.takeIf { episode } ?: row.item.name
			binding.fdLogo.updateLayoutParams {
				height = resources.getDimensionPixelSize(
					if (episode) R.dimen.details_logo_height_above_title else R.dimen.details_logo_height
				)
			}
			if (logo != null) binding.fdLogo.load(logo)
			else binding.fdLogo.setImageDrawable(null)

			binding.fdTitle.isVisible = logo == null || episode
			// Under the series logo an episode title gets one line: two would push the logo up
			// against the top of the screen. The full name is still on the episode's card.
			binding.fdTitle.maxLines = if (logo != null && episode) 1 else 2
		}

		fun setTitle(title: String?) {
			binding.fdTitle.text = title
		}

		fun setSummary(summary: String?) {
			binding.fdSummaryText.text = summary?.let { markdownRenderer.toMarkdownSpanned(it) }
		}

		fun setInfoValue3(text: String?) {
			if (endsText != null) endsText = text?.let { "${view.context.getString(R.string.lbl_ends)} $it" }
			binding.infoValue3.text = text
		}
	}

	var viewHolder: ViewHolder? = null
		private set

	init {
		syncActivatePolicy = SYNC_ACTIVATED_CUSTOM
	}

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
		val view = DetailRowView(parent.context)
		viewHolder = ViewHolder(view, markdownRenderer)
		return viewHolder!!
	}

	override fun onBindRowViewHolder(viewHolder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(viewHolder, item)
		if (item !is MyDetailsOverviewRow) return
		if (viewHolder !is ViewHolder) return

		viewHolder.setItem(item)
	}

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit
}

private const val DETAILS_GENRES_WITH_ART = 3
private const val DETAILS_SUMMARY_LINES_WITH_ART = 4
private const val DETAILS_SUMMARY_LINES = 6
