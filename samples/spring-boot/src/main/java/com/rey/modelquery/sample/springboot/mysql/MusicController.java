package com.rey.modelquery.sample.springboot.mysql;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recipe 8: two models, each enriched by the same cross-datasource profile declaration. */
@RestController
class MusicController {

    private final MusicService music;

    MusicController(MusicService music) {
        this.music = music;
    }

    /** Every song as a {@link SongView} with its profile. */
    @GetMapping("/songs")
    List<SongView> songs() {
        return music.all();
    }

    /** Every song as a {@link SongCreditView}, enriched by the same declaration. */
    @GetMapping("/songs/credits")
    List<SongCreditView> credits() {
        return music.credits();
    }
}
