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
package io.github.dsheirer.record.wave;

import io.github.dsheirer.sample.complex.ComplexSamples;
import org.jtransforms.fft.FloatFFT_1D;

/**
 * Detects signal activity on the channel itself, rather than anywhere in the channelized baseband.
 *
 * The channelized baseband is ~50 kHz wide, so it carries the neighbouring channels on either side.  Measuring total
 * power there triggers on neighbour traffic: on a 12.5 kHz P25 channel with neighbours at 7.5 and 15 kHz, 233 of 319
 * recordings held no traffic for the channel, and on an analog channel with a station 5 kHz away, 49 of 120.  This
 * detector measures power only within +/- the passband half width of the channel centre, using a Hann-windowed FFT of
 * each block of samples (sharp enough to reject a signal 1 kHz outside the passband), and compares it with a tracked
 * noise floor:
 *
 * - smoothed power: exponential average of the block powers
 * - noise floor: drops with the smoothed power immediately, and rises slowly while the channel is idle, so it follows
 *   the noise floor without being pulled up by traffic
 * - active: smoothed power is more than the trigger margin above the noise floor
 *
 * A relative trigger needs no per-channel tuning for gain or antenna: the margin is the channel's in-band signal to
 * noise ratio.  Replayed against those recordings with decoded traffic as ground truth, a +/- 2.5 kHz passband and the
 * default 5 dB margin kept every transmission (including P25 units at 4 dB SNR over the 12.5 kHz channel, which
 * still cleared a 7 dB margin in the narrower band) and cut the recordings without channel traffic to 0 of 88 and
 * 1 of 72.  A busy simulcast channel with no close neighbours recorded the same as before.
 */
public class ChannelActivityDetector
{
    public static final int BLOCK_SIZE = 1024;
    public static final float DEFAULT_TRIGGER_MARGIN_DB = 5.0f;
    private static final float POWER_ALPHA = 0.1f;
    private static final float FLOOR_RISE_DB_PER_SECOND = 0.5f;
    private static final float SILENT_BLOCK_DB = -150.0f;

    private final float mTriggerMarginDb;
    private final double mPassbandHalfWidthHz;
    private final FloatFFT_1D mFFT = new FloatFFT_1D(BLOCK_SIZE);
    private final float[] mWindow = new float[BLOCK_SIZE];
    private final float mWindowPowerNormalization;
    private final float[] mBlock = new float[BLOCK_SIZE * 2];
    private int mBlockFill = 0;
    private int[] mBandBins;
    private float mFloorRisePerBlock;

    private float mSmoothedPowerDb = Float.NaN;
    private float mNoiseFloorDb = Float.NaN;
    private boolean mActive = false;
    private long mBlockCount = 0;

    /**
     * Constructs an instance.
     * @param sampleRate of the channelized complex samples
     * @param passbandHalfWidthHz measurement passband either side of the channel centre
     * @param triggerMarginDb that the in-band power must exceed the noise floor by to be active
     */
    public ChannelActivityDetector(double sampleRate, double passbandHalfWidthHz, float triggerMarginDb)
    {
        mPassbandHalfWidthHz = passbandHalfWidthHz;
        mTriggerMarginDb = triggerMarginDb;

        double windowPower = 0;
        for(int x = 0; x < BLOCK_SIZE; x++)
        {
            mWindow[x] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * x / (BLOCK_SIZE - 1)));
            windowPower += mWindow[x] * mWindow[x];
        }
        mWindowPowerNormalization = (float)(BLOCK_SIZE * windowPower);

        setSampleRate(sampleRate);
    }

    /**
     * Updates the sample rate, which sets the FFT bins inside the passband and the noise floor rise rate.  The
     * smoothed power and noise floor restart, since they were measured at the previous rate.
     */
    public void setSampleRate(double sampleRate)
    {
        int count = 0;
        int[] bins = new int[BLOCK_SIZE];

        for(int k = 0; k < BLOCK_SIZE; k++)
        {
            double frequency = (k < BLOCK_SIZE / 2 ? k : k - BLOCK_SIZE) * sampleRate / BLOCK_SIZE;

            if(Math.abs(frequency) <= mPassbandHalfWidthHz)
            {
                bins[count++] = k;
            }
        }

        mBandBins = java.util.Arrays.copyOf(bins, count);
        mFloorRisePerBlock = (float)(FLOOR_RISE_DB_PER_SECOND * BLOCK_SIZE / sampleRate);
        reset();
    }

    /**
     * Restarts power smoothing and noise floor tracking.
     */
    public void reset()
    {
        mBlockFill = 0;
        mSmoothedPowerDb = Float.NaN;
        mNoiseFloorDb = Float.NaN;
        mActive = false;
    }

    /**
     * Processes the samples and returns the activity state after them.
     */
    public boolean receive(ComplexSamples samples)
    {
        float[] i = samples.i();
        float[] q = samples.q();
        int length = samples.length();

        for(int x = 0; x < length; x++)
        {
            mBlock[mBlockFill * 2] = i[x] * mWindow[mBlockFill];
            mBlock[mBlockFill * 2 + 1] = q[x] * mWindow[mBlockFill];

            if(++mBlockFill == BLOCK_SIZE)
            {
                processBlock();
                mBlockFill = 0;
            }
        }

        return mActive;
    }

    private void processBlock()
    {
        mFFT.complexForward(mBlock);

        double power = 0;
        for(int bin: mBandBins)
        {
            float re = mBlock[bin * 2];
            float im = mBlock[bin * 2 + 1];
            power += re * re + im * im;
        }

        float powerDb = (float)(10.0 * Math.log10(power / mWindowPowerNormalization + 1e-30));

        //An all-zero block (a dropped buffer) would drag the noise floor down for minutes
        if(powerDb < SILENT_BLOCK_DB)
        {
            return;
        }

        mBlockCount++;

        if(Float.isNaN(mSmoothedPowerDb))
        {
            mSmoothedPowerDb = powerDb;
            mNoiseFloorDb = powerDb;
        }
        else
        {
            mSmoothedPowerDb = POWER_ALPHA * powerDb + (1.0f - POWER_ALPHA) * mSmoothedPowerDb;
        }

        mActive = mSmoothedPowerDb > mNoiseFloorDb + mTriggerMarginDb;

        if(mSmoothedPowerDb < mNoiseFloorDb)
        {
            mNoiseFloorDb = mSmoothedPowerDb;
        }
        else if(!mActive)
        {
            mNoiseFloorDb += mFloorRisePerBlock;
        }
    }

    public boolean isActive()
    {
        return mActive;
    }

    /**
     * Smoothed in-band power in dB, or NaN before the first block.
     */
    public float getPowerDb()
    {
        return mSmoothedPowerDb;
    }

    /**
     * Tracked in-band noise floor in dB, or NaN before the first block.
     */
    public float getNoiseFloorDb()
    {
        return mNoiseFloorDb;
    }

    public float getTriggerMarginDb()
    {
        return mTriggerMarginDb;
    }

    public double getPassbandHalfWidthHz()
    {
        return mPassbandHalfWidthHz;
    }

    /**
     * Number of blocks measured, for log pacing.
     */
    public long getBlockCount()
    {
        return mBlockCount;
    }

    /**
     * Passband half width for a channel of the bandwidth: 20% of the channel width either side of centre, which is
     * +/- 2.5 kHz for a 12.5 kHz channel.  That holds the core of a P25 or FM voice signal and stays clear of an
     * off-channel station 5 kHz away, whose lower half a +/- 4 kHz passband would take in.
     */
    public static double getPassbandHalfWidth(double channelBandwidthHz)
    {
        return Math.max(1500.0, channelBandwidthHz * 0.2);
    }
}
