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
 * and its soft decision frame, see SoftIMBEFrameDecoder) vote on the frame's Golay-protected content.  A group's
 * support is the number of distinct tracks behind it.  The best-supported group wins, ties go to the primary, and the
 * frame is only replaced when at least two tracks agree on the winner, they outnumber the tracks agreeing with the
 * primary, and the winner needed fewer corrections than the primary.  Error-free primary frames are never touched, so
 * strong signals are unaffected.
 */
public class P25P1DecoderC4FMDiversity extends P25P1DecoderC4FM
{
    private static final double HOLD_SECONDS = 0.15;
    private static final double MATCH_SECONDS = 0.10;
    private static final int GOLAY_PROTECTED_BITS = 92;
    private static final int CHASE_BITS = 5;
    private static final int MIN_AGREEMENT = Integer.getInteger("p25.diversity.min.agreement", 2);
    //How many more candidates the winning group needs than the primary's own group
    private static final int MIN_LEAD = Integer.getInteger("p25.diversity.min.lead", 1);
    //The winner must also have needed fewer corrections than the primary.  On the known-truth testbed this removed 12
    //of 17 frames replaced wrongly (where the primary was right despite many corrections) for 3% of the frames gained.
    private static final boolean REQUIRE_FEWER_ERRORS =
        !"false".equals(System.getProperty("p25.diversity.fewer.errors"));
    private static final int[] FRAME_OFFSETS = {LDUMessage.IMBE_FRAME_1, LDUMessage.IMBE_FRAME_2,
        LDUMessage.IMBE_FRAME_3, LDUMessage.IMBE_FRAME_4, LDUMessage.IMBE_FRAME_5, LDUMessage.IMBE_FRAME_6,
        LDUMessage.IMBE_FRAME_7, LDUMessage.IMBE_FRAME_8, LDUMessage.IMBE_FRAME_9};

    /**
     * Secondary track definition: channel filter pass and stop band edges and a frequency shift.
     */
    public record TrackConfig(double passBand, double stopBand, double shiftHz) {}

    public static final List<TrackConfig> DEFAULT_TRACKS = List.of(
        new TrackConfig(3500, 4500, 0),
        new TrackConfig(4000, 5000, 0),
        new TrackConfig(4500, 5500, 0),
        new TrackConfig(3500, 4500, 150),
        new TrackConfig(3500, 4500, -150));

    private record Decoded(long position, LDUMessage ldu) {}
    private record Held(long releaseAt, long position, IMessage message) {}

    /**
     * A secondary decode track: a decoder, the LDUs it produced recently, and its frequency shift oscillator.
     */
    private class Track
    {
        private final P25P1DecoderC4FM mDecoder;
        private final double mShiftHz;
        private double mPhase = 0;
        private final List<Decoded> mDecoded = new ArrayList<>();

        Track(TrackConfig config)
        {
            mDecoder = new P25P1DecoderC4FM(config.passBand(), config.stopBand());
            mShiftHz = config.shiftHz();
            mDecoder.setMessageListener(message -> {
                if(message instanceof LDUMessage ldu && !ldu.isDuidCorrected() && !ldu.isTruncated())
                {
                    mDecoded.add(new Decoded(mSamplePosition, ldu));
                }
            });
        }

        void receive(ComplexSamples samples)
        {
            if(mShiftHz == 0)
            {
                mDecoder.receive(samples);
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
            mDecoder.receive(new ComplexSamples(si, sq, samples.timestamp()));
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
            track.mDecoder.setSampleRate(sampleRate);
        }
    }

    @Override
    public void setConfiguredNAC(int nac)
    {
        super.setConfiguredNAC(nac);

        for(Track track: mTracks)
        {
            track.mDecoder.setConfiguredNAC(nac);
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
            track.mDecoder.getMessageFramer().setMaxBchErrors(maxBchErrors);
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

        for(Track track: mTracks)
        {
            track.receive(samples);
        }

        mSamplePosition += samples.length();
        release(mSamplePosition);

        long prune = mSamplePosition - 4 * mHoldSamples;
        for(Track track: mTracks)
        {
            track.prune(prune);
        }
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
        candidates.add(new Candidate(raw, golayContent(hard.getFrame()), hard.getErrorCountTotal(), primary, track));

        if(ldu.getSoftSymbols() != null)
        {
            SoftIMBEFrameDecoder.Result soft = SoftIMBEFrameDecoder.decode(raw,
                SoftIMBEFrameDecoder.getBitConfidence(ldu.getSoftSymbols(), FRAME_OFFSETS[frame]), CHASE_BITS);
            CorrectedIMBEFrame softFrame = CorrectedIMBEFrame.correct(soft.frame());
            //The soft frame re-corrects to zero errors by construction, so rank it behind the hard frames it came from
            candidates.add(new Candidate(soft.frame(), golayContent(softFrame.getFrame()),
                hard.getErrorCountTotal() + 1, false, track));
        }
    }

    /**
     * Votes on the candidates' Golay-protected content.  Returns the raw frame to use in place of the primary's, or
     * null to keep the primary's: when the primary hard frame is in (or tied for) the largest group, or the largest
     * group has fewer than MIN_AGREEMENT members.
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

        //A group's support is the number of distinct tracks in it: a track's hard and soft decodes agreeing with each
        //other are one track, not two votes
        java.util.function.ToIntFunction<List<Candidate>> support =
            g -> (int)g.stream().mapToInt(Candidate::track).distinct().count();
        int largest = groups.stream().mapToInt(support).max().orElse(0);
        int primaryGroup = groups.stream().filter(g -> g.stream().anyMatch(Candidate::primaryHard))
            .mapToInt(support).findFirst().orElse(0);
        List<Candidate> winner = null;

        for(List<Candidate> group: groups)
        {
            if(support.applyAsInt(group) == largest)
            {
                if(group.stream().anyMatch(Candidate::primaryHard))
                {
                    return null; //the primary is (tied for) the most supported content
                }
                if(winner == null)
                {
                    winner = group;
                }
                else
                {
                    return null; //tie between two other groups: no clear winner
                }
            }
        }

        if(winner == null || largest < MIN_AGREEMENT || largest < primaryGroup + MIN_LEAD)
        {
            return null;
        }

        Candidate best = winner.get(0);
        for(Candidate candidate: winner)
        {
            if(candidate.errors() < best.errors())
            {
                best = candidate;
            }
        }

        if(REQUIRE_FEWER_ERRORS)
        {
            int primaryErrors = candidates.stream().filter(Candidate::primaryHard).mapToInt(Candidate::errors)
                .findFirst().orElse(Integer.MAX_VALUE);
            if(best.errors() >= primaryErrors)
            {
                return null;
            }
        }

        return best.raw();
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
