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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode regression against real reference recordings of clean signals.  Each recording's capture conditions and
 * expected results are documented in the README.md beside it under src/test/resources/p25/.
 *
 * Voice frame quality (garbage LDU detection) is checked only when the JMBE library is available, via the JMBE_PATH
 * environment variable, e.g. JMBE_PATH=/path/to/jmbe-1.0.9.jar ./gradlew test --tests '*P25ReferenceRecordingTest'
 */
public class P25ReferenceRecordingTest
{
    private static final String RAYMOND_PD = "/p25/raymond-pd/good-antenna_20261111_202316_10-20s_155640000_baseband.wav";
    private static final int RAYMOND_PD_NAC = 0x860;
    private static final String ROCK_COUNTY_PD_W = "/p25/rock-county-pd-w/rock-county-pd-w_20261111_204909_2.5-18.5s_154815000_baseband.wav";
    private static final int ROCK_COUNTY_PD_W_NAC = 0x117;

    //An LDU whose 9 IMBE frames need >= 90 bit corrections in total is noise decoded as voice (an intact frame needs 0-12)
    private static final int GARBAGE_LDU_IMBE_ERRORS = 90;

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
        int garbageLdu = -1; //-1 when JMBE isn't available

        @Override
        public String toString()
        {
            return "validLDU=" + validLdu + " HDU=" + hdu + " clearLDU2=" + clearLdu2 + " encryptedLDU2=" +
                    encryptedLdu2 + " foreignNAC=" + foreignNac + " garbageLDU=" + garbageLdu;
        }
    }

    /**
     * Counts IMBE bit corrections per LDU using JMBE's IMBE frame decoder, loaded from JMBE_PATH.
     * ponytail: reflection keeps JMBE (not a build dependency) optional for the test.
     */
    static class ImbeErrorCounter
    {
        private final Constructor<?> mFrame;
        private final Constructor<?> mParameters;
        private final Method mGetModelParameters;
        private final Method mGetErrorCountTotal;

        private ImbeErrorCounter(ClassLoader loader) throws Exception
        {
            Class<?> frame = Class.forName("jmbe.codec.imbe.IMBEFrame", true, loader);
            Class<?> parameters = Class.forName("jmbe.codec.imbe.IMBEModelParameters", true, loader);
            mFrame = frame.getConstructor(byte[].class);
            mParameters = parameters.getConstructor();
            mGetModelParameters = frame.getMethod("getModelParameters", parameters);
            mGetErrorCountTotal = parameters.getMethod("getErrorCountTotal");
        }

        static ImbeErrorCounter load()
        {
            String path = System.getenv("JMBE_PATH");
            if(path == null)
            {
                return null;
            }

            TestJmbeCodecLoader loader = new TestJmbeCodecLoader(Path.of(path));
            if(!loader.isLoaded())
            {
                return null;
            }

            try
            {
                return new ImbeErrorCounter(loader.getCodec().getClass().getClassLoader());
            }
            catch(Exception e)
            {
                return null;
            }
        }

        int errors(LDUMessage ldu)
        {
            int total = 0;

            try
            {
                for(byte[] imbe : ldu.getIMBEFrames())
                {
                    Object parameters = mGetModelParameters.invoke(mFrame.newInstance((Object)imbe), mParameters.newInstance());
                    total += (Integer)mGetErrorCountTotal.invoke(parameters);
                }
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }

            return total;
        }
    }

    /**
     * Decoder under test, wrapped so each P25P1 decoder class can be driven the same way.
     */
    record DecoderUnderTest(String name, Consumer<ComplexSamples> receiver, DoubleConsumer sampleRate) {}

    private static DecoderUnderTest c4fm(int nac, Listener<IMessage> listener)
    {
        P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
        decoder.setConfiguredNAC(nac);
        decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
        decoder.setMessageListener(listener);
        decoder.start();
        return new DecoderUnderTest("C4FM", decoder::receive, decoder::setSampleRate);
    }

    private static DecoderUnderTest c4fmV2(int nac, Listener<IMessage> listener)
    {
        P25P1DecoderC4FMv2 decoder = new P25P1DecoderC4FMv2();
        decoder.setConfiguredNAC(nac);
        decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
        decoder.setMessageListener(listener);
        decoder.start();
        return new DecoderUnderTest("C4FM_V2", decoder::receive, decoder::setSampleRate);
    }

    private static DecoderUnderTest cqpsk(int nac, Listener<IMessage> listener)
    {
        P25P1DecoderLSM decoder = new P25P1DecoderLSM();
        decoder.setConfiguredNAC(nac);
        decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
        decoder.setMessageListener(listener);
        decoder.start();
        return new DecoderUnderTest("CQPSK", decoder::receive, decoder::setSampleRate);
    }

    interface DecoderFactory
    {
        DecoderUnderTest create(int nac, Listener<IMessage> listener);
    }

    private static Result decode(String resource, int nac, DecoderFactory factory) throws Exception
    {
        URL url = P25ReferenceRecordingTest.class.getResource(resource);
        assertNotNull(url, "Missing test recording " + resource);

        Result result = new Result();
        ImbeErrorCounter imbe = ImbeErrorCounter.load();
        if(imbe != null)
        {
            result.garbageLdu = 0;
        }

        Listener<IMessage> listener = message -> {
            if(message instanceof P25P1Message p25 && p25.isValid())
            {
                Identifier messageNac = p25.getNAC();
                if(messageNac != null && messageNac.getValue() instanceof Integer value && value != nac)
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

                if(imbe != null && imbe.errors(ldu) >= GARBAGE_LDU_IMBE_ERRORS)
                {
                    result.garbageLdu++;
                }
            }
        };

        DecoderUnderTest decoder = factory.create(nac, listener);

        try(TestComplexWaveSource source = new TestComplexWaveSource(new File(url.toURI())))
        {
            decoder.sampleRate().accept(source.getSampleRate());
            source.setListener(buffer -> {
                Iterator<ComplexSamples> it = buffer.iterator();
                while(it.hasNext())
                {
                    decoder.receiver().accept(it.next());
                }
            });

            while(source.next(2048))
            {
                //Process the full recording
            }
        }

        System.out.println(resource.substring(resource.lastIndexOf('/') + 1) + " " + decoder.name() + ": " + result);
        return result;
    }

    /**
     * Raymond PD, good antenna: one call, two keyups, ~14 dB over the noise floor.  An adjacent P25 system
     * (NAC 0x117, 155.625 MHz) is in the recording and must never be decoded.
     */
    private static void assertRaymondPD(Result r)
    {
        assertTrue(r.validLdu >= 20, "Expected the full call (>= 20 valid LDUs): " + r);
        assertTrue(r.hdu >= 1, "Expected the call header (HDU): " + r);
        assertTrue(r.clearLdu2 >= 8, "Expected clear LDU2 encryption sync on a clean signal: " + r);
        assertEquals(0, r.encryptedLdu2, "Channel is never encrypted: " + r);
        assertEquals(0, r.foreignNac, "Adjacent channel (NAC 0x117) leaked into the decode: " + r);
    }

    @Test
    public void raymondPDC4FM() throws Exception
    {
        assertRaymondPD(decode(RAYMOND_PD, RAYMOND_PD_NAC, P25ReferenceRecordingTest::c4fm));
    }

    @Test
    public void raymondPDC4FMv2() throws Exception
    {
        assertRaymondPD(decode(RAYMOND_PD, RAYMOND_PD_NAC, P25ReferenceRecordingTest::c4fmV2));
    }

    /**
     * Rock County PD W: one continuous transmission at ~32 dB over the noise floor.  CQPSK decodes it cleanly (rated
     * 5/5 by ear); CQPSK_V2 decodes bursts of garbage LDUs in the same recording and is documented as a known issue
     * rather than tested here.
     */
    @Test
    public void rockCountyPDWCQPSK() throws Exception
    {
        Result r = decode(ROCK_COUNTY_PD_W, ROCK_COUNTY_PD_W_NAC, P25ReferenceRecordingTest::cqpsk);
        assertTrue(r.validLdu >= 80, "Expected the full transmission (>= 80 valid LDUs): " + r);
        assertTrue(r.clearLdu2 >= 35, "Expected clear LDU2 encryption sync on a clean signal: " + r);
        assertEquals(0, r.encryptedLdu2, "Channel is never encrypted: " + r);
        assertEquals(0, r.foreignNac, "Unexpected NAC in the decode: " + r);
        if(r.garbageLdu >= 0)
        {
            assertEquals(0, r.garbageLdu, "Garbage voice frames on a clean signal: " + r);
        }
    }
}
