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
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.audio.UnitOffset;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.protocol.Protocol;
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
        assertNull(RdioScannerBroadcaster.buildUnitsJson(null, null));
        assertNull(RdioScannerBroadcaster.buildUnitsJson(List.of(), null));
    }

    @Test
    public void serializesIdAndOffsetWithoutAliasList() throws Exception
    {
        String json = RdioScannerBroadcaster.buildUnitsJson(List.of(unit(100, 0.0), unit(200, 1.5)), null);

        JsonNode array = MAPPER.readTree(json);
        assertTrue(array.isArray());
        assertEquals(2, array.size());

        assertEquals(100, array.get(0).get("id").asInt());
        assertEquals(0.0, array.get(0).get("offset").asDouble(), 0.001);
        assertFalse(array.get(0).has("label"), "No alias list -> no label");

        assertEquals(200, array.get(1).get("id").asInt());
        assertEquals(1.5, array.get(1).get("offset").asDouble(), 0.001);
    }

    @Test
    public void includesLabelWhenAliasExists() throws Exception
    {
        AliasList aliasList = new AliasList("test");
        Alias alias = new Alias("Engine 51");
        alias.addAliasID(new Radio(Protocol.APCO25, 100));
        aliasList.addAlias(alias);

        String json = RdioScannerBroadcaster.buildUnitsJson(List.of(unit(100, 0.0), unit(200, 2.0)), aliasList);

        JsonNode array = MAPPER.readTree(json);
        assertEquals(2, array.size());

        //Unit 100 has an alias -> label present
        assertEquals(100, array.get(0).get("id").asInt());
        assertEquals("Engine 51", array.get(0).get("label").asText());

        //Unit 200 has no matching alias -> no label
        assertEquals(200, array.get(1).get("id").asInt());
        assertFalse(array.get(1).has("label"), "Unit without an alias should have no label");
    }
}
