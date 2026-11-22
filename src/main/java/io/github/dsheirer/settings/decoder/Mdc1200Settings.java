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
package io.github.dsheirer.settings.decoder;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.AbstractSettings;
import io.github.dsheirer.settings.Setting;
import io.github.dsheirer.settings.SettingsType;

/**
 * MDC-1200 decoder settings.
 */
public class Mdc1200Settings extends AbstractSettings
{
    /**
     * Default allowed bit errors in the 40-bit sync preamble for soft-sync matching. A JVM system
     * property override is honored so field experimentation still works without touching settings.
     */
    public static final int DEFAULT_SYNC_BIT_ERROR_THRESHOLD = Integer.getInteger("mdc.sync.threshold", 8);
    public static final int MIN_SYNC_BIT_ERROR_THRESHOLD = 0;
    public static final int MAX_SYNC_BIT_ERROR_THRESHOLD = 20;

    private Integer mSyncBitErrorThreshold;

    public Mdc1200Settings(Listener<SettingsType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public SettingsType getSettingsType()
    {
        return SettingsType.MDC1200;
    }

    /**
     * Allowed bit errors in the 40-bit sync preamble. Higher = more sensitive (catches marginal
     * bursts) at the cost of more soft-sync false positives (which the FEC/CRC then reject).
     */
    public int getSyncBitErrorThreshold()
    {
        if(mSyncBitErrorThreshold == null)
        {
            mSyncBitErrorThreshold = Setting.MDC1200_SYNC_BIT_ERROR_THRESHOLD.getInt(DEFAULT_SYNC_BIT_ERROR_THRESHOLD);
        }

        return mSyncBitErrorThreshold;
    }

    /**
     * Sets the sync bit-error threshold, clamped to the supported range.
     */
    public void setSyncBitErrorThreshold(int threshold)
    {
        mSyncBitErrorThreshold = Math.max(MIN_SYNC_BIT_ERROR_THRESHOLD, Math.min(MAX_SYNC_BIT_ERROR_THRESHOLD, threshold));
        Setting.MDC1200_SYNC_BIT_ERROR_THRESHOLD.putInt(mSyncBitErrorThreshold);
        notifySettingsUpdated();
    }
}
