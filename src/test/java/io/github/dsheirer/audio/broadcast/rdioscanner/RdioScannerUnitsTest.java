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

package io.github.dsheirer.audio.broadcast.rdioscanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.UnitOffset;
import io.github.dsheirer.module.decode.mdc1200.identifier.MDC1200Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the rdio-scanner 'units' JSON array construction in {@link RdioScannerBroadcaster#buildUnitsJson}.
 */
public class RdioScannerUnitsTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static UnitOffset unit(int id, double offset)
    {
        return new UnitOffset(APCO25RadioIdentifier.createFrom(id), offset);
    }

    @Test
    public void nullForEmptyOrNullHistory() throws Exception
    {
        assertNull(RdioScannerBroadcaster.buildUnitsJson(null));
        assertNull(RdioScannerBroadcaster.buildUnitsJson(List.of()));
    }

    @Test
    public void serializesSrcAndPos() throws Exception
    {
        //rdio-scanner's 'sources' array uses {src, pos} keys (verified against server 6.6.3 and current).
        String json = RdioScannerBroadcaster.buildUnitsJson(List.of(unit(100, 0.0), unit(200, 1.5)));

        JsonNode array = MAPPER.readTree(json);
        assertTrue(array.isArray());
        assertEquals(2, array.size());

        assertEquals(100, array.get(0).get("src").asInt());
        assertEquals(0.0, array.get(0).get("pos").asDouble(), 0.001);

        assertEquals(200, array.get(1).get("src").asInt());
        assertEquals(1.5, array.get(1).get("pos").asDouble(), 0.001);
    }

    @Test
    public void serializesMdc1200UnitAsHexSource() throws Exception
    {
        //MDC-1200 unit IDs are radio (source) identifiers sent in 4-digit hex (8004 == 0x1F44).
        String json = RdioScannerBroadcaster.buildUnitsJson(
            List.of(new UnitOffset(MDC1200Identifier.createFrom(8004), 0.0)));

        JsonNode array = MAPPER.readTree(json);
        assertTrue(array.isArray());
        assertEquals(1, array.size());
        assertTrue(array.get(0).get("src").isTextual(), "MDC src must be a hex string");
        assertEquals("1F44", array.get(0).get("src").asText());
        assertEquals(0.0, array.get(0).get("pos").asDouble(), 0.001);
    }

    @Test
    public void nonMdcUnitStaysNumericSource() throws Exception
    {
        //P25 (and other digital) units keep their numeric src so existing feeds are unchanged.
        String json = RdioScannerBroadcaster.buildUnitsJson(List.of(unit(8004, 0.0)));

        JsonNode array = MAPPER.readTree(json);
        assertTrue(array.get(0).get("src").isNumber(), "digital src must remain numeric");
        assertEquals(8004, array.get(0).get("src").asInt());
    }

    @Test
    public void onlySrcAndPosKeys() throws Exception
    {
        //rdio-scanner resolves the displayed unit label from its own server-side unit database, so SDRTrunk sends only
        //{src, pos} - no 'tag'/'label', and never the older 'id'/'offset' keys that 6.6.3 does not understand.
        String json = RdioScannerBroadcaster.buildUnitsJson(List.of(unit(100, 0.0), unit(200, 2.0)));

        JsonNode array = MAPPER.readTree(json);
        assertEquals(2, array.size());

        for(JsonNode entry : array)
        {
            assertTrue(entry.has("src"));
            assertTrue(entry.has("pos"));
            assertFalse(entry.has("id"), "must use 'src', not 'id'");
            assertFalse(entry.has("offset"), "must use 'pos', not 'offset'");
            assertFalse(entry.has("tag"), "SDRTrunk must not send a unit tag/label - rdio-scanner ignores it");
            assertFalse(entry.has("label"), "SDRTrunk must not send a unit tag/label - rdio-scanner ignores it");
        }
    }
}
