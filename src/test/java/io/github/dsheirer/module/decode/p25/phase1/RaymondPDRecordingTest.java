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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.Iterator;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode regression against a real Raymond PD capture (155.640 MHz, P25 Phase 1 C4FM conventional, NAC 0x860,
 * never encrypted) with an adjacent P25 system in the recording.  See src/test/resources/p25/raymond-pd/README.md
 * for capture conditions and expected results.
 *
 * ponytail: counts decoder output only (valid LDUs, HDUs, encryption sync).  JMBE emits repeat/comfort audio for nearly
 * every frame, so audio energy can't separate good voice from bad; add IMBE error scoring if that becomes the target.
 */
public class RaymondPDRecordingTest
{
    private static final int NAC = 0x860;
    private static final String GOOD = "/p25/raymond-pd/good-antenna_20261111_202316_10-20s_155640000_baseband.wav";

    /**
     * Decode statistics for one recording/decoder run.
     */
    static class Result
    {
        int validLdu;
        int hdu;
        int clearLdu2;
        int encryptedLdu2;
        int foreignNac;

        @Override
        public String toString()
        {
            return "validLDU=" + validLdu + " HDU=" + hdu + " clearLDU2=" + clearLdu2 + " encryptedLDU2=" +
                    encryptedLdu2 + " foreignNAC=" + foreignNac;
        }
    }

    private static Result decode(String resource, boolean v2) throws Exception
    {
        URL url = RaymondPDRecordingTest.class.getResource(resource);
        assertNotNull(url, "Missing test recording " + resource);

        Result result = new Result();

        Listener<IMessage> listener = message -> {
            if(message instanceof P25P1Message p25 && p25.isValid())
            {
                Identifier nac = p25.getNAC();
                if(nac != null && nac.getValue() instanceof Integer value && value != NAC)
                {
                    result.foreignNac++;
                }
            }

            if(message instanceof HDUMessage hdu && hdu.isValid())
            {
                result.hdu++;
            }

            if(message instanceof LDUMessage ldu && ldu.isValid())
            {
                result.validLdu++;

                if(ldu instanceof LDU2Message ldu2 && ldu2.getEncryptionSyncParameters().isValid())
                {
                    if(ldu2.getEncryptionSyncParameters().isEncryptedAudio())
                    {
                        result.encryptedLdu2++;
                    }
                    else
                    {
                        result.clearLdu2++;
                    }
                }
            }
        };

        Consumer<ComplexSamples> receiver;
        java.util.function.DoubleConsumer sampleRate;

        if(v2)
        {
            P25P1DecoderC4FMv2 decoder = new P25P1DecoderC4FMv2();
            decoder.setConfiguredNAC(NAC);
            decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
            decoder.setMessageListener(listener);
            decoder.start();
            receiver = decoder::receive;
            sampleRate = decoder::setSampleRate;
        }
        else
        {
            P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
            decoder.setConfiguredNAC(NAC);
            decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
            decoder.setMessageListener(listener);
            decoder.start();
            receiver = decoder::receive;
            sampleRate = decoder::setSampleRate;
        }

        try(TestComplexWaveSource source = new TestComplexWaveSource(new File(url.toURI())))
        {
            sampleRate.accept(source.getSampleRate());
            source.setListener(buffer -> {
                Iterator<ComplexSamples> it = buffer.iterator();
                while(it.hasNext())
                {
                    receiver.accept(it.next());
                }
            });

            while(source.next(2048))
            {
                //Process the full recording
            }
        }

        System.out.println(resource.substring(resource.lastIndexOf('/') + 1) + (v2 ? " C4FM_V2: " : " C4FM: ") + result);
        return result;
    }

    /**
     * Good antenna: one call, two keyups, ~14 dB over the noise floor.  Every build decodes 21 LDUs.  An adjacent
     * P25 system (NAC 0x117, 155.625 MHz, 15 kHz below) is in the recording and must never be decoded.
     */
    private static void assertGoodAntenna(Result r)
    {
        assertTrue(r.validLdu >= 20, "Expected the full call (>= 20 valid LDUs): " + r);
        assertTrue(r.hdu >= 1, "Expected the call header (HDU): " + r);
        assertTrue(r.clearLdu2 >= 8, "Expected clear LDU2 encryption sync on a clean signal: " + r);
        assertEquals(0, r.encryptedLdu2, "Channel is never encrypted: " + r);
        assertEquals(0, r.foreignNac, "Adjacent channel (NAC 0x117) leaked into the decode: " + r);
    }

    @Test
    public void goodAntennaC4FM() throws Exception
    {
        assertGoodAntenna(decode(GOOD, false));
    }

    @Test
    public void goodAntennaC4FMv2() throws Exception
    {
        assertGoodAntenna(decode(GOOD, true));
    }
}
