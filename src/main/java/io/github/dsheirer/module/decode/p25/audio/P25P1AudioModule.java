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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.gain.SoftLimitedGain;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.IMBEFrameDiagnostic;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.P25PipelineDiagnostics;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import jmbe.frame.CorrectedIMBEFrame;

public class P25P1AudioModule extends ImbeAudioModule
{
    private static final int FRAME_SAMPLE_COUNT = 160;          // 20ms at 8kHz

    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;
    private boolean mIgnoreEncryptionState = false;

    // Fix B: Encryption confirmation — require multiple consecutive encrypted LDU2s before silencing
    // System property p25.encrypt.confirm: 1 = disabled (original behavior), default = 2
    private static final int ENCRYPTION_CONFIRMATION_THRESHOLD =
            Integer.parseInt(System.getProperty("p25.encrypt.confirm", "2"));
    private int mConsecutiveEncryptedLDU2 = 0;

    // Fix C: LDU caching fallback — assume unencrypted if too many LDUs cached without ESP resolution
    // System property p25.cache.fallback: 0 = disabled (unlimited caching), default = 4
    private static final int MAX_CACHED_LDU_BEFORE_ASSUME_UNENCRYPTED =
            Integer.parseInt(System.getProperty("p25.cache.fallback", "4"));

    // Quality gate state
    private float[] mLastGoodFrame = null;
    private int mTotalFrameCount = 0;

    // Pre-codec quality gate: max IMBE FEC errors before substituting silence (0 = disabled)
    private int mMaxImbeErrors = 0;
    private int mPreCodecFilteredCount = 0;
    private int mConsecutiveGatedFrames = 0;
    private static final int GATE_FADE_START = 9;   // Start fading after 9 consecutive gated frames (1 LDU)
    private static final int GATE_FADE_LENGTH = 9;   // Fade to silence over next 9 frames
    private static final int CODEC_RESET_THRESHOLD = 5; // Reset codec after this many consecutive gated frames

    // Adaptive gate: auto-disable when pass rate is too low (non-bimodal error distribution)
    private static final int ADAPTIVE_WINDOW = 27;       // 3 LDUs worth of frames
    private static final float ADAPTIVE_DISABLE_RATE = 0.30f;  // Disable gate when <30% of frames pass
    private static final float ADAPTIVE_ENABLE_RATE = 0.50f;   // Re-enable gate when >50% pass
    private int mAdaptiveWindowIndex = 0;
    private int mAdaptivePassCount = 0;
    private boolean mAdaptiveGateDisabled = false;

    // Unrecoverable LDU gate.  Below roughly 6 dB in-band SNR the voice frames arrive as near-random bits while the
    // sync, NAC and link control still decode, so the call is accepted.  JMBE mutes most of those frames and lets the
    // odd one through, which comes out as isolated clicks in an otherwise silent call.  The corrected-frame error
    // totals of most of these LDUs total 80-135 across their 9 frames, against 0-45 for clean ones; isUnrecoverable()
    // is that single-LDU test.  LDUVoiceQuality adds a window of the call's last 3 LDUs, which catches the weak LDUs that
    // total less.  An LDU it rejects is not decoded, and a call doesn't start its audio on one, so a call that is
    // unrecoverable from start to finish produces no audio segment (its call event is still logged).  An LDU that the
    // framer truncated is judged on its received frames.
    // System property p25.unrecoverable.ldu.errors: 0 disables the error total test (truncated LDU padding is still
    // dropped).
    public static final int UNRECOVERABLE_LDU_ERROR_TOTAL =
            Integer.parseInt(System.getProperty("p25.unrecoverable.ldu.errors", "72"));
    private static final int CLEAN_FRAME_MAX_ERRORS = 3;
    private static final int MIN_CLEAN_FRAMES = 3;
    private final LDUVoiceQuality mVoiceQuality = new LDUVoiceQuality();
    private boolean mCodecResetPending = false;

    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    // ponytail: fixed +6 dB; upstream #2489 dropped the old 5x hard-clipped gain entirely. Level processing beyond
    // this belongs on the client so downstream tone detection sees untouched audio.
    private final SoftLimitedGain mGain = new SoftLimitedGain(2.0f, 0.8f, 0.95f);
    private List<LDUMessage> mCachedLDUMessages = new ArrayList<>();

    public P25P1AudioModule(Settings settings, AliasList aliasList)
    {
        super(settings, aliasList);
    }

    /**
     * Sets the maximum IMBE FEC errors allowed per frame before substituting silence.
     * When set to a value > 0, frames exceeding this threshold are replaced with silence
     * BEFORE being sent to the JMBE codec, preventing codec state contamination.
     * Set to 0 to disable pre-codec filtering (default).
     *
     * @param maxErrors maximum total FEC errors per IMBE frame (0 = disabled, recommended: 3)
     */
    public void setMaxImbeErrors(int maxErrors)
    {
        mMaxImbeErrors = Math.max(0, maxErrors);
    }

    public int getMaxImbeErrors()
    {
        return mMaxImbeErrors;
    }

    /**
     * Gets the total number of frames processed since the last call start.
     *
     * @return total frame count
     */
    public int getTotalFrameCount()
    {
        return mTotalFrameCount;
    }

    /**
     * Sets whether encryption detection should be ignored.
     * When true, all calls are assumed unencrypted and audio processing begins immediately
     * without waiting for encryption state verification from HDU or LDU2 messages.
     *
     * @param ignore true to bypass encryption detection
     */
    public void setIgnoreEncryptionState(boolean ignore)
    {
        mIgnoreEncryptionState = ignore;
        if(ignore)
        {
            // When ignoring encryption, treat state as established and unencrypted
            mEncryptedCallStateEstablished = true;
            mEncryptedCall = false;
        }
    }

    /**
     * Applies the fixed output gain to all audio (decoded, repeated, faded and silence frames) before packaging.
     */
    @Override
    public void addAudio(float[] audioBuffer)
    {
        super.addAudio(mGain.apply(audioBuffer));
    }

    @Override
    protected int getTimeslot()
    {
        return 0;
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
    }

    /**
     * Processes call header (HDU) and voice frame (LDU1/LDU2) messages to decode audio and to determine the
     * encrypted audio status of a call event. Only the HDU and LDU2 messages convey encrypted call status. If an
     * LDU1 message is received without a preceding HDU message, then the LDU1 message is cached until the first
     * LDU2 message is received and the encryption state can be determined. Both the LDU1 and the LDU2 message are
     * then processed for audio if the call is unencrypted.
     *
     * When ignoreEncryptionState is enabled, audio processing begins immediately without waiting for encryption
     * state verification, assuming all calls are unencrypted.
     */
    public void receive(IMessage message)
    {
        if(hasAudioCodec())
        {
            if(mEncryptedCallStateEstablished || mIgnoreEncryptionState)
            {
                if(message instanceof LDUMessage ldu)
                {
                    if(P25PipelineDiagnostics.isEnabled())
                    {
                        P25PipelineDiagnostics.log("AUDIO", "AUDIO_MOD", "LDU_PROCESS",
                            ldu.getDUID().name() + " encrypted=" + mEncryptedCall + " duidCorrected=" + ldu.isDuidCorrected());
                    }
                    // Fix B: Continuously monitor encryption state — if a valid LDU2 says unencrypted,
                    // immediately flip back (handles single corrupted LDU2 that falsely set encrypted)
                    if(ldu instanceof LDU2Message ldu2 && ldu2.getEncryptionSyncParameters().isValid())
                    {
                        if(ldu2.getEncryptionSyncParameters().isEncryptedAudio())
                        {
                            mConsecutiveEncryptedLDU2++;
                        }
                        else
                        {
                            mConsecutiveEncryptedLDU2 = 0;
                            mEncryptedCall = false;
                        }
                    }
                    processAudio(ldu);
                }
            }
            else
            {
                if(P25PipelineDiagnostics.isEnabled() && (message instanceof LDUMessage || message instanceof HDUMessage))
                {
                    P25PipelineDiagnostics.log("AUDIO", "AUDIO_MOD", "ENCRYPT_PENDING",
                        message.getClass().getSimpleName() + " cached=" + mCachedLDUMessages.size() +
                        " established=" + mEncryptedCallStateEstablished);
                }

                if(message instanceof HDUMessage hdu && hdu.isValid())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = hdu.getHeaderData().isEncryptedAudio();
                    if(P25PipelineDiagnostics.isEnabled())
                    {
                        P25PipelineDiagnostics.log("AUDIO", "AUDIO_MOD", "HDU_ENCRYPT_SET",
                            "encrypted=" + mEncryptedCall);
                    }
                }
                else if(message instanceof LDU1Message ldu1)
                {
                    // Fix C: LDU caching fallback — if too many LDUs cached without ESP resolution,
                    // assume unencrypted and flush cache (disabled when MAX is 0)
                    if(MAX_CACHED_LDU_BEFORE_ASSUME_UNENCRYPTED > 0 &&
                       mCachedLDUMessages.size() >= MAX_CACHED_LDU_BEFORE_ASSUME_UNENCRYPTED)
                    {
                        mEncryptedCallStateEstablished = true;
                        mEncryptedCall = false;
                        for(LDUMessage cachedLdu : mCachedLDUMessages)
                        {
                            processAudio(cachedLdu);
                        }
                        mCachedLDUMessages.clear();
                        processAudio(ldu1);
                    }
                    else
                    {
                        //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                        //until we can determine the encrypted call state from the next LDU2 message
                        mCachedLDUMessages.add(ldu1);
                    }
                }
                else if(message instanceof LDU2Message ldu2)
                {
                    if(ldu2.getEncryptionSyncParameters().isValid())
                    {
                        boolean encrypted = ldu2.getEncryptionSyncParameters().isEncryptedAudio();

                        // Fix B: Require multiple consecutive encrypted LDU2s before declaring encrypted
                        if(encrypted)
                        {
                            mConsecutiveEncryptedLDU2++;
                            if(mConsecutiveEncryptedLDU2 >= ENCRYPTION_CONFIRMATION_THRESHOLD)
                            {
                                mEncryptedCallStateEstablished = true;
                                mEncryptedCall = true;
                            }
                        }
                        else
                        {
                            mConsecutiveEncryptedLDU2 = 0;
                            mEncryptedCallStateEstablished = true;
                            mEncryptedCall = false;
                        }
                    }

                    if(mEncryptedCallStateEstablished)
                    {
                        for(LDUMessage cachedLdu : mCachedLDUMessages)
                        {
                            processAudio(cachedLdu);
                        }

                        mCachedLDUMessages.clear();
                        processAudio(ldu2);
                    }
                    else
                    {
                        // Fix C: Check cache size before adding (disabled when MAX is 0)
                        mCachedLDUMessages.add(ldu2);
                        if(MAX_CACHED_LDU_BEFORE_ASSUME_UNENCRYPTED > 0 &&
                           mCachedLDUMessages.size() >= MAX_CACHED_LDU_BEFORE_ASSUME_UNENCRYPTED)
                        {
                            mEncryptedCallStateEstablished = true;
                            mEncryptedCall = false;
                            for(LDUMessage cachedLdu : mCachedLDUMessages)
                            {
                                processAudio(cachedLdu);
                            }
                            mCachedLDUMessages.clear();
                        }
                    }
                }
            }
        }
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     * Applies frame validation and concealment when corrupted frames are detected.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(!mEncryptedCall)
        {
            // Suppress audio for DUID-corrected LDUs: these are TDUs rewritten as LDUs and contain noise, not voice.
            // Silence keeps the audio timing continuous.  Don't replay the last good frame: at the end of a
            // transmission that repeats the final 20 ms of voice after the speaker stopped, an audible blip.
            if(ldu.isDuidCorrected())
            {
                addSilence(ldu.getIMBEFrames().size());
                return;
            }

            // Unrecoverable voice: silence once the call's audio has started, nothing before that.  The codec is
            // reset before the next good frame so that it doesn't synthesize from parameters of the bad run.  Only
            // the received frames get silence: the zero padding of a truncated LDU took up no air time, because the
            // next frame's sync arrived in its place.
            if(!mVoiceQuality.accept(ldu))
            {
                mCodecResetPending = true;
                addSilence(getReceivedFrameCount(ldu));

                if(P25PipelineDiagnostics.isEnabled())
                {
                    P25PipelineDiagnostics.log("AUDIO", "AUDIO_MOD", "LDU_UNRECOVERABLE",
                        ldu.getDUID().name() + " errors=" + getErrorTotal(ldu) + " callAudioStarted=" + mVoiceQuality.isCallAudioStarted());
                }
                return;
            }

            if(mCodecResetPending)
            {
                getAudioCodec().reset();
                mCodecResetPending = false;
            }

            List<byte[]> frames = ldu.getIMBEFrames();

            //Only error correct the frames in sdrtrunk when the JMBE library (version 3+) can synthesize from them
            List<CorrectedIMBEFrame> correctedFrames = isCorrectedFrameSupported() ? ldu.getCorrectedIMBEFrames() : null;

            for(int x = 0; x < frames.size(); x++)
            {
                // Zero padding after the received bits of a truncated LDU is not voice, and took up no air time
                if(!ldu.isIMBEFrameReceived(x))
                {
                    continue;
                }

                byte[] frame = frames.get(x);
                mTotalFrameCount++;

                // Pre-codec quality gate: check IMBE FEC errors before codec decode
                if(mMaxImbeErrors > 0)
                {
                    IMBEFrameDiagnostic.FrameErrors fe = IMBEFrameDiagnostic.analyzeFrame(frame);
                    boolean exceedsThreshold = fe.totalErrors() > mMaxImbeErrors;

                    // Adaptive gate: track pass rate and auto-disable when most frames fail
                    updateAdaptiveGate(exceedsThreshold);

                    if(exceedsThreshold && !mAdaptiveGateDisabled)
                    {
                        mPreCodecFilteredCount++;
                        mConsecutiveGatedFrames++;

                        // Reset codec after sustained corruption to prevent state contamination
                        if(mConsecutiveGatedFrames == CODEC_RESET_THRESHOLD)
                        {
                            getAudioCodec().reset();
                        }

                        // Repeat last good frame with fade-out, or silence if none available
                        if(mLastGoodFrame != null && mConsecutiveGatedFrames <= GATE_FADE_START + GATE_FADE_LENGTH)
                        {
                            float[] repeated = mLastGoodFrame.clone();
                            if(mConsecutiveGatedFrames > GATE_FADE_START)
                            {
                                float fade = 1.0f - (float)(mConsecutiveGatedFrames - GATE_FADE_START) / GATE_FADE_LENGTH;
                                for(int i = 0; i < repeated.length; i++)
                                {
                                    repeated[i] *= fade;
                                }
                            }
                            addAudio(repeated);
                        }
                        else
                        {
                            addAudio(new float[FRAME_SAMPLE_COUNT]);
                        }
                        continue;
                    }
                }

                // Good frame passed the gate (or gate adaptively disabled) — reset consecutive counter
                mConsecutiveGatedFrames = 0;

                float[] audio = getAudio(frame, correctedFrames != null ? correctedFrames.get(x) : null);
                mLastGoodFrame = audio.clone();
                addAudio(audio);
            }
        }
        else
        {
            //Encrypted audio processing not implemented
        }
    }

    /**
     * Adds silence frames to keep the call's audio timing continuous, but only once the call has produced decoded
     * audio.  Before that there is nothing to keep in time with, and adding silence would open an audio segment that
     * holds nothing but silence.
     */
    private void addSilence(int frameCount)
    {
        for(int x = 0; x < frameCount; x++)
        {
            mTotalFrameCount++;
            mConsecutiveGatedFrames++;

            if(mVoiceQuality.isCallAudioStarted())
            {
                addAudio(new float[FRAME_SAMPLE_COUNT]);
            }
        }
    }

    /**
     * Number of the LDU's IMBE voice frames that were fully received: 9, unless the LDU was truncated.
     */
    public static int getReceivedFrameCount(LDUMessage ldu)
    {
        int frameCount = ldu.getIMBEFrames().size();
        int received = 0;

        for(int x = 0; x < frameCount; x++)
        {
            if(ldu.isIMBEFrameReceived(x))
            {
                received++;
            }
        }

        return received;
    }

    /**
     * Sum of the corrected-frame error counts across the LDU's received IMBE voice frames, scaled up to a full LDU of
     * 9 frames when the LDU was truncated, so that it compares with UNRECOVERABLE_LDU_ERROR_TOTAL.  An LDU with no
     * received frames returns Integer.MAX_VALUE.
     */
    public static int getErrorTotal(LDUMessage ldu)
    {
        List<CorrectedIMBEFrame> frames = ldu.getCorrectedIMBEFrames();
        int total = 0;
        int received = 0;

        for(int x = 0; x < frames.size(); x++)
        {
            if(ldu.isIMBEFrameReceived(x))
            {
                total += frames.get(x).getErrorCountTotal();
                received++;
            }
        }

        if(received == 0)
        {
            return Integer.MAX_VALUE;
        }

        return total * frames.size() / received;
    }

    /**
     * Indicates if the LDU carries no recoverable voice: its received voice frames are too corrupted, or it was
     * truncated before any complete voice frame was received.  See UNRECOVERABLE_LDU_ERROR_TOTAL.
     */
    public static boolean isUnrecoverable(LDUMessage ldu)
    {
        int errorTotal = getErrorTotal(ldu);

        //Nothing received: padding is never voice, even with the gate disabled
        if(errorTotal == Integer.MAX_VALUE)
        {
            return true;
        }

        if(UNRECOVERABLE_LDU_ERROR_TOTAL <= 0 || errorTotal < UNRECOVERABLE_LDU_ERROR_TOTAL)
        {
            return false;
        }

        //A fading signal can leave a few clean frames among the corrupted ones.  Those carry voice the codec can use,
        //so the LDU is only unrecoverable when it has fewer than MIN_CLEAN_FRAMES of them.
        List<CorrectedIMBEFrame> frames = ldu.getCorrectedIMBEFrames();
        int clean = 0;

        for(int x = 0; x < frames.size(); x++)
        {
            if(ldu.isIMBEFrameReceived(x) && frames.get(x).getErrorCountTotal() <= CLEAN_FRAME_MAX_ERRORS)
            {
                clean++;
            }
        }

        return clean < MIN_CLEAN_FRAMES;
    }

    /**
     * Updates the adaptive gate state based on whether the current frame exceeds the threshold.
     * When most frames fail the gate (non-bimodal error distribution), the gate auto-disables
     * to let the codec attempt partial decode instead of producing silence/repetition.
     */
    private void updateAdaptiveGate(boolean exceedsThreshold)
    {
        mAdaptiveWindowIndex++;
        if(!exceedsThreshold)
        {
            mAdaptivePassCount++;
        }

        if(mAdaptiveWindowIndex >= ADAPTIVE_WINDOW)
        {
            float passRate = (float)mAdaptivePassCount / mAdaptiveWindowIndex;

            if(mAdaptiveGateDisabled)
            {
                // Re-enable if conditions improved
                if(passRate >= ADAPTIVE_ENABLE_RATE)
                {
                    mAdaptiveGateDisabled = false;
                }
            }
            else
            {
                // Disable if too few frames pass
                if(passRate < ADAPTIVE_DISABLE_RATE)
                {
                    mAdaptiveGateDisabled = true;
                }
            }

            // Reset window
            mAdaptiveWindowIndex = 0;
            mAdaptivePassCount = 0;
        }
    }

    /**
     * Resets frame validation state for a new call.
     */
    private void resetFrameValidation()
    {
        mLastGoodFrame = null;
        mPreCodecFilteredCount = 0;
        mConsecutiveGatedFrames = 0;
        mTotalFrameCount = 0;
        mAdaptiveWindowIndex = 0;
        mAdaptivePassCount = 0;
        mAdaptiveGateDisabled = false;
        mVoiceQuality.reset();
        mCodecResetPending = false;
    }

    /**
     * Wrapper for squelch state to process end of call actions.  At call end the encrypted call state established
     * flag is reset so that the encrypted audio state for the next call can be properly detected and we send an
     * END audio packet so that downstream processors like the audio recorder can properly close out a call sequence.
     *
     * Note: When ignoreEncryptionState is enabled, the encryption state flags remain set to unencrypted.
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            if(P25PipelineDiagnostics.isEnabled())
            {
                P25PipelineDiagnostics.log("AUDIO", "SQUELCH_RECV", event.getSquelchState().name(),
                    "encryptEstablished=" + mEncryptedCallStateEstablished + " cached=" + mCachedLDUMessages.size());
            }
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                closeAudioSegment();
                // Only reset encryption state if we're not ignoring it
                if(!mIgnoreEncryptionState)
                {
                    mEncryptedCallStateEstablished = false;
                    mEncryptedCall = false;
                }
                mConsecutiveEncryptedLDU2 = 0;
                mCachedLDUMessages.clear();
                resetFrameValidation();
            }
        }
    }
}
