package com.rey.modelquery.sample.springboot.mysql;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.sample.springboot.h2.ProfileRepository;
import com.rey.modelquery.sample.springboot.h2.ProfileView;
import com.rey.modelquery.sample.springboot.h2.QProfileView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Recipe 8: the one profile lookup and the one generic enricher declaration. {@link #profileOf} builds an enricher
 * for whatever model it is given, so the same declaration serves {@link SongView} and {@link SongCreditView}
 * (D-111 item 8).
 */
@Component
public class ProfileEnrichers {

    private final ProfileRepository profiles;
    private final ProfileLookupCounter counter;

    public ProfileEnrichers(ProfileRepository profiles, ProfileLookupCounter counter) {
        this.profiles = profiles;
        this.counter = counter;
    }

    /**
     * The declaration recipe 8 reuses across models: each model's key routes into its profile field, the one lookup
     * serves them all, and {@code batchSize(2)} splits the keys into chunks of two (R-FCH-15, R-FCH-16).
     */
    @SafeVarargs
    public final <M> Enricher<M> profileOf(Function<M, UserRef> key, BiFunction<M, String, M> with,
            ColumnField<M, ?, ?>... reading) {
        return Enricher.<M, UserRef, String>byKeys(this::findProfiles)
                .key(key, with)
                .batchSize(2)
                .reading(reading);
    }

    /** The lookup: one model query on the h2 profile repository per chunk, counting the calls (recipe 8). */
    Map<UserRef, String> findProfiles(Set<UserRef> keys) {
        counter.increment();
        List<Long> userIds = keys.stream().map(UserRef::userId).distinct().toList();
        List<Integer> userTypeIds = keys.stream().map(UserRef::userTypeId).distinct().toList();
        var query = QProfileView.query()
                .select(QProfileView.ALL)
                .where(f -> f.in(QProfileView.USER_ID, userIds).in(QProfileView.USER_TYPE_ID, userTypeIds))
                .build();
        var found = new LinkedHashMap<UserRef, String>();
        for (ProfileView profile : profiles.findAll(query, Limit.unlimited())) {
            UserRef ref = new UserRef(profile.userId(), profile.userTypeId());
            if (keys.contains(ref)) {
                found.put(ref, profile.profile());
            }
        }
        return found;
    }
}
