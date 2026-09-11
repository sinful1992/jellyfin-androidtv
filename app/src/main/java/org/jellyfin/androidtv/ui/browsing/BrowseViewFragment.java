package org.jellyfin.androidtv.ui.browsing;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.Row;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.constant.ChangeTriggerType;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.constant.QueryType;
import org.jellyfin.androidtv.ui.GridButton;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter;
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.CollectionType;
import org.jellyfin.sdk.model.api.request.GetNextUpRequest;
import org.koin.java.KoinJavaComponent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class BrowseViewFragment extends EnhancedBrowseFragment {

    @Override
    protected void setupQueries(final RowLoader rowLoader) {
        CollectionType type = mFolder != null && mFolder.getCollectionType() != null ? mFolder.getCollectionType() : CollectionType.UNKNOWN;
        switch (type) {
            case MOVIES:
                itemType = BaseItemKind.MOVIE;

                //Resume
                mRows.add(new BrowseRowDef(getString(R.string.lbl_continue_watching), BrowsingUtils.createResumeItemsRequest(mFolder.getId(), BaseItemKind.MOVIE), 0, new ChangeTriggerType[]{ChangeTriggerType.MoviePlayback}));

                //Latest
                mRows.add(new BrowseRowDef(getString(R.string.lbl_latest), BrowsingUtils.createLatestMediaRequest(mFolder.getId()), new ChangeTriggerType[]{ChangeTriggerType.MoviePlayback, ChangeTriggerType.LibraryUpdated}));

                //Favorites
                mRows.add(new BrowseRowDef(getString(R.string.lbl_favorites), BrowsingUtils.createFavoriteItemsRequest(mFolder.getId(), BaseItemKind.MOVIE), 60, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate}));

                //Collections
                mRows.add(new BrowseRowDef(getString(R.string.lbl_collections), BrowsingUtils.createCollectionsRequest(mFolder.getId()), 60, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated}));

                rowLoader.loadRows(mRows);
                break;
            case TVSHOWS:
                itemType = BaseItemKind.SERIES;

                //Resume
                mRows.add(new BrowseRowDef(getString(R.string.lbl_continue_watching), BrowsingUtils.createResumeItemsRequest(mFolder.getId(), BaseItemKind.EPISODE), 0, new ChangeTriggerType[]{ChangeTriggerType.TvPlayback}));

                //Next up
                GetNextUpRequest getNextUpRequest = BrowsingUtils.createGetNextUpRequest(mFolder.getId());
                mRows.add(new BrowseRowDef(getString(R.string.lbl_next_up), getNextUpRequest, new ChangeTriggerType[]{ChangeTriggerType.TvPlayback}));

                //Latest content added
                mRows.add(new BrowseRowDef(getString(R.string.lbl_latest), BrowsingUtils.createLatestMediaRequest(mFolder.getId(), BaseItemKind.EPISODE, true), new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated}));

                //Favorites
                mRows.add(new BrowseRowDef(getString(R.string.lbl_favorites), BrowsingUtils.createFavoriteItemsRequest(mFolder.getId(), BaseItemKind.SERIES), 60, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate}));

                rowLoader.loadRows(mRows);
                break;
            case MUSIC:
                //Latest
                mRows.add(new BrowseRowDef(getString(R.string.lbl_latest), BrowsingUtils.createLatestMediaRequest(mFolder.getId(), BaseItemKind.AUDIO, true), new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated}));

                //Last Played
                mRows.add(new BrowseRowDef(getString(R.string.lbl_last_played), BrowsingUtils.createLastPlayedRequest(mFolder.getId()), 0, false, true, new ChangeTriggerType[]{ChangeTriggerType.MusicPlayback, ChangeTriggerType.LibraryUpdated}));

                //Favorites
                mRows.add(new BrowseRowDef(getString(R.string.lbl_favorites), BrowsingUtils.createFavoriteItemsRequest(mFolder.getId(), BaseItemKind.MUSIC_ALBUM), 60, false, true, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate}));

                //AudioPlaylists
                mRows.add(new BrowseRowDef(getString(R.string.lbl_playlists), BrowsingUtils.createPlaylistsRequest(), 60, false, true, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated}, QueryType.AudioPlaylists));

                rowLoader.loadRows(mRows);
                break;
            default:
                break;
        }
    }

    @Override
    protected void addAdditionalRows(MutableObjectAdapter<Row> rowAdapter) {
        super.addAdditionalRows(rowAdapter);
    }
}
