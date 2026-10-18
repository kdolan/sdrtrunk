package io.github.dsheirer.dsp.gain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SoftLimitedGainTest
{
    @Test
    public void linearBelowKneeAndBoundedAbove()
    {
        float[] out = new SoftLimitedGain(2.0f, 0.8f, 0.95f).apply(new float[]{0.1f, -0.4f, 0.41f, 1.0f, -5.0f});

        assertEquals(0.2f, out[0], 1e-6f);   //below knee: exact gain
        assertEquals(-0.8f, out[1], 1e-6f);  //at knee: exact gain
        assertTrue(out[2] > 0.8f && out[2] < 0.82f);  //just above knee: gentle compression
        assertTrue(out[3] > out[2] && out[3] < 0.95f); //monotonic, under ceiling
        assertTrue(out[4] < 0 && out[4] >= -0.95f);   //sign kept, bounded
    }
}
