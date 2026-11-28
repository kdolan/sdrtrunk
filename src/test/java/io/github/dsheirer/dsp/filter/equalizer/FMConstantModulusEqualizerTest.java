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

package io.github.dsheirer.dsp.filter.equalizer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class FMConstantModulusEqualizerTest
{
    private static final int N = 100_000;

    /**
     * Constant envelope 4-level FM at 25 kHz: 4800 symbols per second, +/- 600 and 1800 Hz deviation.
     */
    private static double[][] fm(long seed)
    {
        Random random = new Random(seed);
        double[] i = new double[N], q = new double[N];
        double phase = 0, deviation = 0;
        double samplesPerSymbol = 25000.0 / 4800;
        double next = 0;
        for(int n = 0; n < N; n++)
        {
            if(n >= next)
            {
                deviation = new double[]{-1800, -600, 600, 1800}[random.nextInt(4)];
                next += samplesPerSymbol;
            }
            phase += 2 * Math.PI * deviation / 25000;
            i[n] = Math.cos(phase);
            q[n] = Math.sin(phase);
        }
        return new double[][]{i, q};
    }

    /**
     * Envelope variation of the last half of the samples: standard deviation of |x| over its mean
     */
    private static double envelopeRipple(float[] i, float[] q)
    {
        double sum = 0, sum2 = 0;
        int n = 0;
        for(int x = i.length / 2; x < i.length; x++)
        {
            double m = Math.sqrt(i[x] * i[x] + q[x] * q[x]);
            sum += m;
            sum2 += m * m;
            n++;
        }
        double mean = sum / n;
        return Math.sqrt(sum2 / n - mean * mean) / mean;
    }

    @Test
    public void restoresConstantEnvelopeAfterTwoPathChannel()
    {
        double[][] s = fm(1);
        //Second path 2 samples (80 us) later, 3 dB weaker, rotated in phase
        int delay = 2;
        double a = 0.7, rotation = 2.1;
        float[] i = new float[N], q = new float[N];
        for(int n = 0; n < N; n++)
        {
            double di = n >= delay ? s[0][n - delay] : 0, dq = n >= delay ? s[1][n - delay] : 0;
            i[n] = (float)(s[0][n] + a * (di * Math.cos(rotation) - dq * Math.sin(rotation)));
            q[n] = (float)(s[1][n] + a * (di * Math.sin(rotation) + dq * Math.cos(rotation)));
        }
        double before = envelopeRipple(i, q);
        new FMConstantModulusEqualizer(9, 0.01f, 1e-5f).equalize(i, q);
        double after = envelopeRipple(i, q);
        assertTrue(before > 0.2, "two-path channel ripples the envelope: " + before);
        assertTrue(after < before / 3, "equalizer flattens the envelope: before " + before + " after " + after);
    }

    @Test
    public void cleanSignalPassesThroughDelayedByHalfTheFilter()
    {
        double[][] s = fm(2);
        float[] i = new float[N], q = new float[N];
        for(int n = 0; n < N; n++)
        {
            i[n] = (float)(3.0 * s[0][n]);
            q[n] = (float)(3.0 * s[1][n]);
        }
        new FMConstantModulusEqualizer(9, 0.01f, 1e-5f).equalize(i, q);
        double error = 0;
        for(int n = N / 2; n < N; n++)
        {
            //Output is normalized to unit power and delayed by 4 samples
            double ei = i[n] - s[0][n - 4], eq = q[n] - s[1][n - 4];
            error += ei * ei + eq * eq;
        }
        assertTrue(error / (N / 2) < 1e-3, "mean squared error " + error / (N / 2));
    }
}
