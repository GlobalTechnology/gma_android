package com.expidev.gcmapp.model.measurement;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.expidev.gcmapp.model.Base;

import java.util.Locale;

public class MeasurementType extends Base {
    public static final String INVALID_ID = "";
    public static final int DEFAULT_SORT_ORDER = -1;

    public enum Section {
        WIN, BUILD, SEND, UNKNOWN;

        @NonNull
        public static Section fromRaw(@Nullable final String raw) {
            if (raw != null) {
                switch (raw.toLowerCase(Locale.US)) {
                    case "win":
                        return WIN;
                    case "build":
                        return BUILD;
                    case "send":
                        return SEND;
                }
            }
            return UNKNOWN;
        }
    }

    public enum Column {
        FAITH, FRUIT, OUTCOME, UNKNOWN;

        @NonNull
        public static Column fromRaw(@Nullable final String raw) {
            if (raw != null) {
                switch (raw.toLowerCase(Locale.US)) {
                    case "faith":
                        return FAITH;
                    case "fruit":
                        return FRUIT;
                    case "outcome":
                        return OUTCOME;
                }
            }

            return UNKNOWN;
        }
    }

    @NonNull
    private String measurementId = INVALID_ID;
    @Nullable
    private String name;
    @Nullable
    private String permLink;
    @Nullable
    private String description;
    @NonNull
    private Section section = Section.UNKNOWN;
    @NonNull
    private Column column = Column.UNKNOWN;
    private int sortOrder = DEFAULT_SORT_ORDER;

    @NonNull
    public String getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(@NonNull final String measurementId) {
        this.measurementId = measurementId;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public void setName(@Nullable final String name) {
        this.name = name;
    }

    @Nullable
    public String getPermLink() {
        return permLink;
    }

    public void setPermLink(@Nullable final String permLink) {
        this.permLink = permLink;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable final String description) {
        this.description = description;
    }

    @NonNull
    public Section getSection() {
        return section;
    }

    public void setSection(@NonNull final Section section) {
        this.section = section;
    }

    @NonNull
    public Column getColumn() {
        return column;
    }

    public void setColumn(@NonNull final Column column) {
        this.column = column;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(final int order) {
        this.sortOrder = order;
    }
}
