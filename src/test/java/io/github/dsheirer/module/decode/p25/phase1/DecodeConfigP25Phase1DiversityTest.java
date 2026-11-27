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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.controller.channel.Channel;
import org.junit.jupiter.api.Test;

/**
 * Weak-signal diversity is on by default and can be turned off per channel; the older opt-in attribute is ignored.
 */
public class DecodeConfigP25Phase1DiversityTest
{
    private static XmlMapper mapper()
    {
        JacksonXmlModule module = new JacksonXmlModule();
        module.setDefaultUseWrapper(false);
        XmlMapper mapper = new XmlMapper(module);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static Channel channel(boolean disabled)
    {
        Channel channel = new Channel("c");
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setWeakSignalDiversityDisabled(disabled);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static DecodeConfigP25Phase1 decode(Channel channel)
    {
        return (DecodeConfigP25Phase1)channel.getDecodeConfiguration();
    }

    @Test
    public void onByDefault()
    {
        assertTrue(new DecodeConfigP25Phase1().isWeakSignalDiversity());
    }

    @Test
    public void disabledChannelSurvivesSaveAndLoad() throws Exception
    {
        XmlMapper mapper = mapper();
        String xml = mapper.writeValueAsString(channel(true));
        assertTrue(xml.contains("weakSignalDiversityDisabled=\"true\""), xml);
        assertFalse(xml.contains("weakSignalDiversity=\""), "the old opt-in attribute is no longer written");
        assertFalse(decode(mapper.readValue(xml, Channel.class)).isWeakSignalDiversity());
        assertTrue(decode(mapper.readValue(mapper.writeValueAsString(channel(false)), Channel.class))
            .isWeakSignalDiversity());
    }

    @Test
    public void oldOptInAttributeIsIgnored() throws Exception
    {
        XmlMapper mapper = mapper();
        String current = mapper.writeValueAsString(channel(false));

        //A channel saved while diversity was opt-in: weakSignalDiversity="false" (every channel) or "true" (Raymond)
        for(String old: new String[]{"false", "true"})
        {
            String legacy = current.replace("weakSignalDiversityDisabled=\"false\"", "weakSignalDiversity=\"" + old + "\"");
            assertTrue(legacy.contains("weakSignalDiversity=\"" + old + "\""), legacy);
            assertTrue(decode(mapper.readValue(legacy, Channel.class)).isWeakSignalDiversity(),
                "old weakSignalDiversity=" + old + " loads with diversity on");
        }

        //As a child element: how a master build re-saves an attribute it doesn't know
        String child = current.replace("weakSignalDiversityDisabled=\"false\"", "")
            .replace("</decode_configuration>", "<weakSignalDiversity>false</weakSignalDiversity></decode_configuration>")
            .replaceAll("(<decode_configuration[^>]*?)/>", "$1><weakSignalDiversity>false</weakSignalDiversity></decode_configuration>");
        assertTrue(decode(mapper.readValue(child, Channel.class)).isWeakSignalDiversity(), child);
    }
}
