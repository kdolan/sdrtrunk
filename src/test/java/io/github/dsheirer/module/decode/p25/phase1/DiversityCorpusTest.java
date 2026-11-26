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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import jmbe.frame.CorrectedIMBEFrame;
import org.junit.jupiter.api.Test;

/**
 * The weak-signal diversity decoder must leave the primary decode intact: the same messages in the same order, and
 * every voice frame the standard decoder decoded without Golay errors unchanged byte for byte.  On a weak clip it
 * must replace some of the frames the standard decoder got wrong.
 */
public class DiversityCorpusTest
{
    private static List<IMessage> decode(File file, int nac, boolean diversity) throws Exception
    {
        List<IMessage> messages = new ArrayList<>();
        P25P1DecoderC4FM decoder = diversity ? new P25P1DecoderC4FMDiversity() : new P25P1DecoderC4FM();
        decoder.setConfiguredNAC(nac);
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
                //Process the whole clip
            }
        }

        decoder.stop();
        return messages;
    }

    private static int golayErrors(CorrectedIMBEFrame frame)
    {
        int[] errors = frame.getErrors();
        return errors[0] + errors[1] + errors[2] + errors[3];
    }

    /**
     * Decodes the clip both ways, checks the primary is intact and returns the number of frames diversity changed.
     */
    private static int compare(String path, int nac) throws Exception
    {
        File file = new File(path);
        assumeTrue(file.exists(), "corpus clip not present: " + file);

        List<IMessage> standard = decode(file, nac, false);
        List<IMessage> diversity = decode(file, nac, true);

        assertEquals(standard.size(), diversity.size(), path + ": same number of messages");

        int changed = 0;
        for(int x = 0; x < standard.size(); x++)
        {
            IMessage s = standard.get(x);
            IMessage d = diversity.get(x);
            assertEquals(s.getClass(), d.getClass(), path + ": message " + x + " type");

            if(s instanceof LDUMessage sl && d instanceof LDUMessage dl)
            {
                for(int f = 0; f < sl.getIMBEFrames().size(); f++)
                {
                    byte[] sf = sl.getIMBEFrames().get(f);
                    byte[] df = dl.getIMBEFrames().get(f);

                    if(golayErrors(sl.getCorrectedIMBEFrames().get(f)) == 0)
                    {
                        assertArrayEquals(sf, df, path + ": error-free frame " + f + " of message " + x + " changed");
                    }
                    else if(!java.util.Arrays.equals(sf, df))
                    {
                        changed++;
                    }
                }
            }
        }

        return changed;
    }

    @Test
    public void cleanCallsAreUntouched() throws Exception
    {
        compare("testdata/baseband/p25-c4fm-g/p25-c4fm-g_02_100000010_baseband.wav", 2144);
        compare("testdata/baseband/p25-c4fm-f/p25-c4fm-f_01_100000007_baseband.wav", 2311);
    }

    @Test
    public void weakCallFramesAreReplaced() throws Exception
    {
        int changed = compare("testdata/baseband/p25-c4fm-g/p25-c4fm-g_03_100000010_baseband.wav", 2144);
        assertTrue(changed > 0, "diversity replaces frames the standard decoder got wrong on a weak call");
    }
}
