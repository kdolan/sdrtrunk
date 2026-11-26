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

import io.github.dsheirer.module.decode.p25.audio.LDUVoiceQuality;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Decodes the p25-c4fm-g corpus clips and checks how their LDUs are classified for audio.  The regression gate only
 * fails when a metric falls, so it can't catch junk audio coming back on a clip whose baseline is zero audio.  This
 * test does.
 *
 * - p25-c4fm-g_01: a unit received at ~4 dB in-band SNR.  Sync, NAC and link control decode, so the call is accepted,
 *   but every voice frame is near-random bits.  Every LDU must be classified unrecoverable, so that the call produces
 *   no audio instead of isolated clicks.
 * - p25-c4fm-g_02: clean calls at ~12 dB.  At call start a false sync starts a phantom LDU2 that the real LDU2's sync
 *   truncates, and at call end an LDU stub is truncated after 138 bits.  The truncated LDUs must be flagged and
 *   suppressed while the real voice LDUs stay recoverable.
 * - p25-c4fm-g_03: the same unit at ~5 dB.  Many of its LDUs total fewer errors than the single-LDU threshold, so only
 *   the call-level clean-frame window (LDUVoiceQuality) catches them: before it, this call came out as an audio segment
 *   of silence and odd clicks.  LDUVoiceQuality must accept none of the LDUs of either weak call.
 */
public class UnrecoverableVoiceCorpusTest
{
    private static final String CORPUS = "testdata/baseband/p25-c4fm-g/";
    private static final int NAC = 2144;

    private static List<LDUMessage> decode(String fileName) throws Exception
    {
        File file = new File(CORPUS + fileName);
        assumeTrue(file.exists(), "corpus clip not present: " + file);

        List<LDUMessage> ldus = new ArrayList<>();
        P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
        decoder.setConfiguredNAC(NAC);
        decoder.getMessageFramer().setMaxBchErrors(5);
        decoder.setMessageListener(message -> {
            if(message instanceof LDUMessage ldu && !ldu.isDuidCorrected())
            {
                ldus.add(ldu);
            }
        });
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
        return ldus;
    }

    @Test
    public void weakUnitProducesNoRecoverableVoice() throws Exception
    {
        List<LDUMessage> ldus = decode("p25-c4fm-g_01_100000010_baseband.wav");

        assertTrue(ldus.size() >= 20, "the weak call's LDUs still decode: " + ldus.size());

        long recoverable = ldus.stream().filter(ldu -> !P25P1AudioModule.isUnrecoverable(ldu)).count();
        assertEquals(0, recoverable, "every LDU of the weak call is unrecoverable voice");
    }

    /**
     * Runs the LDUs through LDUVoiceQuality as the audio module does, starting a new call at a gap of more than a
     * second, and counts the accepted LDUs.
     */
    private static long accepted(List<LDUMessage> ldus)
    {
        LDUVoiceQuality quality = new LDUVoiceQuality();
        long previous = 0;
        long accepted = 0;

        for(LDUMessage ldu: ldus)
        {
            if(previous > 0 && ldu.getTimestamp() - previous > 1000)
            {
                quality.reset();
            }
            previous = ldu.getTimestamp();

            if(quality.accept(ldu))
            {
                accepted++;
            }
        }

        return accepted;
    }

    @Test
    public void weakCallsProduceNoAudio() throws Exception
    {
        for(String clip: List.of("p25-c4fm-g_01_100000010_baseband.wav", "p25-c4fm-g_03_100000010_baseband.wav"))
        {
            List<LDUMessage> ldus = decode(clip);
            assertTrue(ldus.size() >= 20, clip + " LDUs still decode: " + ldus.size());
            assertEquals(0, accepted(ldus), clip + ": no LDU of a weak call is decoded for audio");
        }
    }

    @Test
    public void cleanCallsAreDecodedByTheCallLevelRule() throws Exception
    {
        List<LDUMessage> ldus = decode("p25-c4fm-g_02_100000010_baseband.wav");
        long recoverable = ldus.stream().filter(ldu -> !P25P1AudioModule.isUnrecoverable(ldu)).count();
        assertEquals(recoverable, accepted(ldus), "the call window rejects nothing the single-LDU test accepts");
    }

    @Test
    public void truncatedLdusAreSuppressedAndCleanVoiceIsKept() throws Exception
    {
        List<LDUMessage> ldus = decode("p25-c4fm-g_02_100000010_baseband.wav");

        List<LDUMessage> truncated = ldus.stream().filter(LDUMessage::isTruncated).toList();
        assertTrue(truncated.size() >= 2, "phantom LDU2 and end-of-call stub are flagged truncated: " +
            truncated.size());

        for(LDUMessage ldu: truncated)
        {
            assertTrue(P25P1AudioModule.isUnrecoverable(ldu), "truncated LDU carries no recoverable voice: " +
                P25P1AudioModule.getReceivedFrameCount(ldu) + " frames received, error total " +
                P25P1AudioModule.getErrorTotal(ldu));
        }

        long recoverable = ldus.stream().filter(ldu -> !P25P1AudioModule.isUnrecoverable(ldu)).count();
        assertTrue(recoverable >= 35, "clean voice LDUs are kept: " + recoverable + " of " + ldus.size());
    }
}
