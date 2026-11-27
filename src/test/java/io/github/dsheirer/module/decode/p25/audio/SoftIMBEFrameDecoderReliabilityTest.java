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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Voice bit confidence is weighted by the signal envelope reliability, so bits received in a fade rank as least
 * confident even when the FM discriminator's soft symbol looks decisive.
 */
public class SoftIMBEFrameDecoderReliabilityTest
{
    @Test
    public void fadedDibitsBecomeTheLeastConfidentBits()
    {
        int dibits = 200;
        float[] soft = new float[dibits];
        float[] reliability = new float[dibits];

        for(int x = 0; x < dibits; x++)
        {
            soft[x] = (float)(3 * Math.PI / 4); //every symbol decisively at +3
            reliability[x] = 1.0f;
        }

        //A fade over dibits 10-13 (message bits 20-27): the discriminator output still looks decisive
        for(int x = 10; x < 14; x++)
        {
            reliability[x] = 0.05f;
        }

        float[] unweighted = SoftIMBEFrameDecoder.getBitConfidence(soft, 0);
        float[] weighted = SoftIMBEFrameDecoder.getBitConfidence(soft, reliability, 0);
        assertEquals(unweighted[20], unweighted[0], 1e-6, "without reliability the faded bits look as good as any");

        for(int bit = 0; bit < 144; bit++)
        {
            boolean faded = bit >= 20 && bit < 28;
            assertTrue(faded ? weighted[bit] < 0.1f * unweighted[bit] : weighted[bit] == unweighted[bit], "bit " + bit);
        }

        assertArrayEquals(unweighted, SoftIMBEFrameDecoder.getBitConfidence(soft, null, 0),
            "no reliabilities: unweighted");
    }
}
