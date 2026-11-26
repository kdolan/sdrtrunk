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
package io.github.dsheirer.record.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.controller.config.Configuration;
import io.github.dsheirer.record.RecorderType;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains the types of recordings specified for a channel
 */
public class RecordConfiguration extends Configuration
{
    public static final float DEFAULT_ACTIVITY_SQUELCH_THRESHOLD = -70.0f;
    public static final float DEFAULT_ACTIVITY_TRIGGER_MARGIN_DB = 5.0f;
    public static final float MIN_ACTIVITY_TRIGGER_MARGIN_DB = 2.0f;
    public static final float MAX_ACTIVITY_TRIGGER_MARGIN_DB = 30.0f;

    /**
     * Recording types requested for this configuration
     */
    private List<RecorderType> mRecorders = new ArrayList<>();
    private boolean mActivityTriggeredRecording = false;
    private float mActivitySquelchThreshold = DEFAULT_ACTIVITY_SQUELCH_THRESHOLD;
    private float mActivityTriggerMarginDb = DEFAULT_ACTIVITY_TRIGGER_MARGIN_DB;

    /**
     * Constructs a recording configuration instance
     */
    public RecordConfiguration()
    {
        //Empty constructor required for deserialization
    }

    /**
     * List of recorder types specified in this configuration
     */
    @JacksonXmlProperty(isAttribute = false, localName = "recorder")
    public List<RecorderType> getRecorders()
    {
        return mRecorders;
    }

    /**
     * Sets the (complete) list of recorder types for this configuration, erasing any existing recording types.
     */
    public void setRecorders(List<RecorderType> recorders)
    {
        mRecorders = recorders;
    }

    /**
     * Adds the recorder type to the configuration
     */
    public void addRecorder(RecorderType recorder)
    {
        mRecorders.add(recorder);
    }

    /**
     * Clears all recorder types from this configuration
     */
    public void clearRecorders()
    {
        mRecorders.clear();
    }

    /**
     * Indicates if this configuration has the specified recorder type
     * @param recorderType to check
     * @return true if this configuration contains the specified recorder type
     */
    public boolean contains(RecorderType recorderType)
    {
        return mRecorders.contains(recorderType);
    }

    /**
     * Indicates if activity-triggered baseband recording is enabled.
     * @return true if enabled
     */
    @JacksonXmlProperty(isAttribute = true, localName = "activityTriggeredRecording")
    public boolean isActivityTriggeredRecording()
    {
        return mActivityTriggeredRecording;
    }

    /**
     * Sets activity-triggered baseband recording enabled state.
     * @param enabled true to enable
     */
    public void setActivityTriggeredRecording(boolean enabled)
    {
        mActivityTriggeredRecording = enabled;
    }

    /**
     * Gets the squelch threshold in dB for activity-triggered recording.
     * @return threshold in dB (default -70.0)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "activitySquelchThreshold")
    public float getActivitySquelchThreshold()
    {
        return mActivitySquelchThreshold;
    }

    /**
     * Sets the squelch threshold in dB for activity-triggered recording.
     * @param threshold in dB
     */
    public void setActivitySquelchThreshold(float threshold)
    {
        mActivitySquelchThreshold = Math.max(-100.0f, Math.min(-30.0f, threshold));
    }

    /**
     * Margin in dB that the in-channel power must exceed the channel's tracked noise floor by to trigger an
     * activity-triggered recording.  This replaces the absolute squelch threshold, which measured power across the
     * whole channelized baseband and so triggered on neighbouring channels.  The threshold attribute is still read and
     * written so that the playlist stays compatible with older builds, but recording no longer uses it.
     * @return margin in dB (default 5.0)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "activityTriggerMarginDb")
    public float getActivityTriggerMarginDb()
    {
        return mActivityTriggerMarginDb;
    }

    /**
     * Sets the activity-triggered recording margin above the channel noise floor, limited to 2-30 dB.
     * @param marginDb in dB
     */
    public void setActivityTriggerMarginDb(float marginDb)
    {
        mActivityTriggerMarginDb = Math.max(MIN_ACTIVITY_TRIGGER_MARGIN_DB,
                Math.min(MAX_ACTIVITY_TRIGGER_MARGIN_DB, marginDb));
    }

    /**
     * Indicates if activity-triggered recording has a non-default squelch threshold.
     */
    @JsonIgnore
    public boolean hasCustomSquelchThreshold()
    {
        return mActivitySquelchThreshold != DEFAULT_ACTIVITY_SQUELCH_THRESHOLD;
    }
}
