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

/**
 * Blind multipath equalizer for constant-envelope (FM) signals, applied to complex baseband ahead of the FM
 * discriminator.
 *
 * An FM signal has a constant envelope.  When it arrives twice, as from two simulcast transmitters or a reflection,
 * the copies add with a delay, the envelope ripples and the discriminator output smears neighbouring symbols together,
 * which no filter after the discriminator can undo.  The constant modulus algorithm (CMA) adapts a complex FIR filter
 * to restore a constant envelope, which inverts the two-path channel.
 *
 * Unlike CMAEqualizer (whose reference tap is the oldest sample, so it can only cancel an echo that arrives early),
 * the reference tap is in the centre, so echoes either side can be cancelled.  The input is normalized to unit power
 * by a slow power tracker, and each update is normalized by the energy in the filter (NLMS form), so the convergence
 * speed doesn't depend on the signal level.  A small leakage pulls the taps back towards passthrough so they can't
 * drift while there's only noise.
 */
public class FMConstantModulusEqualizer
{
    private final int mTaps;
    private final int mCenter;
    private final float mMu;
    private final float mLeakage;
    private final float[] mWeightI;
    private final float[] mWeightQ;
    private final float[] mBufferI;
    private final float[] mBufferQ;
    private int mPointer = 0;
    private float mPower = 0;
    private float mEnergy = 0;
    private static final float POWER_ALPHA = 0.0005f;

    /**
     * @param taps filter length (odd), spanning the delays to cancel: at 25 kHz, 9 taps cover +/- 160 microseconds
     * @param mu normalized step size (0.001 - 0.05)
     * @param leakage per-update pull of the taps towards passthrough (for example 1e-5)
     */
    public FMConstantModulusEqualizer(int taps, float mu, float leakage)
    {
        if(taps < 1 || taps % 2 == 0)
        {
            throw new IllegalArgumentException("Tap count must be odd: " + taps);
        }

        mTaps = taps;
        mCenter = taps / 2;
        mMu = mu;
        mLeakage = leakage;
        mWeightI = new float[taps];
        mWeightQ = new float[taps];
        mBufferI = new float[taps];
        mBufferQ = new float[taps];
        reset();
    }

    /**
     * Resets to passthrough.
     */
    public void reset()
    {
        java.util.Arrays.fill(mWeightI, 0);
        java.util.Arrays.fill(mWeightQ, 0);
        java.util.Arrays.fill(mBufferI, 0);
        java.util.Arrays.fill(mBufferQ, 0);
        mWeightI[mCenter] = 1;
        mPointer = 0;
        mPower = 0;
        mEnergy = 0;
    }

    /**
     * Equalizes the samples in place.  The output is delayed by (taps - 1) / 2 samples.
     */
    public void equalize(float[] i, float[] q)
    {
        for(int n = 0; n < i.length; n++)
        {
            float xi = i[n], xq = q[n];
            float p = xi * xi + xq * xq;
            mPower = mPower == 0 ? Math.max(p, 1e-12f) : mPower + POWER_ALPHA * (p - mPower);
            float scale = (float)(1 / Math.sqrt(mPower + 1e-20f));
            xi *= scale;
            xq *= scale;

            //Running energy of the samples in the filter, for the normalized update
            float leaving = mBufferI[mPointer] * mBufferI[mPointer] + mBufferQ[mPointer] * mBufferQ[mPointer];
            mBufferI[mPointer] = xi;
            mBufferQ[mPointer] = xq;
            mEnergy += xi * xi + xq * xq - leaving;
            mPointer = (mPointer + 1) % mTaps;

            //y = sum(w[k] * x[n - k]), k = 0 the newest sample
            float yi = 0, yq = 0;
            for(int k = 0; k < mTaps; k++)
            {
                int idx = (mPointer - 1 - k + 2 * mTaps) % mTaps;
                float bi = mBufferI[idx], bq = mBufferQ[idx];
                yi += mWeightI[k] * bi - mWeightQ[k] * bq;
                yq += mWeightI[k] * bq + mWeightQ[k] * bi;
            }

            //CMA error e = y (|y|^2 - 1); w -= mu e conj(x) / energy
            float m = yi * yi + yq * yq - 1;
            float ei = yi * m, eq = yq * m;
            float magnitude = ei * ei + eq * eq;
            if(magnitude > 4)
            {
                float limit = (float)(2 / Math.sqrt(magnitude));
                ei *= limit;
                eq *= limit;
            }
            float step = mMu / Math.max(mEnergy, 1e-3f);

            for(int k = 0; k < mTaps; k++)
            {
                int idx = (mPointer - 1 - k + 2 * mTaps) % mTaps;
                float bi = mBufferI[idx], bq = mBufferQ[idx];
                //e * conj(x)
                mWeightI[k] -= step * (ei * bi + eq * bq);
                mWeightQ[k] -= step * (eq * bi - ei * bq);
                mWeightI[k] += mLeakage * ((k == mCenter ? 1 : 0) - mWeightI[k]);
                mWeightQ[k] -= mLeakage * mWeightQ[k];
            }

            i[n] = yi;
            q[n] = yq;
        }
    }
}
