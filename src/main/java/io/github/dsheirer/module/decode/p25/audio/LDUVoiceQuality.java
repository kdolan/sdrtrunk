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

import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import java.util.List;
import jmbe.frame.CorrectedIMBEFrame;

/**
 * Decides, LDU by LDU within a call, whether the voice frames carry recoverable voice.
 *
 * The corrected error count of a single IMBE frame doesn't tell voice from noise: on a weak transmission (4-6 dB
 * in-band SNR) the codec synthesizes audible output from about a third of the frames at every error count, which is
 * the isolated clicks, while a fading but intelligible signal still decodes frames with 4-9 corrected errors as speech.
 * What separates them is how many clean frames (3 or fewer errors) the signal produces: about 3% of frames on a weak
 * transmission, against 46% on the noisiest intelligible capture in the corpus and ~95% on a clean call.  So an LDU is
 * unrecoverable when it has fewer than 3 clean frames of its own and either its errors are high (72+ across 9 frames)
 * or fewer than 20% of the frames in the last 3 LDUs of the call are clean.  A call's audio only starts on an LDU with
 * at least 3 clean frames when that 3-LDU window is at least 20% clean, so a weak transmission whose odd LDU happens to
 * have a few clean frames doesn't open an audio segment that is then filled with silence.  Within a call, a short run of
 * corrupted LDUs is still decoded (the codec conceals it) until the window drops below 20%.
 *
 * Replayed over 319 captures of a channel with weak units and the regression corpus, the rule decoded exactly the
 * same LDUs as before on every intelligible call, and no audio at all on the transmissions that had none.
 */
public class LDUVoiceQuality
{
    private static final int CLEAN_FRAME_MAX_ERRORS = 3;
    private static final int MIN_CLEAN_FRAMES = 3;
    private static final int WINDOW_LDUS = 3;
    private static final float MIN_WINDOW_CLEAN_FRACTION = 0.20f;

    private final int[] mWindowClean = new int[WINDOW_LDUS];
    private final int[] mWindowReceived = new int[WINDOW_LDUS];
    private int mWindowIndex = 0;
    private boolean mCallAudioStarted = false;

    /**
     * Starts a new call: clears the clean-frame window and the call audio started state.
     */
    public void reset()
    {
        java.util.Arrays.fill(mWindowClean, 0);
        java.util.Arrays.fill(mWindowReceived, 0);
        mWindowIndex = 0;
        mCallAudioStarted = false;
    }

    /**
     * Indicates if the call's audio has started, after which unrecoverable LDUs are replaced with silence to keep the
     * audio timing, rather than dropped.
     */
    public boolean isCallAudioStarted()
    {
        return mCallAudioStarted;
    }

    /**
     * Assesses the next LDU of the call, which updates the window, and indicates if its voice frames should be
     * decoded.  Pass every non-DUID-corrected LDU of the call, in order, including the ones this returns false for.
     * @param ldu to assess
     * @return true to decode the LDU's received voice frames, false if it carries no recoverable voice.
     */
    public boolean accept(LDUMessage ldu)
    {
        List<CorrectedIMBEFrame> frames = ldu.getCorrectedIMBEFrames();
        int received = 0;
        int clean = 0;

        for(int x = 0; x < frames.size(); x++)
        {
            if(ldu.isIMBEFrameReceived(x))
            {
                received++;

                if(frames.get(x).getErrorCountTotal() <= CLEAN_FRAME_MAX_ERRORS)
                {
                    clean++;
                }
            }
        }

        if(received == 0)
        {
            //Truncated before any voice frame was received: nothing to assess or decode
            return false;
        }

        mWindowClean[mWindowIndex] = clean;
        mWindowReceived[mWindowIndex] = received;
        mWindowIndex = (mWindowIndex + 1) % WINDOW_LDUS;

        int windowClean = 0;
        int windowReceived = 0;
        for(int x = 0; x < WINDOW_LDUS; x++)
        {
            windowClean += mWindowClean[x];
            windowReceived += mWindowReceived[x];
        }

        boolean windowClear = windowClean >= MIN_WINDOW_CLEAN_FRACTION * windowReceived;

        if(clean < MIN_CLEAN_FRAMES && (P25P1AudioModule.isUnrecoverable(ldu) || !windowClear))
        {
            return false;
        }

        //Start the call's audio only on an LDU with clean frames of its own in a clear window.  At the start of a call
        //the window holds just this LDU, where 2 clean frames of 9 would already clear 20%.
        if(!mCallAudioStarted && (clean < MIN_CLEAN_FRAMES || !windowClear))
        {
            return false;
        }

        mCallAudioStarted = true;
        return true;
    }
}
