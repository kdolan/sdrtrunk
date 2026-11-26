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
package io.github.dsheirer.module.decode.p25.audio;

import io.github.dsheirer.bits.BinaryMessage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import jmbe.binary.BinaryFrame;
import jmbe.edac.Golay23;
import jmbe.edac.Hamming15;
import jmbe.frame.IMBEInterleave;

/**
 * Soft decision (Chase) decoding of an IMBE voice frame's four Golay(23,12) codewords.
 *
 * JMBE corrects each codeword from hard bit decisions, so a codeword with 4+ bit errors is lost.  This decoder also
 * knows how confident each bit decision was (from the demodulated soft symbol), and for each Golay codeword tries
 * flipping every combination of its least confident bits before the hard decode, keeping the codeword whose differences
 * from the received bits have the lowest total confidence.  It reuses JMBE's own deinterleave, Golay, Hamming and
 * scrambler code, and returns a raw frame that JMBE decodes to the chosen codewords.
 */
public class SoftIMBEFrameDecoder
{
    private static final int FRAME_BITS = 144;
    private static final int[] GOLAY_OFFSETS = {0, 23, 46, 69};
    private static final int[] HAMMING_OFFSETS = {92, 107, 122};
    private static final int[] MESSAGE_TO_DEINTERLEAVED = new int[FRAME_BITS];
    private static final Method DESCRAMBLE;
    private static final int[] RANDOMIZER_SEED;
    private static final float HALF_PI = (float)(Math.PI / 2);

    static
    {
        try
        {
            //Learn the bit order empirically so this can't disagree with JMBE: set one message bit, convert it the way
            //LDUMessage.getIMBEFrames() and CorrectedIMBEFrame.correct() do, and see where it lands after deinterleaving
            for(int bit = 0; bit < FRAME_BITS; bit++)
            {
                BinaryMessage message = new BinaryMessage(FRAME_BITS);
                message.set(bit);
                BinaryFrame frame = BinaryFrame.fromBytes(message.toByteArray(), ByteOrder.LITTLE_ENDIAN);
                IMBEInterleave.deinterleave(frame);
                MESSAGE_TO_DEINTERLEAVED[bit] = frame.nextSetBit(0);
            }

            Class<?> scrambler = Class.forName("jmbe.frame.MBEScrambler");
            DESCRAMBLE = scrambler.getDeclaredMethod("descramble", BinaryFrame.class, int.class, int.class, int.class);
            DESCRAMBLE.setAccessible(true);
            Field seed = Class.forName("jmbe.frame.CorrectedIMBEFrame").getDeclaredField("RANDOMIZER_SEED");
            seed.setAccessible(true);
            RANDOMIZER_SEED = (int[])seed.get(null);
        }
        catch(Exception e)
        {
            throw new IllegalStateException("Unable to initialize soft IMBE decoding from the JMBE API", e);
        }
    }

    /**
     * Result of soft decoding a frame.
     * @param frame raw frame bytes (as from LDUMessage.getIMBEFrames()) that decode to the chosen codewords.
     * @param metric total confidence of the received bits the chosen Golay codewords disagree with: low when the soft
     * decisions support the result, high when it took flipping confident bits.
     * @param changed true if any Golay codeword differs from the hard decision result.
     */
    public record Result(byte[] frame, float metric, boolean changed) {}

    /**
     * Bit confidence for each of the frame's 144 message bits, from the soft symbols of the LDU message.
     * @param softSymbols per dibit of the LDU message (radians)
     * @param frameOffset message bit offset of the IMBE frame
     */
    public static float[] getBitConfidence(float[] softSymbols, int frameOffset)
    {
        float[] confidence = new float[FRAME_BITS];

        for(int x = 0; x < FRAME_BITS; x++)
        {
            int bit = frameOffset + x;
            float soft = softSymbols[bit / 2];
            //First bit of the dibit is the sign, the second is inner/outer level (boundary at +/- pi/2)
            confidence[x] = (bit % 2 == 0) ? Math.abs(soft) : Math.abs(Math.abs(soft) - HALF_PI);
        }

        return confidence;
    }

    /**
     * Soft decodes the frame.
     * @param raw frame bytes from LDUMessage.getIMBEFrames()
     * @param confidence for each of the 144 message bits
     * @param chaseBits number of least confident bits per codeword to try flipping (2^n trials)
     */
    public static Result decode(byte[] raw, float[] confidence, int chaseBits)
    {
        try
        {
            BinaryFrame frame = BinaryFrame.fromBytes(raw, ByteOrder.LITTLE_ENDIAN);
            IMBEInterleave.deinterleave(frame);
            BinaryFrame received = (BinaryFrame)frame.clone();
            float[] deinterleavedConfidence = new float[FRAME_BITS];

            for(int x = 0; x < FRAME_BITS; x++)
            {
                deinterleavedConfidence[MESSAGE_TO_DEINTERLEAVED[x]] = confidence[x];
            }

            float metric = 0;
            boolean changed = false;

            Chase c0 = chase(frame, 0, 23, deinterleavedConfidence, chaseBits, true);
            metric += c0.metric;
            changed |= c0.changed;

            int seed = frame.getInt(RANDOMIZER_SEED);
            DESCRAMBLE.invoke(null, frame, seed, 23, 114);
            DESCRAMBLE.invoke(null, received, seed, 23, 114);

            for(int x = 1; x < GOLAY_OFFSETS.length; x++)
            {
                Chase c = chase(frame, GOLAY_OFFSETS[x], 23, deinterleavedConfidence, chaseBits, true);
                metric += c.metric;
                changed |= c.changed;
            }

            for(int offset: HAMMING_OFFSETS)
            {
                Chase c = chase(frame, offset, 15, deinterleavedConfidence, Math.min(chaseBits, 3), false);
                metric += c.metric;
                changed |= c.changed;
            }

            //Back to the raw transmitted form: rescramble (the scrambler is an XOR) and reinterleave
            DESCRAMBLE.invoke(null, frame, seed, 23, 114);
            BinaryMessage out = new BinaryMessage(FRAME_BITS);

            for(int x = 0; x < FRAME_BITS; x++)
            {
                if(frame.get(MESSAGE_TO_DEINTERLEAVED[x]))
                {
                    out.set(x);
                }
            }

            return new Result(out.toByteArray(), metric, changed);
        }
        catch(Exception e)
        {
            throw new IllegalStateException("Soft IMBE decode failed", e);
        }
    }

    private record Chase(float metric, boolean changed) {}

    /**
     * Chase decodes the Golay(23,12) or Hamming(15,11) codeword at the offset in place.
     */
    private static Chase chase(BinaryFrame frame, int offset, int length, float[] confidence, int chaseBits,
                               boolean golay)
    {
        boolean[] received = new boolean[length];
        Integer[] order = new Integer[length];

        for(int x = 0; x < length; x++)
        {
            received[x] = frame.get(offset + x);
            order[x] = x;
        }

        java.util.Arrays.sort(order, (a, b) -> Float.compare(confidence[offset + a], confidence[offset + b]));

        boolean[] hard = null;
        boolean[] best = null;
        float bestMetric = Float.MAX_VALUE;

        for(int pattern = 0; pattern < (1 << chaseBits); pattern++)
        {
            BinaryFrame trial = new BinaryFrame(length);

            for(int x = 0; x < length; x++)
            {
                trial.set(x, received[x]);
            }

            for(int b = 0; b < chaseBits; b++)
            {
                if((pattern & (1 << b)) != 0)
                {
                    trial.flip(order[b]);
                }
            }

            int errors = golay ? Golay23.checkAndCorrect(trial, 0) : Hamming15.checkAndCorrect(trial, 0);

            if(errors > (golay ? Golay23.MAX_CORRECTABLE_ERRORS : 1))
            {
                continue;
            }

            boolean[] codeword = new boolean[length];
            float metric = 0;

            for(int x = 0; x < length; x++)
            {
                codeword[x] = trial.get(x);

                if(codeword[x] != received[x])
                {
                    metric += confidence[offset + x];
                }
            }

            if(pattern == 0)
            {
                hard = codeword;
            }

            if(metric < bestMetric)
            {
                bestMetric = metric;
                best = codeword;
            }
        }

        if(best == null)
        {
            return new Chase(Float.MAX_VALUE / 8, false);
        }

        for(int x = 0; x < length; x++)
        {
            frame.set(offset + x, best[x]);
        }

        return new Chase(bestMetric, hard == null || !java.util.Arrays.equals(hard, best));
    }
}
