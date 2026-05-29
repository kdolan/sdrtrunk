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

package io.github.dsheirer.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.dsp.oscillator.ScalarRealOscillator;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the source (FROM) radio unit timeline capture in {@link AudioSegment} that backs the rdio-scanner 'units' array.
 */
public class AudioSegmentTest
{
    private static final int SAMPLES_PER_SECOND = 8000; //8 samples per millisecond

    private static AudioSegment newSegment()
    {
        return new AudioSegment(new AliasList("test"), TimeslotMessage.TIMESLOT_0);
    }

    private static void addSeconds(AudioSegment segment, int seconds)
    {
        ScalarRealOscillator oscillator = new ScalarRealOscillator(1000, SAMPLES_PER_SECOND);
        segment.addAudio(oscillator.generate(SAMPLES_PER_SECOND * seconds));
    }

    @Test
    public void capturesFromUnitsInOrderWithOffsets()
    {
        AudioSegment segment = newSegment();

        //First unit keys up before any audio -> offset 0.0
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(100));
        addSeconds(segment, 1);
        //Second unit keys up after 1 second of audio
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(200));
        addSeconds(segment, 1);
        //Third unit keys up after 2 seconds of audio
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(300));

        List<UnitOffset> history = segment.getUnitHistory();

        assertEquals(3, history.size(), "Expected three unit timeline entries");
        assertEquals(100, history.get(0).radio().getValue().intValue());
        assertEquals(200, history.get(1).radio().getValue().intValue());
        assertEquals(300, history.get(2).radio().getValue().intValue());
        assertEquals(0.0, history.get(0).offsetSeconds(), 0.001, "First unit should be at offset 0");
        assertEquals(1.0, history.get(1).offsetSeconds(), 0.001);
        assertEquals(2.0, history.get(2).offsetSeconds(), 0.001);
    }

    @Test
    public void dedupesConsecutiveSameUnit()
    {
        AudioSegment segment = newSegment();

        segment.addIdentifier(APCO25RadioIdentifier.createFrom(100));
        addSeconds(segment, 1);
        //Same unit re-asserted (e.g. repeated link control) -> no new entry
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(100));
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(100));

        List<UnitOffset> history = segment.getUnitHistory();

        assertEquals(1, history.size(), "Repeated same-unit updates should not add new entries");
        assertEquals(100, history.get(0).radio().getValue().intValue());
    }

    @Test
    public void ignoresNonSourceAndNonRadioIdentifiers()
    {
        AudioSegment segment = newSegment();

        //TO radio and a talkgroup are not source (FROM) units and must be excluded
        segment.addIdentifier(APCO25RadioIdentifier.createTo(500));
        segment.addIdentifier(APCO25Talkgroup.create(1234));
        segment.addIdentifier(APCO25RadioIdentifier.createFrom(100));

        List<UnitOffset> history = segment.getUnitHistory();

        assertEquals(1, history.size(), "Only FROM radio identifiers should be captured");
        assertEquals(100, history.get(0).radio().getValue().intValue());
    }

    @Test
    public void emptyWhenNoSourceUnits()
    {
        AudioSegment segment = newSegment();
        addSeconds(segment, 1);
        assertTrue(segment.getUnitHistory().isEmpty(), "Segment with no FROM units should have empty timeline");
    }
}
