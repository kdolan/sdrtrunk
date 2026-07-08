/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.preference.decoder;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.decoder.Mdc1200Preference;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

/**
 * Preference editor for the MDC-1200 decoder.
 */
public class Mdc1200PreferenceEditor extends HBox
{
    private final Mdc1200Preference mPreference;
    private GridPane mEditorPane;
    private Spinner<Integer> mSyncThresholdSpinner;

    public Mdc1200PreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getMdc1200Preference();
        getChildren().add(getEditorPane());
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10));
            mEditorPane.setHgap(10);
            mEditorPane.setVgap(10);

            Label titleLabel = new Label("MDC-1200 Decoder Preferences");
            GridPane.setConstraints(titleLabel, 0, row++, 2, 1);
            mEditorPane.getChildren().add(titleLabel);

            GridPane.setConstraints(getSyncThresholdSpinner(), 0, row);
            mEditorPane.getChildren().add(getSyncThresholdSpinner());

            Label thresholdLabel = new Label("Sync Bit-Error Threshold (higher = more sensitive, more false triggers)");
            GridPane.setConstraints(thresholdLabel, 1, row++);
            mEditorPane.getChildren().add(thresholdLabel);

            Label helpLabel = new Label("Allowed bit errors in the 40-bit sync preamble. Noise matches are rejected by "
                + "FEC/CRC. Restart or reload the channel for changes to take effect.");
            GridPane.setConstraints(helpLabel, 0, row++, 2, 1);
            mEditorPane.getChildren().add(helpLabel);
        }

        return mEditorPane;
    }

    private Spinner<Integer> getSyncThresholdSpinner()
    {
        if(mSyncThresholdSpinner == null)
        {
            mSyncThresholdSpinner = new Spinner<>(Mdc1200Preference.MIN_SYNC_BIT_ERROR_THRESHOLD,
                Mdc1200Preference.MAX_SYNC_BIT_ERROR_THRESHOLD, mPreference.getSyncBitErrorThreshold(), 1);
            mSyncThresholdSpinner.setEditable(true);
            mSyncThresholdSpinner.setTooltip(new Tooltip("Allowed bit errors in the 40-bit MDC-1200 sync preamble"));
            mSyncThresholdSpinner.valueProperty()
                .addListener((observable, oldValue, newValue) -> mPreference.setSyncBitErrorThreshold(newValue));
        }

        return mSyncThresholdSpinner;
    }
}
