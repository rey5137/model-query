package com.rey.modelquery.sample.springboot.mysql;

import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Recipe 8: the one generic enricher declaration built twice, once per model, over the songs' own view
 * {@link SongView} and the credit view {@link SongCreditView}, both reading profiles from h2 (D-111 item 8).
 */
@Service
public class MusicService {

    private final SongRepository songs;
    private final Enricher<SongView> songProfile;
    private final Enricher<SongCreditView> creditProfile;

    public MusicService(SongRepository songs, ProfileEnrichers profiles) {
        this.songs = songs;
        this.songProfile = profiles.<SongView>profileOf(song -> UserRef.of(song.userId(), song.userTypeId()),
                SongView::withProfile, QSongView.USER_ID, QSongView.USER_TYPE_ID);
        this.creditProfile = profiles.<SongCreditView>profileOf(credit -> UserRef.of(credit.userId(),
                credit.userTypeId()), SongCreditView::withProfile,
                QSongCreditView.USER_ID, QSongCreditView.USER_TYPE_ID);
    }

    /** Every song, each row's profile filled from h2 when one exists. */
    public List<SongView> all() {
        var query = QSongView.query().orderBy(QSongView.ID.asc())
                .fetch(FetchPlan.of(QSongView.ALL).enrich(songProfile))
                .build();
        return songs.findAll(query, Limit.unlimited());
    }

    /** Every song as a credit row, enriched by the same declaration through the factory. */
    public List<SongCreditView> credits() {
        var query = QSongCreditView.query().orderBy(QSongCreditView.ID.asc())
                .fetch(FetchPlan.of(QSongCreditView.ALL).enrich(creditProfile))
                .build();
        return songs.findAll(query, Limit.unlimited());
    }
}
