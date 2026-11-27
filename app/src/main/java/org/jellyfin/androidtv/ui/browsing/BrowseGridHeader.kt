package org.jellyfin.androidtv.ui.browsing

import androidx.recyclerview.widget.RecyclerView

/**
 * Whether the grid header should hold its info update: true while the grid is still sliding, so the
 * update's layout change lands after the slide instead of on one of its frames (task 198).
 */
fun headerWaitsForRest(scrollState: Int): Boolean = scrollState != RecyclerView.SCROLL_STATE_IDLE
