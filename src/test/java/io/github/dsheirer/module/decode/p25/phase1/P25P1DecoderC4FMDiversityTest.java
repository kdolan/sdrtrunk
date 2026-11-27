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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Voice frame selection in the weak-signal diversity decoder.  Score of a group of identical candidates = distinct
 * tracks supporting it - 0.1 x fewest corrections; a group other than the primary's must have needed fewer corrections
 * than the primary and must score strictly higher.
 */
public class P25P1DecoderC4FMDiversityTest
{
    private static BitSet content(int value)
    {
        return BitSet.valueOf(new long[]{value});
    }

    private static P25P1DecoderC4FMDiversity.Candidate primary(int content, int errors)
    {
        return candidate(content, errors, true, 0);
    }

    private static P25P1DecoderC4FMDiversity.Candidate candidate(int content, int errors, int track)
    {
        return candidate(content, errors, false, track);
    }

    private static P25P1DecoderC4FMDiversity.Candidate candidate(int content, int errors, boolean primary, int track)
    {
        return new P25P1DecoderC4FMDiversity.Candidate(new byte[]{(byte)content, (byte)errors}, content(content),
            errors, primary, track);
    }

    @Test
    public void betterSupportedGroupWithFewerCorrectionsReplaces()
    {
        //Primary: 1 track, 6 corrections (0.4).  Content 2: 2 tracks, best 1 correction (1.9)
        byte[] winner = P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 6), candidate(2, 2, 1),
            candidate(2, 1, 2), candidate(3, 5, 3)));
        assertArrayEquals(new byte[]{2, 1}, winner, "the winning group's fewest-correction member is used");
    }

    @Test
    public void replacementMustNeedFewerCorrectionsThanThePrimary()
    {
        //Content 2 has three tracks behind it, but none needed fewer corrections than the primary's 2
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 2), candidate(2, 2, 1), candidate(2, 3, 2),
            candidate(2, 2, 3))));
    }

    @Test
    public void primaryBackedByMoreTracksIsKept()
    {
        //Primary content: 3 tracks, 3 corrections (2.7).  Content 2: 1 track, 0 corrections (1.0)
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 4), candidate(1, 3, 1), candidate(1, 3, 2),
            candidate(2, 0, 3))));
    }

    @Test
    public void oneTracksHardAndSoftDecodesCountOnce()
    {
        //Primary content: 2 tracks, 4 corrections (1.6).  Content 2 from track 5's hard and soft decodes, 3 corrections:
        //one track (0.7), not two (1.7), so the primary is kept
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 4), candidate(1, 5, 1), candidate(2, 3, 5),
            candidate(2, 4, 5))));
    }

    @Test
    public void equalScoreKeepsThePrimary()
    {
        //Primary: 2 tracks, 5 corrections (1.5).  Content 2: 2 tracks, best 5 corrections: not fewer, kept
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 5), candidate(1, 6, 1), candidate(2, 5, 2),
            candidate(2, 6, 3))));
    }

    @Test
    public void lowerCorrectionSingleTrackCanReplaceAWeakPrimary()
    {
        //Primary: 1 track, 10 corrections (0.0).  Content 2: 1 track, 2 corrections (0.8)
        assertArrayEquals(new byte[]{2, 2}, P25P1DecoderC4FMDiversity.vote(List.of(primary(1, 10),
            candidate(2, 2, 4))));
    }
}
