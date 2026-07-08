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
package io.github.dsheirer.record.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.record.RecorderType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RecordConfiguration survives XML round-trips and that playlists written before the
 * activity-triggered recording fields existed still load safely (no data loss / corruption).
 */
public class RecordConfigurationRoundTripTest
{
    private XmlMapper createMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        XmlMapper mapper = new XmlMapper(xmlModule);
        //Same tolerant configuration PlaylistManager uses for loading playlists.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);
        return mapper;
    }

    @Test
    void legacyConfigWithoutActivityAttributesLoadsWithDefaults() throws Exception
    {
        //A playlist written before activity-triggered recording existed - only a recorder element.
        String xml = "<record_configuration><recorder>BASEBAND</recorder></record_configuration>";

        RecordConfiguration config = createMapper().readValue(xml, RecordConfiguration.class);

        assertTrue(config.contains(RecorderType.BASEBAND), "existing recorder must survive load");
        assertFalse(config.isActivityTriggeredRecording(), "missing attribute must default to off");
        assertEquals(RecordConfiguration.DEFAULT_ACTIVITY_SQUELCH_THRESHOLD,
            config.getActivitySquelchThreshold(), 0.001, "missing threshold must default");
    }

    @Test
    void activitySettingsRoundTrip() throws Exception
    {
        XmlMapper mapper = createMapper();

        RecordConfiguration config = new RecordConfiguration();
        config.addRecorder(RecorderType.BASEBAND);
        config.setActivityTriggeredRecording(true);
        config.setActivitySquelchThreshold(-55.0f);

        String xml = mapper.writeValueAsString(config);
        RecordConfiguration reloaded = mapper.readValue(xml, RecordConfiguration.class);

        assertTrue(reloaded.contains(RecorderType.BASEBAND), "recorder list preserved");
        assertTrue(reloaded.isActivityTriggeredRecording(), "activity flag preserved");
        assertEquals(-55.0f, reloaded.getActivitySquelchThreshold(), 0.001, "threshold preserved");
    }

    @Test
    void unknownFutureAttributesAreIgnoredNotFatal() throws Exception
    {
        //A playlist written by a future build with an attribute this build doesn't know about must
        //still load (FAIL_ON_UNKNOWN_PROPERTIES=false), so downgrading branches never corrupts.
        String xml = "<record_configuration activityTriggeredRecording=\"true\" "
            + "someFutureField=\"xyz\"><recorder>BASEBAND</recorder></record_configuration>";

        RecordConfiguration config = createMapper().readValue(xml, RecordConfiguration.class);

        assertTrue(config.contains(RecorderType.BASEBAND));
        assertTrue(config.isActivityTriggeredRecording());
    }
}
