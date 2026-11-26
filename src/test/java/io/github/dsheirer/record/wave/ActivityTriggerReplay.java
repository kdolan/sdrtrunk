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
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Replays baseband I/Q captures through ChannelActivityDetector, so a channel's activity trigger can be dialed in
 * against recordings: prints, per capture, the seconds the trigger was active and where it first fired.
 *
 * Usage: ActivityTriggerReplay <capture or dir> [marginDb] [channelBandwidthHz]
 * Gradle: ./gradlew runActivityTriggerReplay -Psamples=path [-Pmargin=5] [-Pbandwidth=12500]
 */
public class ActivityTriggerReplay
{
    public static void main(String[] args) throws Exception
    {
        File input = new File(args[0]);
        float margin = args.length > 1 ? Float.parseFloat(args[1]) : ChannelActivityDetector.DEFAULT_TRIGGER_MARGIN_DB;
        double bandwidth = args.length > 2 ? Double.parseDouble(args[2]) : 12500.0;
        double halfWidth = ChannelActivityDetector.getPassbandHalfWidth(bandwidth);

        File[] files = input.isFile() ? new File[]{input} : input.listFiles((d, n) -> n.endsWith("_baseband.wav"));
        Arrays.sort(files);
        int triggered = 0;

        System.out.printf("Activity trigger replay: margin %.1f dB, passband +/- %.0f Hz, %d captures%n", margin,
            halfWidth, files.length);

        for(File file: files)
        {
            try(AudioInputStream stream = AudioSystem.getAudioInputStream(file))
            {
                double sampleRate = stream.getFormat().getSampleRate();
                ChannelActivityDetector detector = new ChannelActivityDetector(sampleRate, halfWidth, margin);
                byte[] bytes = new byte[2048 * 4];
                int read;
                long samples = 0;
                long activeSamples = 0;
                double firstActive = -1;

                while((read = stream.readNBytes(bytes, 0, bytes.length)) > 0)
                {
                    int count = read / 4;
                    float[] i = new float[count];
                    float[] q = new float[count];
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN);

                    for(int x = 0; x < count; x++)
                    {
                        i[x] = buffer.getShort() / 32768.0f;
                        q[x] = buffer.getShort() / 32768.0f;
                    }

                    if(detector.receive(new ComplexSamples(i, q, 0)))
                    {
                        activeSamples += count;
                        if(firstActive < 0)
                        {
                            firstActive = samples / sampleRate;
                        }
                    }

                    samples += count;
                }

                if(activeSamples > 0)
                {
                    triggered++;
                }

                System.out.printf("%s active %5.1fs first %6.2fs floor %6.1f dB%n", file.getName(),
                    activeSamples / sampleRate, firstActive, detector.getNoiseFloorDb());
            }
        }

        System.out.printf("Triggered on %d of %d captures%n", triggered, files.length);
    }
}
