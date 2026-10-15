package com.rey.modelquery.annotations;

/**
 * The frame of a {@link Window}: which rows of the partition a function reads around the current row (R-PROC-36). Five
 * frames, plus {@link #NONE}, which declares no frame clause so the database's own default applies.
 *
 * <p>A {@code ROWS} frame counts physical rows and a {@code RANGE} frame groups peers that share the window's order
 * values. Offset frames ({@code n preceding}) are left for a later release.
 */
@Incubating
public enum WindowFrame {

    /** No frame clause: the database's default frame applies. */
    NONE,

    /** {@code ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW}. */
    ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW,

    /** {@code ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING}. */
    ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING,

    /** {@code ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING}. */
    ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING,

    /** {@code RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW}. */
    RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW,

    /** {@code RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING}. */
    RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING
}
