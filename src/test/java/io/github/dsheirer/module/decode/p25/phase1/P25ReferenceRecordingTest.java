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
        int validLdu; //Voice LDUs, excluding the DUID-corrected ones
        int correctedLdu; //TDUs that the framer rewrote as LDUs (DUID correction)
        int hdu;
        int clearLdu2;
        int encryptedLdu2;
        int foreignNac;
        int garbageLdu = -1; //-1 when JMBE isn't available
        //Valid messages by class name, ie TDUMessage or AcknowledgeResponse
        java.util.Map<String,Integer> types = new java.util.TreeMap<>();

        @Override
        public String toString()
        {
            return "validLDU=" + validLdu + " correctedLDU=" + correctedLdu + " HDU=" + hdu + " clearLDU2=" + clearLdu2 + " encryptedLDU2=" +
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
            if(message.isValid())
            {
                result.types.merge(message.getClass().getSimpleName(), 1, Integer::sum);
            }

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

            //A DUID-corrected LDU is a TDU that the framer rewrote as an LDU.  The live audio module silences it, so
            //it's counted separately rather than as a voice frame.
            if(message instanceof LDUMessage corrected && corrected.isValid() && corrected.isDuidCorrected())
            {
                result.correctedLdu++;
            }
            else if(message instanceof LDUMessage ldu && ldu.isValid())
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
        assertTrue(r.validLdu >= 15, "Expected the full call (16 voice LDUs, floor 15): " + r);
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
     * Rock County PD W collection (2026-11-12 to 2026-11-19, ~29 dB, centred): long keyups, short bursts and rapid
     * back-and-forth exchanges.  Each floor is ~90% of the voice LDUs CQPSK decoded on 2026-11-22, which exclude TDUs
     * that DUID correction rewrote as LDUs.  Four of the short recordings (all but short_20261113_172118) are
     * signalling bursts of back-to-back TDUs with 1 or 2 voice LDUs: their floors were 14 to 27 while unlimited DUID
     * correction turned the TDUs into LDUs.  The two short
     * signalling bursts (TDUs and TSBKs, no voice) are tested by rockCountyPDWSignallingBursts, and the off-frequency
     * recordings by P25P1LSMFrequencyAmbiguityTest.
     */
    private static final String[][] ROCK_COUNTY_PD_W_COLLECTION = {
        {"long/long_20261112_130208_2.5-37.5s_154815000_baseband.wav", "139"},
        {"long/long_20261113_114157_2.9-46.0s_154815000_baseband.wav", "146"},
        {"long/long_20261114_202546_0.3-57.1s_154815000_baseband.wav", "201"},
        {"long/long_20261115_154857_0.0-37.3s_154815000_baseband.wav", "63"},
        {"long/long_20261116_124620_0.2-35.5s_154815000_baseband.wav", "137"},
        {"long/long_20261117_194143_1.9-40.4s_154815000_baseband.wav", "146"},
        {"long/long_20261119_160807_3.0-47.8s_154815000_baseband.wav", "153"},
        {"short/short_20261112_194046_2.9-13.3s_154815000_baseband.wav", "0"},
        {"short/short_20261113_172118_1.3-3.6s_154815000_baseband.wav", "9"},
        {"short/short_20261114_040030_1.8-5.8s_154815000_baseband.wav", "0"},
        {"short/short_20261115_224411_1.3-5.4s_154815000_baseband.wav", "1"},
        {"short/short_20261118_224522_2.9-6.9s_154815000_baseband.wav", "0"},
        {"rapid/rapid_20261112_182227_2.9-48.0s_154815000_baseband.wav", "101"},
        {"rapid/rapid_20261113_090154_2.9-33.1s_154815000_baseband.wav", "62"},
        {"rapid/rapid_20261114_150713_2.9-29.9s_154815000_baseband.wav", "60"},
        {"rapid/rapid_20261115_235615_3.0-29.6s_154815000_baseband.wav", "52"},
        {"rapid/rapid_20261116_200154_3.0-39.9s_154815000_baseband.wav", "89"},
        {"rapid/rapid_20261118_180405_0.0-42.5s_154815000_baseband.wav", "83"},
        {"rapid/rapid_20261119_120004_2.9-35.1s_154815000_baseband.wav", "54"},
    };

    @Test
    public void rockCountyPDWCollectionCQPSK() throws Exception
    {
        StringBuilder failures = new StringBuilder();

        for(String[] entry : ROCK_COUNTY_PD_W_COLLECTION)
        {
            Result r = decode("/p25/rock-county-pd-w/" + entry[0], ROCK_COUNTY_PD_W_NAC, P25ReferenceRecordingTest::cqpsk);
            int floor = Integer.parseInt(entry[1]);

            //The channel is never encrypted and has one NAC: an occasional "encrypted" LDU2 or foreign NAC is a bit-error
            //artifact, while real encryption flags every LDU2 (half of the LDUs).  The artifact count varies slightly by
            //platform (rapid_20261119_120004: 2 on Windows, 3 on the Linux CI runner), so the limit scales with the
            //decode: 5% of the valid LDUs, at least 2.
            int artifactLimit = Math.max(2, r.validLdu / 20);

            if(r.validLdu < floor || r.encryptedLdu2 > artifactLimit || r.foreignNac > artifactLimit)
            {
                failures.append("\n").append(entry[0]).append(" (floor ").append(floor).append("): ").append(r);
            }
        }

        assertTrue(failures.isEmpty(), "Collection decode regressions:" + failures);
    }

    /**
     * Rock County PD W short signalling bursts with no voice, decoded the same as the live system (CQPSK).  Live log
     * cross-section for each (decoded message log, consecutive identical lines counted):
     *
     * short_20261116_090436 (2026-11-16 09:04:36):
     * <pre>
     *   090436-090438  ~105x PASSED NAC:279/x117 TDU
     *   090449         voice resumes: HDU TALKGROUP:1, then LDU1 FM:80003 TO:1 (after this excerpt)
     * </pre>
     *
     * short_20261117_065750 (2026-11-17 06:57:50), an emergency alarm from unit 866521 acknowledged by the system:
     * <pre>
     *   065750  PASSED TSBK1 DENY_RESPONSE TO:866521 SERVICE:GRP_V_REQ REASON:RESERVED
     *   065750  PASSED TSBK1 ACK_RESPONSE_FNE FM:16777212 TO:866521 ACKNOWLEDGING:EMRG_ALRM_REQ
     *   065751  FAILED NAC:1116/x45C TDULC ***LINK CONTROL CRC FAIL***
     *   065751  PASSED DROPPED SAMPLES (POSSIBLY) DETECTED -- COULD NOT ACCOUNT FOR [2] SYMBOL BITS
     *   065753  PASSED TSBK1 RESERVED_23 **UNRECOGNIZED OSP OPCODE: 35
     *   065750-065755  ~270x PASSED NAC:279/x117 TDU
     *   065805  voice resumes: HDU TALKGROUP:1, then LDU1 FM:80001 TO:1 (after this excerpt)
     * call events: 06:57:50 Response DENY: GROUP VOICE SERVICE REQUEST (866521)
     *              06:57:50 Response 16777212 -> (866521) ACKNOWLEDGE EMERGENCY ALARM REQUEST
     * </pre>
     */
    @Test
    public void rockCountyPDWSignallingBursts() throws Exception
    {
        Result tdus = decode("/p25/rock-county-pd-w/short/short_20261116_090436_2.9-6.8s_154815000_baseband.wav",
                ROCK_COUNTY_PD_W_NAC, P25ReferenceRecordingTest::cqpsk);
        assertEquals(0, tdus.validLdu, "No voice: " + tdus.types);
        assertTrue(tdus.types.getOrDefault("TDUMessage", 0) >= 120, "TDU burst (141 on 2026-11-21): " + tdus.types);

        Result alarm = decode("/p25/rock-county-pd-w/short/short_20261117_065750_3.0-9.5s_154815000_baseband.wav",
                ROCK_COUNTY_PD_W_NAC, P25ReferenceRecordingTest::cqpsk);
        assertEquals(0, alarm.validLdu, "No voice: " + alarm.types);
        assertTrue(alarm.types.getOrDefault("TDUMessage", 0) >= 245, "TDU burst (275 on 2026-11-21): " + alarm.types);
        assertEquals(1, alarm.types.getOrDefault("AcknowledgeResponse", 0), "Emergency alarm ACK: " + alarm.types);
        assertEquals(1, alarm.types.getOrDefault("DenyResponse", 0), "Group voice request DENY: " + alarm.types);
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
        assertTrue(r.validLdu >= 67, "Expected the full transmission (74 voice LDUs, floor 67): " + r);
        assertTrue(r.clearLdu2 >= 35, "Expected clear LDU2 encryption sync on a clean signal: " + r);
        assertEquals(0, r.encryptedLdu2, "Channel is never encrypted: " + r);
        assertEquals(0, r.foreignNac, "Unexpected NAC in the decode: " + r);
        if(r.garbageLdu >= 0)
        {
            assertEquals(0, r.garbageLdu, "Garbage voice frames on a clean signal: " + r);
        }
    }
}
