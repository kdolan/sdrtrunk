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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The diversity decoder's secondary tracks sleep while the primary is quiet and catch up on a pre-roll when it wakes
 * them.  The output must be as good as running the tracks all the time: no cold start loss.
 */
public class DiversityGatingTest
{
    private static final String CORPUS = "testdata/baseband/";

    @TempDir
    Path mTemp;

    record Result(List<IMessage> messages, double activeFraction) {}

    static Result decode(File file, int nac, boolean gating) throws Exception
    {
        List<IMessage> messages = new ArrayList<>();
        P25P1DecoderC4FMDiversity decoder = new P25P1DecoderC4FMDiversity();
        decoder.setGating(gating);
        decoder.setConfiguredNAC(nac);
        decoder.setMaxBchErrors(5);
        decoder.setMessageListener(messages::add);
        decoder.start();

        try(TestComplexWaveSource source = new TestComplexWaveSource(file))
        {
            source.setListener(buffer -> {
                var iterator = buffer.iterator();
                while(iterator.hasNext())
                {
                    ComplexSamples samples = iterator.next();
                    decoder.receive(samples);
                }
            });
            decoder.setSampleRate(source.getSampleRate());
            while(source.next(2048))
            {
            }
        }

        decoder.stop();
        return new Result(messages, decoder.getActiveFraction());
    }

    /**
     * The gated decode must match the always-on decode in every message, and be no worse in its voice frames.  The
     * frames are not bit-identical: on waking, a secondary track starts its symbol timing and filters from a different
     * state than if it had been running, so some frames come out differently, either way: 2% of the frames of weak
     * Raymond captures and 17% of a 6 dB noise-degraded call, with the same number of errored frames.  Against known
     * truth (runNoiseControl -Pgating=false/true, 27 runs at 4-15 dB with and without fading) the two decoded 1570 and
     * 1567 more frames right than the standard decoder: no cold start loss.
     * @return number of LDUs
     */
    private static int assertNoLoss(File file, int nac) throws Exception
    {
        Result always = decode(file, nac, false);
        Result gated = decode(file, nac, true);
        assertEquals(1.0, always.activeFraction(), 1e-9);
        assertEquals(always.messages().size(), gated.messages().size(), file.getName() + ": message count");
        int ldus = 0, frames = 0, alwaysErrored = 0, gatedErrored = 0;

        for(int m = 0; m < always.messages().size(); m++)
        {
            IMessage a = always.messages().get(m);
            IMessage g = gated.messages().get(m);
            assertEquals(a.getClass(), g.getClass(), file.getName() + " message " + m);

            if(a instanceof LDUMessage la && g instanceof LDUMessage lg)
            {
                ldus++;
                for(int f = 0; f < 9; f++)
                {
                    frames++;
                    alwaysErrored += la.getCorrectedIMBEFrames().get(f).getErrorCountTotal() > 0 ? 1 : 0;
                    gatedErrored += lg.getCorrectedIMBEFrames().get(f).getErrorCountTotal() > 0 ? 1 : 0;
                }
            }
        }

        assertTrue(gatedErrored <= alwaysErrored + Math.max(2, frames * 0.01), file.getName() + ": errored frames " +
            "gated " + gatedErrored + " vs always on " + alwaysErrored);
        return ldus;
    }

    @Test
    public void gatedDecodeLosesNothingOnCorpusClips() throws Exception
    {
        String[][] clips = {
            {"p25-c4fm-l/p25-c4fm-l_03_100000015_baseband.wav", "279"},  //weak channel, 52% clean
            {"p25-c4fm-g/p25-c4fm-g_02_100000010_baseband.wav", "2144"}, //clean calls with a stub LDU at call end
            {"p25-c4fm-j/p25-c4fm-j_01_100000013_baseband.wav", "279"},  //strong, busy
        };

        for(String[] clip: clips)
        {
            File file = new File(CORPUS + clip[0]);
            assumeTrue(file.exists(), "corpus clip not present: " + file);
            assertTrue(assertNoLoss(file, Integer.parseInt(clip[1])) > 10, clip[0] + " has voice");
        }
    }

    @Test
    public void gatedDecodeLosesNothingOnAWeakCallAndTracksSleepWhenQuiet() throws Exception
    {
        File clean = new File(CORPUS + "p25-c4fm-f/p25-c4fm-f_01_100000007_baseband.wav");
        assumeTrue(clean.exists(), "corpus clip not present: " + clean);
        NoiseInjector.Capture capture = NoiseInjector.load(clean);
        File weak = mTemp.resolve("snr06_baseband.wav").toFile();
        NoiseInjector.write(NoiseInjector.degrade(capture, NoiseInjector.measure(capture), 6.0, 1), weak);

        assertTrue(assertNoLoss(weak, 2311) > 10, "weak call decodes");

        //The clip is a 25 s capture with quiet time around the call: the tracks sleep through some of it
        double active = decode(weak, 2311, true).activeFraction();
        assertTrue(active < 0.9, "secondary tracks processed " + active + " of the samples");
    }
}
