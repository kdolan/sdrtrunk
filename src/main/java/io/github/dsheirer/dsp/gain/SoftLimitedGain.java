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

package io.github.dsheirer.dsp.gain;

/**
 * Applies a fixed gain with a soft-knee limiter.  Samples below the knee pass through linearly, so steady tones
 * below the knee are not distorted.  Samples above the knee are compressed with tanh toward the ceiling instead of
 * being hard clipped like NonClippingGain.
 */
public class SoftLimitedGain
{
    private final float mGain;
    private final float mKnee;
    private final float mWidth;

    /**
     * Constructs an instance
     * @param gain linear gain
     * @param knee absolute level above which limiting starts (0.0 to ceiling)
     * @param ceiling absolute level that output never exceeds (knee to 1.0)
     */
    public SoftLimitedGain(float gain, float knee, float ceiling)
    {
        mGain = gain;
        mKnee = knee;
        mWidth = ceiling - knee;
    }

    /**
     * Applies gain and limiting to the sample array in place
     * @param samples to process
     * @return the same array
     */
    public float[] apply(float[] samples)
    {
        for(int x = 0; x < samples.length; x++)
        {
            float s = samples[x] * mGain;
            float abs = Math.abs(s);

            if(abs > mKnee)
            {
                s = Math.signum(s) * (mKnee + mWidth * (float)Math.tanh((abs - mKnee) / mWidth));
            }

            samples[x] = s;
        }

        return samples;
    }
}
