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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import org.junit.jupiter.api.Test;

/**
 * Unrecoverable LDU gate and truncated LDU handling in the P25 Phase 1 audio module.
 */
public class P25P1AudioModuleUnrecoverableTest
{
    private static final int LDU_LENGTH = 1568;
    private static final int[] FRAME_OFFSETS = {LDUMessage.IMBE_FRAME_1, LDUMessage.IMBE_FRAME_2,
        LDUMessage.IMBE_FRAME_3, LDUMessage.IMBE_FRAME_4, LDUMessage.IMBE_FRAME_5, LDUMessage.IMBE_FRAME_6,
        LDUMessage.IMBE_FRAME_7, LDUMessage.IMBE_FRAME_8, LDUMessage.IMBE_FRAME_9};

    /**
     * An off-air LDU1 whose nine IMBE frames all correct with zero errors.
     */
    private static final String CLEAN_LDU =
        "7AA4B5C0F5404D81EFE93A4B993A53F8115F2051F9C8028C7C3B514B20B4AEE9BFA289ED00000000007A84F4C0E5404D91" +
        "EFE91A099D1A51F0115E00000000002051F9C8028C7C3B514B20B4AEE9BFA289ED00000000007AA4B5C0F5404D81EFE93A" +
        "4B993A53F8115F83800000002071B8C8128C7C2B514B00F6AAC9BDAA89ECAE8B48B6497AA4B5C0F5404D81EFE93A4B993A" +
        "53F8115F9ABD3C7F582051F9C8028C7C3B514B20B4AEE9BFA289ED000000007A84F4C0E5404D91EFE91A099D1A51F0115E";

    private static LDUMessage ldu(CorrectedBinaryMessage message)
    {
        return new LDU1Message(message, 2144, 0);
    }

    private static CorrectedBinaryMessage clean()
    {
        CorrectedBinaryMessage message = CorrectedBinaryMessage.loadHex(CLEAN_LDU);
        assertEquals(LDU_LENGTH, message.size(), "fixture length");
        return message;
    }

    /**
     * Zeroes the IMBE frames from the index onward.  An all-zero frame is what the framer leaves behind as padding,
     * and it corrects with 12 errors.
     */
    private static CorrectedBinaryMessage zeroFramesFrom(CorrectedBinaryMessage message, int firstFrame)
    {
        for(int frame = firstFrame; frame < FRAME_OFFSETS.length; frame++)
        {
            for(int bit = FRAME_OFFSETS[frame]; bit < FRAME_OFFSETS[frame] + 144; bit++)
            {
                message.clear(bit);
            }
        }

        return message;
    }

    @Test
    public void cleanLduIsRecoverable()
    {
        LDUMessage ldu = ldu(clean());
        assertEquals(0, P25P1AudioModule.getErrorTotal(ldu));
        assertFalse(P25P1AudioModule.isUnrecoverable(ldu));
        assertFalse(ldu.isTruncated());
        assertEquals(9, P25P1AudioModule.getReceivedFrameCount(ldu));
    }

    @Test
    public void allZeroLduIsUnrecoverable()
    {
        LDUMessage ldu = ldu(zeroFramesFrom(clean(), 0));
        assertEquals(9 * 12, P25P1AudioModule.getErrorTotal(ldu));
        assertTrue(P25P1AudioModule.isUnrecoverable(ldu));
    }

    @Test
    public void threeCleanFramesKeepACorruptedLduRecoverable()
    {
        //Six corrupted frames reach the error threshold (6 x 12 = 72), but three clean frames carry usable voice
        LDUMessage ldu = ldu(zeroFramesFrom(clean(), 3));
        assertEquals(72, P25P1AudioModule.getErrorTotal(ldu));
        assertFalse(P25P1AudioModule.isUnrecoverable(ldu));
    }

    @Test
    public void twoCleanFramesDoNotKeepACorruptedLduRecoverable()
    {
        LDUMessage ldu = ldu(zeroFramesFrom(clean(), 2));
        assertEquals(84, P25P1AudioModule.getErrorTotal(ldu));
        assertTrue(P25P1AudioModule.isUnrecoverable(ldu));
    }

    @Test
    public void truncatedLduOnlyCountsReceivedFrames()
    {
        //Received through the end of frame 8 (1424 bits), so frame 9 is zero padding
        LDUMessage ldu = ldu(zeroFramesFrom(clean(), 8));
        ldu.setReceivedBitCount(1424);

        assertTrue(ldu.isTruncated());
        assertEquals(8, P25P1AudioModule.getReceivedFrameCount(ldu));
        assertTrue(ldu.isIMBEFrameReceived(7));
        assertFalse(ldu.isIMBEFrameReceived(8));
        assertEquals(0, P25P1AudioModule.getErrorTotal(ldu), "padding frame is excluded from the error total");
        assertFalse(P25P1AudioModule.isUnrecoverable(ldu));
    }

    @Test
    public void truncatedLduWithCorruptedReceivedFramesIsUnrecoverable()
    {
        //The phantom LDU2 at call start: 378 bits received, frames 1 and 2 corrupted, the rest padding
        LDUMessage ldu = ldu(zeroFramesFrom(clean(), 0));
        ldu.setReceivedBitCount(378);

        assertEquals(2, P25P1AudioModule.getReceivedFrameCount(ldu));
        assertEquals(9 * 12, P25P1AudioModule.getErrorTotal(ldu), "scaled from the 2 received frames to 9");
        assertTrue(P25P1AudioModule.isUnrecoverable(ldu));
    }

    @Test
    public void truncatedLduWithNoReceivedFramesIsUnrecoverable()
    {
        //A stub: the next frame's sync arrived 138 bits in, before the first voice frame ended
        LDUMessage ldu = ldu(clean());
        ldu.setReceivedBitCount(138);

        assertEquals(0, P25P1AudioModule.getReceivedFrameCount(ldu));
        assertEquals(Integer.MAX_VALUE, P25P1AudioModule.getErrorTotal(ldu));
        assertTrue(P25P1AudioModule.isUnrecoverable(ldu));
    }
}
