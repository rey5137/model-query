package com.rey.modelquery.sample.springboot.mysql;

/** Recipe 8's profile key: the {@code (userId, userTypeId)} pair a song carries (D-111 item 8). */
public record UserRef(long userId, int userTypeId) {

    /** The ref the pair names, or {@code null} when either half is missing, so the enricher skips the row. */
    static UserRef of(Long userId, Integer userTypeId) {
        return userId == null || userTypeId == null ? null : new UserRef(userId, userTypeId);
    }
}
