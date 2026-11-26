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

package io.github.dsheirer.module.decode.p25.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import org.junit.jupiter.api.Test;

/**
 * Call-level voice quality: weak transmissions whose LDUs total fewer errors than the single-LDU threshold but carry
 * almost no clean frames produce no audio, while intelligible calls with some noisy frames are decoded as before.
 */
public class LDUVoiceQualityTest
{
    private static final int[] FRAME_OFFSETS = {LDUMessage.IMBE_FRAME_1, LDUMessage.IMBE_FRAME_2,
        LDUMessage.IMBE_FRAME_3, LDUMessage.IMBE_FRAME_4, LDUMessage.IMBE_FRAME_5, LDUMessage.IMBE_FRAME_6,
        LDUMessage.IMBE_FRAME_7, LDUMessage.IMBE_FRAME_8, LDUMessage.IMBE_FRAME_9};

    /**
     * Builds an LDU from the clean off-air fixture with the first cleanFrames frames intact and the rest corrupted by
     * flipping bits spread through the frame, which corrects with several errors per frame (a weak signal) rather
     * than the 12 of an all-zero frame.
     */
    private static LDUMessage ldu(int cleanFrames)
    {
        CorrectedBinaryMessage message = CorrectedBinaryMessage.loadHex(P25P1AudioModuleUnrecoverableTest.CLEAN_LDU);

        for(int frame = cleanFrames; frame < FRAME_OFFSETS.length; frame++)
        {
            for(int bit = 7; bit < 144; bit += 23)
            {
                message.flip(FRAME_OFFSETS[frame] + bit);
            }
        }

        return new LDU1Message(message, 2144, 0);
    }

    private static int clean(LDUMessage ldu)
    {
        return (int)ldu.getCorrectedIMBEFrames().stream().filter(f -> f.getErrorCountTotal() <= 3).count();
    }

    @Test
    public void weakFixtureHasFewErrorsPerFrameButNoCleanFrames()
    {
        LDUMessage weak = ldu(0);
        assertTrue(clean(weak) == 0, "corrupted frames are not clean");
        assertFalse(P25P1AudioModule.isUnrecoverable(weak),
            "below the single-LDU error total: only the call window can catch it (total " +
                P25P1AudioModule.getErrorTotal(weak) + ")");
    }

    @Test
    public void weakTransmissionProducesNoAudio()
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();

        for(int x = 0; x < 20; x++)
        {
            assertFalse(quality.accept(ldu(x % 5 == 0 ? 2 : 0)), "LDU " + x);
        }

        assertFalse(quality.isCallAudioStarted());
    }

    @Test
    public void oddLduWithCleanFramesDoesNotStartAWeakCall()
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();
        quality.accept(ldu(0));
        quality.accept(ldu(0));
        assertFalse(quality.accept(ldu(3)), "3 clean frames in a 27-frame window of a weak call is under 20%");
        assertFalse(quality.isCallAudioStarted());
    }

    @Test
    public void cleanCallIsDecoded()
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();

        for(int x = 0; x < 10; x++)
        {
            assertTrue(quality.accept(ldu(9)));
        }
    }

    @Test
    public void fadingCallKeepsLdusWithCleanFrames()
    {
        //The noisiest intelligible corpus capture has ~46% clean frames, spread across LDUs
        LDUVoiceQuality quality = new LDUVoiceQuality();
        assertTrue(quality.accept(ldu(9)));
        assertTrue(quality.accept(ldu(4)));
        assertTrue(quality.accept(ldu(3)));
        assertTrue(quality.accept(ldu(5)));
    }

    @Test
    public void fadeOutInsideACallIsSilencedAndRecovers()
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();
        assertTrue(quality.accept(ldu(9)));
        assertTrue(quality.accept(ldu(9)));
        assertTrue(quality.accept(ldu(0)), "a short fade is left to the codec's concealment");
        assertTrue(quality.accept(ldu(0)), "window still 33% clean");
        assertFalse(quality.accept(ldu(0)), "sustained corruption: the window has no clean frames");
        assertTrue(quality.isCallAudioStarted(), "the call's audio started, so it is silenced, not dropped");
        assertTrue(quality.accept(ldu(9)), "voice comes back");
    }

    @Test
    public void resetStartsANewCall()
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();
        quality.accept(ldu(9));
        quality.reset();
        assertFalse(quality.isCallAudioStarted());
        assertFalse(quality.accept(ldu(0)));
    }
}
