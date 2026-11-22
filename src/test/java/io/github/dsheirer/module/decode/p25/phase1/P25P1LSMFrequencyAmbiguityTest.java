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

import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CQPSK (LSM) frequency correction requests across the full carrier offset range, against real Rock County PD W
 * recordings (see src/test/resources/p25/rock-county-pd-w/README.md, "Off-frequency").
 *
 * The differential demodulator can't see a carrier offset of one symbol rate (4800 Hz).  Before the fix, a signal
 * 4800 Hz off centre decoded as well as a centred one and requested almost no correction, so the channel and tuner
 * (auto PPM) correction loops held it there and the C4FM channels sharing the tuner stopped decoding (2026-11-21).
 * Offsets of 1.8 to 3.0 kHz also never synchronized, so a loop walking back from 4800 Hz stalled there.
 *
 * The channel mixer shifts the signal up by the applied correction, so a signal at +X Hz should request about -X Hz.
 */
public class P25P1LSMFrequencyAmbiguityTest
{
    private static final String BASE = "/p25/rock-county-pd-w/";
    private static final String REFERENCE = BASE + "rock-county-pd-w_20261111_204909_2.5-18.5s_154815000_baseband.wav";
    private static final String LONG = BASE + "long/long_20261114_202546_0.3-57.1s_154815000_baseband.wav";
    private static final String ALIAS = BASE + "off-frequency/off-frequency_20261121_060510_3.0-13.1s_154815000_baseband.wav";
    private static final String OFFSET_2500 = BASE + "off-frequency/off-frequency_20261121_095214_3.0-12.3s_154815000_baseband.wav";
    private static final int NAC = 0x117;
    private static final double LOOP_MAXIMUM_STEP_HZ = 50;

    /**
     * Decode result: valid LDUs and the correction requests (Hz) in the order they were made.
     */
    record Run(int validLdu, List<Long> requests, double finalCorrection)
    {
        long medianRequest()
        {
            List<Long> sorted = new ArrayList<>(requests);
            sorted.sort(null);
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }
    }

    /**
     * Decodes a recording shifted by shiftHz.  With loopGain > 0, emulates the channel frequency correction loop: every
     * 0.25 s, the applied correction moves by loopGain times the average request since the last update, clamped to
     * LOOP_MAXIMUM_STEP_HZ, and the signal is shifted by shiftHz plus the applied correction.  Like the production loop
     * (ChannelFrequencyErrorManager, which clamps to 3 Hz per second), it moves smoothly, but fast enough (200 Hz per
     * second) to converge within the recording.
     */
    private static Run decode(String resource, double shiftHz, double loopGain) throws Exception
    {
        int[] ldu = {0};
        List<Long> requests = new ArrayList<>();
        P25P1DecoderLSM decoder = new P25P1DecoderLSM();
        decoder.setConfiguredNAC(NAC);
        decoder.getMessageFramer().setMaxBchErrors(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
        decoder.setMessageListener(m -> {
            if(m instanceof LDUMessage l && l.isValid() && !l.isDuidCorrected()) //Voice LDUs: DUID-corrected TDUs are silenced live
            {
                ldu[0]++;
            }
        });
        decoder.setSourceEventListener(e -> {
            if(e.getEvent() == SourceEvent.Event.REQUEST_FREQUENCY_CORRECTION)
            {
                requests.add(e.getValue().longValue());
            }
        });
        decoder.start();

        File file = new File(P25P1LSMFrequencyAmbiguityTest.class.getResource(resource).toURI());
        double[] phase = {0};
        double[] correction = {0};
        long[] sampleCount = {0};
        int[] requestsApplied = {0};

        try(TestComplexWaveSource source = new TestComplexWaveSource(file))
        {
            double sampleRate = source.getSampleRate();
            long updateInterval = (long)(sampleRate / 4);
            decoder.setSampleRate(sampleRate);
            source.setListener(buffer -> {
                Iterator<ComplexSamples> it = buffer.iterator();
                while(it.hasNext())
                {
                    ComplexSamples samples = it.next();
                    float[] i = samples.i().clone();
                    float[] q = samples.q().clone();
                    double step = 2 * Math.PI * (shiftHz + correction[0]) / sampleRate;

                    for(int x = 0; x < i.length; x++)
                    {
                        double cos = Math.cos(phase[0]), sin = Math.sin(phase[0]);
                        float mixedI = (float)(i[x] * cos - q[x] * sin);
                        q[x] = (float)(i[x] * sin + q[x] * cos);
                        i[x] = mixedI;
                        phase[0] += step;
                    }

                    phase[0] %= 2 * Math.PI;
                    decoder.receive(new ComplexSamples(i, q, samples.timestamp()));
                    sampleCount[0] += i.length;

                    if(loopGain > 0 && sampleCount[0] >= updateInterval)
                    {
                        sampleCount[0] = 0;
                        int count = requests.size() - requestsApplied[0];

                        if(count > 0)
                        {
                            double sum = 0;
                            for(int r = requestsApplied[0]; r < requests.size(); r++)
                            {
                                sum += requests.get(r);
                            }
                            correction[0] += Math.clamp(loopGain * sum / count, -LOOP_MAXIMUM_STEP_HZ, LOOP_MAXIMUM_STEP_HZ);
                            requestsApplied[0] = requests.size();
                        }
                    }
                }
            });

            while(source.next(2048))
            {
                //Process the full recording
            }
        }

        return new Run(ldu[0], requests, correction[0]);
    }

    private static void assertRequest(String resource, double shiftHz, double toleranceHz) throws Exception
    {
        Run run = decode(resource, shiftHz, 0);
        assertTrue(run.validLdu() >= 60, "Expected the transmission to decode at a " + shiftHz + " Hz shift: " +
                run.validLdu() + " valid LDUs");
        assertEquals(-shiftHz, run.medianRequest(), toleranceHz, "Correction request at a " + shiftHz + " Hz shift");
    }

    /**
     * Test: at one symbol rate off centre, the request points back to centre instead of reporting no error.
     */
    @Test
    public void symbolRateOffsetRequestsFullCorrection() throws Exception
    {
        assertRequest(REFERENCE, 4800, 500);
        assertRequest(REFERENCE, -4800, 500);
    }

    /**
     * Test: offsets between the ambiguity search's old reach (1.8 kHz) and half of the symbol rate decode and request
     * the correct correction.
     */
    @Test
    public void midRangeOffsetsSynchronize() throws Exception
    {
        for(double shift : new double[]{-3000, -2500, -2000, 2000, 2500, 3000})
        {
            assertRequest(REFERENCE, shift, 400);
        }
    }

    /**
     * Test: centred and small offsets are unchanged by the ambiguity resolution.
     */
    @Test
    public void smallOffsetsUnchanged() throws Exception
    {
        for(double shift : new double[]{-1000, 0, 1000})
        {
            assertRequest(REFERENCE, shift, 200);
        }
    }

    /**
     * Test: the live 2026-11-21 recordings.  At 06:05 the channel sat in the false lock about 4.8 kHz above centre;
     * at 09:52 it sat about 2.5 kHz above centre and didn't decode at all.
     */
    @Test
    public void liveOffFrequencyRecordings() throws Exception
    {
        Run alias = decode(ALIAS, 0, 0);
        assertTrue(alias.validLdu() >= 22, "False lock recording decodes: " + alias.validLdu());
        assertTrue(alias.medianRequest() < -3800, "False lock recording requests a ~-4.8 kHz correction: " +
                alias.medianRequest());

        Run offset = decode(OFFSET_2500, 0, 0);
        assertTrue(offset.validLdu() >= 16, "2.5 kHz off recording decodes: " + offset.validLdu());
        assertEquals(-2500, offset.medianRequest(), 500, "2.5 kHz off recording requests ~-2.5 kHz");
    }

    /**
     * Test: a closed correction loop that starts at the 4800 Hz false lock walks back to centre and keeps decoding.
     */
    @Test
    public void closedLoopLeavesFalseLock() throws Exception
    {
        Run run = decode(LONG, 4800, 0.5);
        double residual = 4800 + run.finalCorrection();
        assertTrue(Math.abs(residual) < 300, "Residual offset after the loop converges: " + residual + " Hz");
        System.out.println("Closed loop from +4800 Hz: residual " + residual + " Hz, " + run.validLdu() + " valid LDUs");
        //122 voice LDUs on 2026-11-22, excluding DUID-corrected TDUs (205 when they counted): decoding dips while
        //the walk passes the filter edges
        assertTrue(run.validLdu() >= 110, "Keeps decoding while the loop converges: " + run.validLdu());
    }
}
