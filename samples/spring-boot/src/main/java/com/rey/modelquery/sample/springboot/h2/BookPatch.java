package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.UpdateModel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The columns a PATCH may write. The processor generates {@code BookPatchChanges}, a change set that tells a field
 * left out of the body from one sent as {@code null} and carries {@code @ValidChanges}, so each constraint below
 * applies to a field the body sets and to no other.
 */
@UpdateModel(root = BookEntity.class)
public record BookPatch(
        @PrimaryKey Long id,
        @NotNull @Size(min = 1, max = 20) String title,
        @Max(2100) Integer released) {}
