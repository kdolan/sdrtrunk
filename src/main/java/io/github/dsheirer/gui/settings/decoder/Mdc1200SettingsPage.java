/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.gui.settings.decoder;

import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.decoder.Mdc1200Settings;
import javafx.scene.control.Spinner;

/**
 * MDC-1200 decoder settings: soft-sync bit error threshold.
 */
public class Mdc1200SettingsPage extends SettingsPage
{
    private final Mdc1200Settings mMdc1200Settings;
    private Spinner<Integer> mSyncThresholdSpinner;

    public Mdc1200SettingsPage(Settings settings)
    {
        super(SettingsPageType.MDC1200, settings);
        mMdc1200Settings = settings.getMdc1200Settings();

        SettingsSection sync = addSection("Sync Detection");
        sync.addRow("Sync bit error threshold", getSyncThresholdSpinner())
            .defaultValue(getSyncThresholdSpinner().valueProperty(), Mdc1200Settings.DEFAULT_SYNC_BIT_ERROR_THRESHOLD,
                value -> getSyncThresholdSpinner().getValueFactory().setValue(value))
            .requiresRestart()
            .help("Bit errors allowed in the 40-bit MDC-1200 sync preamble.  Higher values catch more marginal " +
                "bursts at the cost of more false sync detections, which the FEC and CRC then reject.  Applies to " +
                "channels started after the change.");
    }

    private Spinner<Integer> getSyncThresholdSpinner()
    {
        if(mSyncThresholdSpinner == null)
        {
            mSyncThresholdSpinner = new Spinner<>(Mdc1200Settings.MIN_SYNC_BIT_ERROR_THRESHOLD,
                Mdc1200Settings.MAX_SYNC_BIT_ERROR_THRESHOLD, mMdc1200Settings.getSyncBitErrorThreshold(), 1);
            mSyncThresholdSpinner.setPrefWidth(80);
            mSyncThresholdSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                mMdc1200Settings.setSyncBitErrorThreshold(newValue));
        }

        return mSyncThresholdSpinner;
    }
}
