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
 * Voice frame voting in the weak-signal diversity decoder.
 */
public class P25P1DecoderC4FMDiversityTest
{
    private static BitSet content(int value)
    {
        return BitSet.valueOf(new long[]{value});
    }

    private static int sTrack = 0;

    private static P25P1DecoderC4FMDiversity.Candidate candidate(int content, int errors, boolean primary)
    {
        //Each call is a different track unless it is the primary (track 0)
        return candidate(content, errors, primary, primary ? 0 : ++sTrack);
    }

    private static P25P1DecoderC4FMDiversity.Candidate candidate(int content, int errors, boolean primary, int track)
    {
        return new P25P1DecoderC4FMDiversity.Candidate(new byte[]{(byte)content, (byte)errors}, content(content),
            errors, primary, track);
    }

    @Test
    public void agreeingTracksReplaceThePrimary()
    {
        byte[] winner = P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 6, true), candidate(2, 2, false),
            candidate(2, 1, false), candidate(3, 5, false)));
        assertArrayEquals(new byte[]{2, 1}, winner, "the agreeing group wins, its fewest-error member is used");
    }

    @Test
    public void primaryKeepsATie()
    {
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 6, true), candidate(1, 6, false),
            candidate(2, 1, false), candidate(2, 1, false))));
    }

    @Test
    public void primaryInTheLargestGroupIsKept()
    {
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 4, true), candidate(1, 3, false),
            candidate(1, 3, false), candidate(2, 0, false))));
    }

    @Test
    public void singleDissentingCandidateDoesNotReplace()
    {
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 6, true), candidate(2, 0, false))),
            "a lone candidate isn't agreement");
    }

    @Test
    public void oneTracksHardAndSoftDecodesAreOneVote()
    {
        //Track 5's hard and soft decodes agree with each other but no other track does: not agreement
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 10, true), candidate(2, 3, false, 5),
            candidate(2, 4, false, 5))));
    }

    @Test
    public void tracksMustOutnumberThePrimarysSupporters()
    {
        //Two tracks back the primary's content, two others back content 2: a tie, the primary stays
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 10, true), candidate(1, 9, false, 1),
            candidate(2, 3, false, 2), candidate(2, 3, false, 3))));
        assertArrayEquals(new byte[]{2, 3}, P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 10, true),
            candidate(2, 3, false, 2), candidate(2, 3, false, 3))));
    }

    @Test
    public void tieBetweenTwoOtherGroupsKeepsThePrimary()
    {
        assertNull(P25P1DecoderC4FMDiversity.vote(List.of(candidate(1, 6, true), candidate(2, 1, false),
            candidate(2, 1, false), candidate(3, 1, false), candidate(3, 1, false))));
    }
}
