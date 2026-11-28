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

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.audio.SoftIMBEFrameDecoder;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import jmbe.binary.BinaryFrame;
import jmbe.frame.CorrectedIMBEFrame;

/**
 * P25 Phase 1 C4FM decoder with weak-signal voice diversity.
 *
 * The primary decode is the standard P25P1DecoderC4FM, unchanged: every message it produces is passed on, in order,
 * and nothing it decodes can be lost.  Alongside it, secondary decoders run on the same samples with narrower channel
 * filters and small frequency offsets.  A narrower channel filter passes less noise into the FM discriminator, which
 * recovers weak signals that the primary's filter can't (on a noise-injection testbed, a 3.5 kHz filter took the
 * voice codewords decoded correctly at 5 dB in-band SNR from 37% to 77%) but can cost decodes on strong signals, which
 * is why it is a secondary track and not the primary.
 *
 * Each primary LDU is held briefly so the secondary tracks' decodes of the same LDU can arrive.  For each voice frame
 * the primary decoded with errors in its Golay-protected codewords, the candidates (each track's hard decision frame
 * and its soft decision frame, see SoftIMBEFrameDecoder) are grouped by the frame's Golay-protected content and the
 * best-scoring group replaces the primary's frame (see vote()): it must have needed fewer corrections than the
 * primary, and ties keep the primary.  Error-free primary frames are never touched, so strong signals are unaffected.
 *
 * Activity gating: the secondary tracks only run while the primary is decoding.  Every LDU that diversity can improve
 * is one the primary produced, so the tracks are woken by the primary's first message and sleep again once the primary
 * has been quiet for {@link #HANGOVER_SECONDS}.  While asleep, the most recent {@link #PRE_ROLL_SECONDS} of samples are
 * kept, and on waking the tracks decode that pre-roll first (it covers the LDU that woke them, its frame sync and the
 * lead-in before it), then continue live.  The catch-up runs before the primary's held LDU is released, so the vote
 * sees the same secondary decodes as when the tracks never sleep; idle channels cost only the primary decoder.
 * -Dp25.diversity.gating=false keeps the tracks running all the time (evaluation).
 */
public class P25P1DecoderC4FMDiversity extends P25P1DecoderC4FM
{
    private static final double HOLD_SECONDS = 0.15;
    private static final double MATCH_SECONDS = 0.10;
    static final double PRE_ROLL_SECONDS = Double.parseDouble(System.getProperty("p25.diversity.preroll", "0.6"));
    static final double HANGOVER_SECONDS = Double.parseDouble(System.getProperty("p25.diversity.hangover", "1.0"));
    private static final boolean GATING_DEFAULT = !"false".equals(System.getProperty("p25.diversity.gating"));
    private static final int GOLAY_PROTECTED_BITS = 92;
    private static final int CHASE_BITS = 5;
    private static final int[] FRAME_OFFSETS = {LDUMessage.IMBE_FRAME_1, LDUMessage.IMBE_FRAME_2,
        LDUMessage.IMBE_FRAME_3, LDUMessage.IMBE_FRAME_4, LDUMessage.IMBE_FRAME_5, LDUMessage.IMBE_FRAME_6,
        LDUMessage.IMBE_FRAME_7, LDUMessage.IMBE_FRAME_8, LDUMessage.IMBE_FRAME_9};

    /**
     * Secondary track definition: the demodulator (the standard C4FM decoder with a channel filter of the given pass
     * and stop band edges, or the C4FM_V2 decoder with its own filters) and a frequency shift.
     */
    public record TrackConfig(boolean v2, double passBand, double stopBand, double shiftHz, int equalizerTaps,
                              float equalizerMu)
    {
        static TrackConfig c4fm(double passBand, double stopBand, double shiftHz)
        {
            return new TrackConfig(false, passBand, stopBand, shiftHz, 0, 0);
        }

        static TrackConfig v2(double shiftHz)
        {
            return new TrackConfig(true, 0, 0, shiftHz, 0, 0);
        }

        /**
         * Standard C4FM decoder with a multipath (constant modulus) equalizer ahead of its FM discriminator
         */
        static TrackConfig equalized(double passBand, double stopBand, int taps, float mu)
        {
            return new TrackConfig(false, passBand, stopBand, 0, taps, mu);
        }
    }

    /**
     * Fourteen secondary tracks.  The last two put a constant modulus equalizer ahead of the FM discriminator, which
     * recovers frames from multi-transmitter (simulcast) channels that no filter or offset track does: on 50 Plaistow PD
     * calls they raised the frames some track had error-free from 70.9% to 73.0% (strong calls) and 36.9% to 41.7%
     * (weak calls).  The first twelve:  Tracks that differ only in channel filter make correlated mistakes; adding narrower
     * filters, wider frequency offsets and a different demodulator (C4FM_V2) made the candidates more independent.  On
     * the testbed at 4 dB this took the frames where some track was right from 77-83% to 89-93%, and the frames
     * decoded correctly from 55-57% to 72-77%.
     */
    public static final List<TrackConfig> DEFAULT_TRACKS = List.of(
        TrackConfig.c4fm(3500, 4500, 0),
        TrackConfig.c4fm(4000, 5000, 0),
        TrackConfig.c4fm(4500, 5500, 0),
        TrackConfig.c4fm(3500, 4500, 150),
        TrackConfig.c4fm(3500, 4500, -150),
        TrackConfig.c4fm(3000, 4000, 0),
        TrackConfig.c4fm(3200, 4200, 0),
        TrackConfig.c4fm(3500, 4500, 300),
        TrackConfig.c4fm(3500, 4500, -300),
        TrackConfig.v2(0),
        TrackConfig.v2(150),
        TrackConfig.v2(-150),
        TrackConfig.equalized(5200, 6500, 9, 0.002f),
        TrackConfig.equalized(5200, 6500, 15, 0.005f));

    private record Decoded(long position, LDUMessage ldu) {}
    private record Held(long releaseAt, long position, IMessage message) {}

    /**
     * A secondary decode track: a decoder, the LDUs it produced recently, and its frequency shift oscillator.
     */
    private class Track
    {
        private final io.github.dsheirer.module.decode.FeedbackDecoder mDecoder;
        private final P25P1MessageFramer mFramer;
        private final java.util.function.Consumer<ComplexSamples> mReceive;
        private final java.util.function.DoubleConsumer mSetSampleRate;
        private final java.util.function.IntConsumer mSetNac;
        private final double mShiftHz;
        private double mPhase = 0;
        private final List<Decoded> mDecoded = new ArrayList<>();

        Track(TrackConfig config)
        {
            if(config.v2())
            {
                P25P1DecoderC4FMv2 decoder = new P25P1DecoderC4FMv2();
                mDecoder = decoder;
                mFramer = decoder.getMessageFramer();
                mReceive = decoder::receive;
                mSetSampleRate = decoder::setSampleRate;
                mSetNac = decoder::setConfiguredNAC;
            }
            else
            {
                P25P1DecoderC4FM decoder = new P25P1DecoderC4FM(config.passBand(), config.stopBand());
                if(config.equalizerTaps() > 0)
                {
                    decoder.setEqualizer(new io.github.dsheirer.dsp.filter.equalizer.FMConstantModulusEqualizer(
                        config.equalizerTaps(), config.equalizerMu(), 1e-5f));
                }
                mDecoder = decoder;
                mFramer = decoder.getMessageFramer();
                mReceive = decoder::receive;
                mSetSampleRate = decoder::setSampleRate;
                mSetNac = decoder::setConfiguredNAC;
            }
            mShiftHz = config.shiftHz();
            mDecoder.setMessageListener(message -> {
                if(message instanceof LDUMessage ldu && !ldu.isDuidCorrected() && !ldu.isTruncated())
                {
                    mDecoded.add(new Decoded(mTrackPosition, ldu));
                }
            });
        }

        void receive(ComplexSamples samples)
        {
            if(mShiftHz == 0)
            {
                mReceive.accept(samples);
                return;
            }

            float[] i = samples.i();
            float[] q = samples.q();
            int length = samples.length();
            float[] si = new float[length];
            float[] sq = new float[length];
            double increment = 2 * Math.PI * mShiftHz / mSampleRate;

            for(int x = 0; x < length; x++)
            {
                float c = (float)Math.cos(mPhase);
                float s = (float)Math.sin(mPhase);
                si[x] = i[x] * c - q[x] * s;
                sq[x] = i[x] * s + q[x] * c;
                mPhase += increment;
            }

            mPhase %= 2 * Math.PI;
            mReceive.accept(new ComplexSamples(si, sq, samples.timestamp()));
        }

        LDUMessage match(LDUMessage primary, long position)
        {
            Decoded best = null;

            for(Decoded decoded: mDecoded)
            {
                if(decoded.ldu().getDUID() == primary.getDUID() &&
                    Math.abs(decoded.position() - position) <= mMatchSamples &&
                    (best == null || Math.abs(decoded.position() - position) < Math.abs(best.position() - position)))
                {
                    best = decoded;
                }
            }

            return best != null ? best.ldu() : null;
        }

        void prune(long before)
        {
            mDecoded.removeIf(decoded -> decoded.position() < before);
        }
    }

    private final List<Track> mTracks = new ArrayList<>();
    private final Deque<Held> mHeld = new ArrayDeque<>();
    private final MergingListener mMergingListener = new MergingListener();
    private long mSamplePosition = 0;
    private double mSampleRate = 50000;
    private long mHoldSamples = (long)(HOLD_SECONDS * 50000);
    private long mMatchSamples = (long)(MATCH_SECONDS * 50000);
    private long mFramesReplaced = 0;
    private long mFramesAssessed = 0;
    //Sample position of the buffer the secondary tracks are processing (live, or a pre-roll buffer on waking)
    private long mTrackPosition = 0;
    private boolean mGating = GATING_DEFAULT;
    private boolean mTracksActive = !GATING_DEFAULT;
    private boolean mWakeRequested = false;
    private long mLastPrimaryMessagePosition = Long.MIN_VALUE;
    private long mActiveSamples = 0;
    private long mTotalSamples = 0;
    private record PreRoll(long position, ComplexSamples samples) {}
    private final Deque<PreRoll> mPreRoll = new ArrayDeque<>();
    private long mPreRollSamples = 0;

    public P25P1DecoderC4FMDiversity()
    {
        this(DEFAULT_TRACKS);
    }

    public P25P1DecoderC4FMDiversity(List<TrackConfig> tracks)
    {
        super();

        for(TrackConfig config: tracks)
        {
            mTracks.add(new Track(config));
        }
    }

    /**
     * Primary messages go to the merging listener, which holds LDUs for the secondary tracks and then forwards
     * everything in order to the registered listener.
     */
    @Override
    protected Listener<IMessage> getMessageListener()
    {
        //Called from the super constructor, before this class's fields are initialized
        return mMergingListener != null ? mMergingListener : super.getMessageListener();
    }

    @Override
    public void setSampleRate(double sampleRate)
    {
        super.setSampleRate(sampleRate);
        mSampleRate = sampleRate;
        mHoldSamples = (long)(HOLD_SECONDS * sampleRate);
        mMatchSamples = (long)(MATCH_SECONDS * sampleRate);

        for(Track track: mTracks)
        {
            track.mSetSampleRate.accept(sampleRate);
        }
    }

    @Override
    public void setConfiguredNAC(int nac)
    {
        super.setConfiguredNAC(nac);

        for(Track track: mTracks)
        {
            track.mSetNac.accept(nac);
        }
    }

    /**
     * Sets the maximum NID BCH errors on the primary and secondary message framers.
     */
    public void setMaxBchErrors(int maxBchErrors)
    {
        getMessageFramer().setMaxBchErrors(maxBchErrors);

        for(Track track: mTracks)
        {
            track.mFramer.setMaxBchErrors(maxBchErrors);
        }
    }

    @Override
    public void start()
    {
        super.start();

        for(Track track: mTracks)
        {
            track.mDecoder.start();
        }
    }

    @Override
    public void stop()
    {
        release(Long.MAX_VALUE);

        for(Track track: mTracks)
        {
            track.mDecoder.stop();
        }

        super.stop();
    }

    @Override
    public void receive(ComplexSamples samples)
    {
        super.receive(samples);
        mTotalSamples += samples.length();

        if(mTracksActive)
        {
            feedTracks(mSamplePosition, samples);

            if(mGating && mSamplePosition - mLastPrimaryMessagePosition > (long)(HANGOVER_SECONDS * mSampleRate))
            {
                mTracksActive = false;
            }
        }
        else
        {
            //Upstream may reuse sample arrays, so the pre-roll keeps copies
            mPreRoll.addLast(new PreRoll(mSamplePosition, new ComplexSamples(samples.i().clone(), samples.q().clone(),
                samples.timestamp())));
            mPreRollSamples += samples.length();

            while(mPreRollSamples - mPreRoll.peekFirst().samples().length() >= (long)(PRE_ROLL_SECONDS * mSampleRate))
            {
                mPreRollSamples -= mPreRoll.pollFirst().samples().length();
            }

            if(mWakeRequested)
            {
                //Catch up on the pre-roll (which includes this buffer), then run live
                mTracksActive = true;

                while(!mPreRoll.isEmpty())
                {
                    PreRoll preRoll = mPreRoll.pollFirst();
                    feedTracks(preRoll.position(), preRoll.samples());
                }

                mPreRollSamples = 0;
            }
        }

        mWakeRequested = false;
        mSamplePosition += samples.length();
        release(mSamplePosition);

        long prune = mSamplePosition - 4 * mHoldSamples;
        for(Track track: mTracks)
        {
            track.prune(prune);
        }
    }

    /**
     * Turns activity gating of the secondary tracks on (default) or off; call before the first samples.
     */
    void setGating(boolean gating)
    {
        mGating = gating;
        mTracksActive = !gating;
    }

    private void feedTracks(long position, ComplexSamples samples)
    {
        mTrackPosition = position;
        mActiveSamples += samples.length();

        for(Track track: mTracks)
        {
            track.receive(samples);
        }
    }

    /**
     * Fraction of the received samples that the secondary tracks processed (1.0 when not gated).  The pre-roll
     * decoded on waking counts as processed.
     */
    public double getActiveFraction()
    {
        return mTotalSamples == 0 ? 0 : Math.min(1.0, (double)mActiveSamples / mTotalSamples);
    }

    /**
     * Number of voice frames replaced by a better secondary decode, and assessed (primary frames with errors).
     */
    public long getFramesReplaced()
    {
        return mFramesReplaced;
    }

    public long getFramesAssessed()
    {
        return mFramesAssessed;
    }

    /**
     * Releases held messages, in order, up to the first one whose hold hasn't expired.
     */
    private void release(long position)
    {
        Listener<IMessage> listener = super.getMessageListener();

        while(!mHeld.isEmpty() && mHeld.peekFirst().releaseAt() <= position)
        {
            Held held = mHeld.pollFirst();

            if(held.message() instanceof LDUMessage ldu)
            {
                merge(ldu, held.position());
            }

            if(listener != null)
            {
                listener.receive(held.message());
            }
        }
    }

    private void merge(LDUMessage primary, long position)
    {
        if(primary.isDuidCorrected() || primary.isTruncated())
        {
            return;
        }

        List<LDUMessage> secondaries = new ArrayList<>();
        for(Track track: mTracks)
        {
            LDUMessage match = track.match(primary, position);
            if(match != null)
            {
                secondaries.add(match);
            }
        }

        for(int frame = 0; frame < FRAME_OFFSETS.length; frame++)
        {
            CorrectedIMBEFrame primaryFrame = primary.getCorrectedIMBEFrames().get(frame);

            if(golayErrors(primaryFrame) == 0)
            {
                continue;
            }

            mFramesAssessed++;
            List<Candidate> candidates = new ArrayList<>();
            addCandidates(candidates, primary, frame, true, 0);
            for(int track = 0; track < secondaries.size(); track++)
            {
                addCandidates(candidates, secondaries.get(track), frame, false, track + 1);
            }

            byte[] winner = vote(candidates);

            if(winner != null)
            {
                primary.replaceIMBEFrame(frame, winner);
                mFramesReplaced++;
            }
        }
    }

    /**
     * Voting candidate: raw frame bytes and the Golay-protected content they decode to.
     */
    public record Candidate(byte[] raw, BitSet golayContent, int errors, boolean primaryHard, int track) {}

    private static void addCandidates(List<Candidate> candidates, LDUMessage ldu, int frame, boolean primary,
                                      int track)
    {
        byte[] raw = ldu.getIMBEFrames().get(frame);
        CorrectedIMBEFrame hard = ldu.getCorrectedIMBEFrames().get(frame);
        candidates.add(new Candidate(raw, golayContent(hard.getFrame()), golayErrors(hard), primary, track));

        if(ldu.getSoftSymbols() != null)
        {
            SoftIMBEFrameDecoder.Result soft = SoftIMBEFrameDecoder.decode(raw,
                SoftIMBEFrameDecoder.getBitConfidence(ldu.getSoftSymbols(), ldu.getReliabilities(),
                    FRAME_OFFSETS[frame]), CHASE_BITS);
            CorrectedIMBEFrame softFrame = CorrectedIMBEFrame.correct(soft.frame());
            //The soft frame re-corrects to zero errors by construction, so rank it behind the hard frames it came from
            candidates.add(new Candidate(soft.frame(), golayContent(softFrame.getFrame()),
                golayErrors(hard) + 1, false, track));
        }
    }

    /**
     * Chooses among the candidates by their Golay-protected content.  Each group of identical candidates scores its
     * support (the number of distinct tracks in it, so a track's agreeing hard and soft decodes count once) minus a
     * tenth of the fewest corrections any member needed.  A group other than the primary's can only win if it needed
     * fewer corrections than the primary, and only by scoring strictly higher, so ties keep the primary.  On the
     * testbed this decoded 1-4 points more frames correctly than requiring two agreeing tracks, without losing more
     * frames the primary had right.
     * @return the raw frame to use in place of the primary's, or null to keep the primary's.
     */
    public static byte[] vote(List<Candidate> candidates)
    {
        List<List<Candidate>> groups = new ArrayList<>();

        for(Candidate candidate: candidates)
        {
            List<Candidate> group = null;
            for(List<Candidate> existing: groups)
            {
                if(existing.get(0).golayContent().equals(candidate.golayContent()))
                {
                    group = existing;
                    break;
                }
            }
            if(group == null)
            {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(candidate);
        }

        List<Candidate> primaryGroup = null;
        int primaryErrors = Integer.MAX_VALUE;
        for(List<Candidate> group: groups)
        {
            for(Candidate candidate: group)
            {
                if(candidate.primaryHard())
                {
                    primaryGroup = group;
                    primaryErrors = candidate.errors();
                }
            }
        }

        List<Candidate> best = primaryGroup;
        double bestScore = primaryGroup != null ? score(primaryGroup) : -Double.MAX_VALUE;

        for(List<Candidate> group: groups)
        {
            if(group == primaryGroup || minErrors(group) >= primaryErrors)
            {
                continue;
            }

            double score = score(group);
            if(score > bestScore)
            {
                bestScore = score;
                best = group;
            }
        }

        if(best == null || best == primaryGroup)
        {
            return null;
        }

        Candidate fewest = best.get(0);
        for(Candidate candidate: best)
        {
            if(candidate.errors() < fewest.errors())
            {
                fewest = candidate;
            }
        }

        return fewest.raw();
    }

    private static int minErrors(List<Candidate> group)
    {
        return group.stream().mapToInt(Candidate::errors).min().orElse(Integer.MAX_VALUE);
    }

    private static double score(List<Candidate> group)
    {
        return group.stream().mapToInt(Candidate::track).distinct().count() - 0.1 * minErrors(group);
    }

    private static BitSet golayContent(BinaryFrame frame)
    {
        return frame.get(0, GOLAY_PROTECTED_BITS);
    }

    private static int golayErrors(CorrectedIMBEFrame frame)
    {
        int[] errors = frame.getErrors();
        return errors[0] + errors[1] + errors[2] + errors[3];
    }

    /**
     * Holds primary messages so the secondary decodes of an LDU can arrive, forwarding in order.
     */
    private class MergingListener implements Listener<IMessage>
    {
        @Override
        public void receive(IMessage message)
        {
            //Any decoded P25 data unit wakes the secondary tracks (sync loss and dropped sample notices don't)
            if(message instanceof io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message)
            {
                mWakeRequested = true;
                mLastPrimaryMessagePosition = mSamplePosition;
            }

            //Hold every message behind a held LDU so order is kept; anything else with nothing held goes straight on
            boolean hold = message instanceof LDUMessage || !mHeld.isEmpty();

            if(hold)
            {
                long releaseAt = message instanceof LDUMessage ? mSamplePosition + mHoldSamples : 0;
                mHeld.addLast(new Held(releaseAt, mSamplePosition, message));
            }
            else
            {
                Listener<IMessage> listener = P25P1DecoderC4FMDiversity.super.getMessageListener();
                if(listener != null)
                {
                    listener.receive(message);
                }
            }
        }
    }
}
