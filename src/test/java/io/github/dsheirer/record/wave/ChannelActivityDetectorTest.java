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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * In-channel activity detection: triggers on signal at the channel centre relative to the noise floor, and not on
 * signals in the neighbouring channels of the channelized baseband.
 */
public class ChannelActivityDetectorTest
{
    private static final double SAMPLE_RATE = 50000.0;
    private static final double HALF_WIDTH = ChannelActivityDetector.getPassbandHalfWidth(12500.0);
    private static final float NOISE = 0.002f;

    /**
     * Generates complex noise plus an optional FM-modulated carrier, as C4FM-like as needed for a power test: the
     * carrier frequency moves between four deviation levels around the offset at the symbol rate.  The frequency steps
     * are smoothed, as a transmitter's pulse shaping filter does: instant steps would splatter energy several kHz
     * wider than a real signal occupies.
     */
    private static class SignalGenerator
    {
        private final Random mRandom = new Random(42);
        private double mPhase = 0;
        private int mSample = 0;
        private int mSymbol = 1;
        private double mDeviation = 0;

        ComplexSamples next(int length, double offsetHz, double amplitude)
        {
            float[] i = new float[length];
            float[] q = new float[length];

            for(int x = 0; x < length; x++)
            {
                if(mSample++ % 10 == 0)
                {
                    int[] levels = {-3, -1, 1, 3};
                    mSymbol = levels[mRandom.nextInt(4)];
                }

                mDeviation += 0.25 * (mSymbol * 600 - mDeviation);
                mPhase += 2 * Math.PI * (offsetHz + mDeviation) / SAMPLE_RATE;
                i[x] = (float)(amplitude * Math.cos(mPhase) + mRandom.nextGaussian() * NOISE);
                q[x] = (float)(amplitude * Math.sin(mPhase) + mRandom.nextGaussian() * NOISE);
            }

            return new ComplexSamples(i, q, 0);
        }
    }

    private static boolean run(ChannelActivityDetector detector, SignalGenerator generator, double seconds,
                               double offsetHz, double amplitude)
    {
        boolean everActive = false;
        int buffers = (int)(seconds * SAMPLE_RATE / 2048);

        for(int x = 0; x < buffers; x++)
        {
            everActive |= detector.receive(generator.next(2048, offsetHz, amplitude));
        }

        return everActive;
    }

    /**
     * Carrier amplitude for an in-passband signal to noise ratio: noise power in the passband is the fraction of the
     * 50 kHz noise bandwidth inside it.
     */
    private static double amplitudeForPassbandSnr(double snrDb)
    {
        double noisePowerInPassband = 2 * NOISE * NOISE * (2 * HALF_WIDTH / SAMPLE_RATE);
        return Math.sqrt(noisePowerInPassband * Math.pow(10, snrDb / 10));
    }

    @Test
    public void passbandIsTwentyPercentOfChannelWidth()
    {
        assertEquals(2500.0, ChannelActivityDetector.getPassbandHalfWidth(12500.0), 0.1);
        assertEquals(5000.0, ChannelActivityDetector.getPassbandHalfWidth(25000.0), 0.1);
        assertEquals(1500.0, ChannelActivityDetector.getPassbandHalfWidth(3000.0), 0.1);
    }

    @Test
    public void noiseAloneDoesNotTrigger()
    {
        ChannelActivityDetector detector = new ChannelActivityDetector(SAMPLE_RATE, HALF_WIDTH, 5.0f);
        assertFalse(run(detector, new SignalGenerator(), 30, 0, 0));
    }

    @Test
    public void weakOnChannelSignalTriggers()
    {
        ChannelActivityDetector detector = new ChannelActivityDetector(SAMPLE_RATE, HALF_WIDTH, 5.0f);
        SignalGenerator generator = new SignalGenerator();
        assertFalse(run(detector, generator, 5, 0, 0), "idle before the transmission");
        assertTrue(run(detector, generator, 2, 0, amplitudeForPassbandSnr(7)), "7 dB in the passband triggers");
        assertFalse(run(detector, generator, 2, 0, 0) && detector.isActive(), "idle again after it ends");
    }

    @Test
    public void strongNeighbourDoesNotTrigger()
    {
        ChannelActivityDetector detector = new ChannelActivityDetector(SAMPLE_RATE, HALF_WIDTH, 5.0f);
        SignalGenerator generator = new SignalGenerator();
        run(detector, generator, 5, 0, 0);
        //A neighbour at 7.5 kHz spacing, 30 dB above the noise, as on the channel these captures came from
        assertFalse(run(detector, generator, 5, 7500, amplitudeForPassbandSnr(30)));
        assertFalse(run(detector, generator, 5, -15000, amplitudeForPassbandSnr(30)));
    }

    @Test
    public void stationFiveKilohertzAwayDoesNotTrigger()
    {
        ChannelActivityDetector detector = new ChannelActivityDetector(SAMPLE_RATE, HALF_WIDTH, 5.0f);
        SignalGenerator generator = new SignalGenerator();
        run(detector, generator, 5, 0, 0);
        assertFalse(run(detector, generator, 5, 5000, amplitudeForPassbandSnr(20)));
    }

    @Test
    public void droppedBufferDoesNotPullTheFloorDown()
    {
        ChannelActivityDetector detector = new ChannelActivityDetector(SAMPLE_RATE, HALF_WIDTH, 5.0f);
        SignalGenerator generator = new SignalGenerator();
        run(detector, generator, 5, 0, 0);
        float floor = detector.getNoiseFloorDb();

        detector.receive(new ComplexSamples(new float[4096], new float[4096], 0));

        assertEquals(floor, detector.getNoiseFloorDb(), 1.0f);
        assertFalse(run(detector, generator, 5, 0, 0), "noise after the dropped buffer doesn't trigger");
    }
}
