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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.jtransforms.fft.DoubleFFT_1D;

/**
 * Makes noise-degraded copies of a clean baseband I/Q capture (16-bit stereo WAV) at a target in-band SNR, for
 * known-truth decoder evaluation: the clean capture's decode is the truth the degraded copy is scored against.
 *
 * In-band means +/- 6.25 kHz around the channel centre (a 12.5 kHz channel).  Signal power is measured over the
 * longest run of activity (blocks more than 8 dB above the floor) and noise over the quietest 2 seconds, so the
 * capture needs some quiet time before or after the call.  White complex Gaussian noise is added over the whole
 * sample rate, scaled so that its in-band share brings the SNR down to the target; a fixed seed makes every copy
 * reproducible.  (Port of the scratch degrade.py used for the first diversity testbed, same measure.)
 */
public class NoiseInjector
{
    private static final int FFT_SIZE = 2048;
    private static final double IN_BAND_HZ = 6250.0;

    /**
     * Clean capture samples
     * @param i in-phase, scaled to +/- 1.0
     * @param q quadrature
     * @param sampleRate Hz
     */
    public record Capture(double[] i, double[] q, double sampleRate)
    {
        public int length()
        {
            return i.length;
        }
    }

    /**
     * Measured windows and SNR of a clean capture
     * @param callStart seconds
     * @param callEnd seconds
     * @param quietStart seconds
     * @param quietEnd seconds
     * @param signalPower in-band signal power (call minus noise)
     * @param noisePower in-band noise power
     */
    public record Measurement(double callStart, double callEnd, double quietStart, double quietEnd, double signalPower,
                              double noisePower)
    {
        public double snrDb()
        {
            return 10 * Math.log10(signalPower / noisePower);
        }
    }

    public static Capture load(File file) throws IOException, UnsupportedAudioFileException
    {
        try(AudioInputStream stream = AudioSystem.getAudioInputStream(file))
        {
            AudioFormat format = stream.getFormat();

            if(format.getChannels() != 2 || format.getSampleSizeInBits() != 16 || format.isBigEndian())
            {
                throw new IOException("Expected 2 channel 16-bit little-endian I/Q: " + format);
            }

            ByteBuffer bytes = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            int n = bytes.remaining() / 4;
            double[] i = new double[n];
            double[] q = new double[n];

            for(int x = 0; x < n; x++)
            {
                i[x] = bytes.getShort() / 32768.0;
                q[x] = bytes.getShort() / 32768.0;
            }

            return new Capture(i, q, format.getSampleRate());
        }
    }

    public static void write(Capture capture, File file) throws IOException
    {
        ByteBuffer bytes = ByteBuffer.allocate(capture.length() * 4).order(ByteOrder.LITTLE_ENDIAN);

        for(int x = 0; x < capture.length(); x++)
        {
            bytes.putShort(toPcm(capture.i()[x]));
            bytes.putShort(toPcm(capture.q()[x]));
        }

        AudioFormat format = new AudioFormat((float)capture.sampleRate(), 16, 2, true, false);
        try(AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(bytes.array()), format,
            capture.length()))
        {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file);
        }
    }

    private static short toPcm(double value)
    {
        return (short)Math.max(-32768, Math.min(32767, Math.round(value * 32767)));
    }

    /**
     * Mean in-band power of the samples from start (inclusive) to end, in Hann-windowed FFT blocks.
     */
    static double inBandPower(Capture capture, int start, int end)
    {
        DoubleFFT_1D fft = new DoubleFFT_1D(FFT_SIZE);
        double[] window = new double[FFT_SIZE];
        double windowEnergy = 0;

        for(int x = 0; x < FFT_SIZE; x++)
        {
            window[x] = 0.5 - 0.5 * Math.cos(2 * Math.PI * x / FFT_SIZE);
            windowEnergy += window[x] * window[x];
        }

        int bins = (int)Math.floor(IN_BAND_HZ / capture.sampleRate() * FFT_SIZE);
        double total = 0;
        int blocks = 0;

        for(int offset = start; offset + FFT_SIZE <= end; offset += FFT_SIZE)
        {
            double[] buffer = new double[2 * FFT_SIZE];

            for(int x = 0; x < FFT_SIZE; x++)
            {
                buffer[2 * x] = capture.i()[offset + x] * window[x];
                buffer[2 * x + 1] = capture.q()[offset + x] * window[x];
            }

            fft.complexForward(buffer);
            double power = 0;

            for(int bin = -bins; bin <= bins; bin++)
            {
                int k = (bin + FFT_SIZE) % FFT_SIZE;
                power += buffer[2 * k] * buffer[2 * k] + buffer[2 * k + 1] * buffer[2 * k + 1];
            }

            total += power;
            blocks++;
        }

        return blocks == 0 ? 0 : total / blocks / (FFT_SIZE * windowEnergy);
    }

    /**
     * Finds the call (longest run of 0.1 s blocks more than 8 dB above the 10th-percentile floor) and the quietest 2 s.
     */
    public static Measurement measure(Capture capture)
    {
        int block = (int)(capture.sampleRate() / 10);
        int blocks = capture.length() / block;
        double[] level = new double[blocks];

        for(int b = 0; b < blocks; b++)
        {
            level[b] = 10 * Math.log10(inBandPower(capture, b * block, Math.min(capture.length(), b * block + 1024 * 2))
                + 1e-20);
        }

        double[] sorted = level.clone();
        java.util.Arrays.sort(sorted);
        double floor = sorted[(int)(0.1 * (blocks - 1))];
        int bestStart = 0, bestEnd = 0;

        for(int b = 0; b < blocks; )
        {
            if(level[b] > floor + 8)
            {
                int e = b;
                while(e < blocks && level[e] > floor + 8)
                {
                    e++;
                }
                if(e - b > bestEnd - bestStart)
                {
                    bestStart = b;
                    bestEnd = e;
                }
                b = e;
            }
            else
            {
                b++;
            }
        }

        int quiet = 0;
        double quietest = Double.MAX_VALUE;

        for(int b = 0; b + 20 <= blocks; b++)
        {
            double sum = 0;
            for(int x = b; x < b + 20; x++)
            {
                sum += level[x];
            }
            if(sum < quietest)
            {
                quietest = sum;
                quiet = b;
            }
        }

        double callStart = bestStart / 10.0 + 0.2, callEnd = bestEnd / 10.0 - 0.2;
        double quietStart = quiet / 10.0, quietEnd = quiet / 10.0 + 2.0;
        double fs = capture.sampleRate();
        double total = inBandPower(capture, (int)(callStart * fs), (int)(callEnd * fs));
        double noise = inBandPower(capture, (int)(quietStart * fs), (int)(quietEnd * fs));
        return new Measurement(callStart, callEnd, quietStart, quietEnd, total - noise, noise);
    }

    /**
     * Copy of the capture multiplied by a flat-fading channel gain: Rician with K factor (K = 0 is Rayleigh), maximum
     * Doppler frequency dopplerHz, unit mean power, so the average SNR is unchanged.  The scattered part is a Jakes
     * sum of sinusoids with random arrival angles and phases; the line-of-sight part has a fixed phase.
     *
     * At 155 MHz the maximum Doppler is about 1 Hz for a walking user and 7-15 Hz for a vehicle at 30-60 mph.
     */
    public static Capture fade(Capture capture, double dopplerHz, double kFactor, long seed)
    {
        int paths = 32;
        Random random = new Random(seed ^ 0x5DEECE66DL);
        double[] frequency = new double[paths];
        double[] phaseI = new double[paths];
        double[] phaseQ = new double[paths];

        for(int p = 0; p < paths; p++)
        {
            double angle = 2 * Math.PI * (p + random.nextDouble()) / paths;
            frequency[p] = 2 * Math.PI * dopplerHz * Math.cos(angle) / capture.sampleRate();
            phaseI[p] = 2 * Math.PI * random.nextDouble();
            phaseQ[p] = 2 * Math.PI * random.nextDouble();
        }

        double scatter = Math.sqrt(1 / (kFactor + 1));
        double los = Math.sqrt(kFactor / (kFactor + 1));
        double norm = Math.sqrt(1.0 / paths);
        double[] i = new double[capture.length()];
        double[] q = new double[capture.length()];

        for(int x = 0; x < capture.length(); x++)
        {
            double gi = 0, gq = 0;

            for(int p = 0; p < paths; p++)
            {
                gi += Math.cos(frequency[p] * x + phaseI[p]);
                gq += Math.sin(frequency[p] * x + phaseQ[p]);
            }

            gi = los + scatter * norm * gi;
            gq = scatter * norm * gq;
            i[x] = capture.i()[x] * gi - capture.q()[x] * gq;
            q[x] = capture.i()[x] * gq + capture.q()[x] * gi;
        }

        return new Capture(i, q, capture.sampleRate());
    }

    /**
     * Copy of the capture with white Gaussian noise added to bring the in-band SNR down to the target.  The result is
     * scaled down if needed so it doesn't clip.
     */
    public static Capture degrade(Capture capture, Measurement measurement, double snrDb, long seed)
    {
        double wanted = measurement.signalPower() / Math.pow(10, snrDb / 10);
        double add = Math.max(0, wanted - measurement.noisePower());
        //The in-band share of white noise over the full sample rate is 12.5 kHz / sample rate
        double sigma = Math.sqrt(add * capture.sampleRate() / (2 * IN_BAND_HZ) / 2);
        Random random = new Random(seed);
        double[] i = new double[capture.length()];
        double[] q = new double[capture.length()];
        double peak = 0;

        for(int x = 0; x < capture.length(); x++)
        {
            i[x] = capture.i()[x] + sigma * random.nextGaussian();
            q[x] = capture.q()[x] + sigma * random.nextGaussian();
            peak = Math.max(peak, Math.max(Math.abs(i[x]), Math.abs(q[x])));
        }

        double scale = Math.min(1.0, 0.98 / peak);

        if(scale < 1.0)
        {
            for(int x = 0; x < capture.length(); x++)
            {
                i[x] *= scale;
                q[x] *= scale;
            }
        }

        return new Capture(i, q, capture.sampleRate());
    }
}
