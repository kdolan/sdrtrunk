/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.mdc1200;

import io.github.dsheirer.bits.MessageFramer;
import io.github.dsheirer.bits.SyncPattern;
import io.github.dsheirer.dsp.NRZDecoder;
import io.github.dsheirer.dsp.afsk.AFSK1200Decoder;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.afsk.AbstractAFSKDecoder;
import io.github.dsheirer.sample.Listener;
import java.util.HashMap;
import java.util.Map;

/**
 * MDC1200 Decoder - 1200 baud 2FSK decoder.
 *
 * Uses dual-branch diversity demodulation: the same FM-demodulated audio is decoded in parallel by
 * two independent AFSK branches - branch A with the original real (phase-sensitive) correlator and
 * branch B with a phase-invariant quadrature correlator. The two branches lock timing and slice
 * bits differently, so they recover complementary sets of marginal bursts; any CRC-valid decode
 * from either branch is emitted (with short-window de-duplication so a burst caught by both is only
 * reported once). Because every emitted decode is CRC-validated, combining branches raises the
 * detection rate without adding false positives.
 */
public class MDCDecoder extends AbstractAFSKDecoder
{
    private static final int MESSAGE_LENGTH = 304;

    //Allowed bit errors in the 40-bit preamble for soft sync matching. Soft matching is safe
    //because MDC1200FEC runs convolutional forward-error-correction + CRC-16-CCITT over each
    //framed message; noise matches that happen to satisfy soft sync will fail the CRC and be
    //rejected by MDCDecoderState.receive(). Default 8 is ~20% BER tolerance on the preamble,
    //roughly matching what marginal real signals exhibit. Configurable via the MDC-1200 user
    //preference (Mdc1200Preference), or -Dmdc.sync.threshold=N for field experimentation.
    private static final int DEFAULT_SYNC_BIT_ERROR_THRESHOLD =
        Integer.getInteger("mdc.sync.threshold", 8);

    //De-duplication window (8 kHz samples, ~0.5s) that collapses the same burst decoded by both
    //branches. Real re-keys of the same unit are far longer than this, so distinct bursts survive.
    private static final long DEDUP_WINDOW_SAMPLES = 4000;

    private final int mSyncBitErrorThreshold;

    //Branch A (real correlator): the base AbstractAFSKDecoder chain.
    private NRZDecoder mNRZDecoder;
    private MessageFramer mMessageFramer;
    private MDCMessageProcessor mMessageProcessor;

    //Branch B (quadrature correlator): an independent chain over the same audio.
    private AFSK1200Decoder mQuadratureDecoder;
    private NRZDecoder mQuadratureNRZDecoder;
    private MessageFramer mQuadratureFramer;
    private MDCMessageProcessor mQuadratureProcessor;

    //Recently emitted (unit:opcode:bot) -> sample offset, for cross-branch de-duplication.
    //Bounded by the number of distinct unit/opcode combinations seen (tiny in practice).
    private final Map<String,Long> mRecentDecodes = new HashMap<>();
    private long mSampleCounter;

    public MDCDecoder()
    {
        this(DEFAULT_SYNC_BIT_ERROR_THRESHOLD);
    }

    public MDCDecoder(int syncBitErrorThreshold)
    {
        super(AFSK1200Decoder.Output.INVERTED);
        mSyncBitErrorThreshold = syncBitErrorThreshold;
        init();
    }

    protected MDCDecoder(AFSK1200Decoder decoder)
    {
        super(decoder);
        mSyncBitErrorThreshold = DEFAULT_SYNC_BIT_ERROR_THRESHOLD;
        init();
    }

    private void init()
    {
        //Branch A - real correlator (the base decoder from AbstractAFSKDecoder).
        mNRZDecoder = new NRZDecoder(NRZDecoder.MODE_INVERTED);
        getDecoder().setSymbolProcessor(mNRZDecoder);
        mMessageFramer = new MessageFramer(SyncPattern.MDC1200.getPattern(), MESSAGE_LENGTH,
            mSyncBitErrorThreshold);
        mNRZDecoder.setListener(mMessageFramer);
        mMessageProcessor = new MDCMessageProcessor();
        mMessageFramer.addMessageListener(mMessageProcessor);
        //Branch A forwards CRC-valid decodes (de-duplicated) and also its CRC-fail messages (for
        //diagnostics/logging); branch B forwards only its CRC-valid decodes.
        mMessageProcessor.addMessageListener(message -> forward(message, true));

        //Branch B - quadrature correlator, independent AFSK chain over the same audio.
        mQuadratureDecoder = new AFSK1200Decoder(AFSK1200Decoder.Output.INVERTED, true);
        mQuadratureNRZDecoder = new NRZDecoder(NRZDecoder.MODE_INVERTED);
        mQuadratureDecoder.setSymbolProcessor(mQuadratureNRZDecoder);
        mQuadratureFramer = new MessageFramer(SyncPattern.MDC1200.getPattern(), MESSAGE_LENGTH,
            mSyncBitErrorThreshold);
        mQuadratureNRZDecoder.setListener(mQuadratureFramer);
        mQuadratureProcessor = new MDCMessageProcessor();
        mQuadratureFramer.addMessageListener(mQuadratureProcessor);
        mQuadratureProcessor.addMessageListener(message -> forward(message, false));
    }

    @Override
    public void receive(float[] realBuffer)
    {
        mSampleCounter += realBuffer.length;
        super.receive(realBuffer);              //branch A
        mQuadratureDecoder.receive(realBuffer); //branch B
    }

    /**
     * Diversity collector. Forwards a CRC-valid MDC decode from either branch, suppressing an
     * identical decode seen within the de-duplication window (the same burst caught by both
     * branches). CRC-fail messages are forwarded only from branch A to preserve existing
     * diagnostic behavior without doubling.
     *
     * @param message decoded message
     * @param forwardInvalid true for branch A (forward CRC-fail messages too), false for branch B
     */
    private void forward(IMessage message, boolean forwardInvalid)
    {
        if(message instanceof MDCMessage mdc)
        {
            if(mdc.isValid())
            {
                String key = mdc.getFromIdentifier().getValue() + ":" + mdc.getOpcode() + ":" + mdc.isBOT();
                Long last = mRecentDecodes.get(key);

                if(last != null && (mSampleCounter - last) < DEDUP_WINDOW_SAMPLES)
                {
                    return; //duplicate of a burst already emitted by the other branch
                }

                mRecentDecodes.put(key, mSampleCounter);
            }
            else if(!forwardInvalid)
            {
                return;
            }
        }

        Listener<IMessage> listener = getMessageListener();

        if(listener != null)
        {
            listener.receive(message);
        }
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.MDC1200;
    }

    public MessageFramer getMessageFramer()
    {
        return mMessageFramer;
    }

    /**
     * Package-visible for test instrumentation: allows a replay test to install a diagnostic tap
     * on the decoded bit stream before it reaches the framer.
     */
    NRZDecoder getNRZDecoder()
    {
        return mNRZDecoder;
    }
}
