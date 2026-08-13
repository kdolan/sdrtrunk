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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFactory;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloat;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessageWithLinkControl;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FullLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ShortLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APCO25 Phase 1 decoder.  Decimates incoming sample buffers to as close as possible to 25 kHz for ~5 samples per
 * symbol.  Employs baseband and pulse shaping filters.  Demodulates the complex baseband (I/Q) sample stream using
 * SIMD differential demodulation of the entire sample stream.  The C4FM demodulator (mSymbolProcessor) performs sync
 * detection, timing correction and PLL signal mistune correction.  Message framer performs message framing and message
 * creation.  A registered message listener receives the detected and framed messages.
 *
 * As a child of the FeedbackDecoder, this decoder provides periodic PLL measurements to the tuner for automatic PPM
 * correction.  It also provides a stream of demodulated soft symbols (in radians) for display to the user.
 */
public class P25P1DecoderC4FM extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
        ISourceEventListener, Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25P1DecoderC4FM.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");
    private static final int SYMBOL_RATE = 4800;
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();

    private static final float ENERGY_EMA_FACTOR = 0.001f;
    private static final float ENERGY_SILENCE_RATIO = 0.10f;

    private final P25P1DemodulatorC4FM mSymbolProcessor;
    private final P25P1MessageFramer mMessageFramer = new P25P1MessageFramer();
    private final P25P1MessageProcessor mMessageProcessor = new P25P1MessageProcessor();
    private DifferentialDemodulatorFloat mDemodulator;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilterI;
    private IRealFilter mPulseShapingFilterQ;

    // Transmission boundary detection — mirrors LSMv2 pattern
    private float mEnergyAverage = 0;
    private float mPeakEnergy = 0;
    private boolean mInSilence = true;
    private int mSilenceSampleCount = 0;
    private int mSilenceSamplesThreshold = 2500; // ~100ms at 25kHz decimated rate, updated in setSampleRate()

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public P25P1DecoderC4FM()
    {
        // Disable sync guard for C4FM — its 4-level FSK sync detection is highly reliable,
        // so blocked syncs are almost always genuine. Guard helps CQPSK (noisy symbol recovery)
        // but costs C4FM 0.8% LDUs and 12%+ words on busy channels like Salem Fire.
        mMessageFramer.mSyncGuardEnabled = false;
        // Limit consecutive DUID corrections for C4FM. Corrections recover genuine voice frames
        // where the NID BCH decoded TDU instead of LDU (~10% improvement on LFD). The limit
        // breaks the correction cycling that generates noise LDUs after call ends. DUID-corrected
        // messages are marked isDuidCorrected() and blocked from state/identifier processing in
        // P25P1DecoderState.processLDU() to prevent stuck calls and garbage identifiers.
        // System property p25.duid.limit.c4fm: 0 = disabled (unlimited), default = 3
        int duidLimit = Integer.parseInt(System.getProperty("p25.duid.limit.c4fm", "3"));
        if(duidLimit > 0)
        {
            mMessageFramer.setMaxConsecutiveDuidCorrections(duidLimit);
        }
        mMessageProcessor.setMessageListener(getMessageListener());
        mSymbolProcessor = new P25P1DemodulatorC4FM(mMessageFramer, this);
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 C4FM";
    }

    /**
     * Sets the sample rate and configures internal decoder components.
     * @param sampleRate of the channel to decode
     */
    public void setSampleRate(double sampleRate)
    {
        if(sampleRate <= SYMBOL_RATE * 2)
        {
            throw new IllegalArgumentException("Sample rate [" + sampleRate + "] must be >9600 (2 * " +
                    SYMBOL_RATE + " symbol rate)");
        }

        int decimation = 1;

        //Identify decimation that gets us as close to 4.0 Samples Per Symbol as possible (19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);

        float decimatedSampleRate = (float)sampleRate / decimation;

        //Set the decimated sample rate to use for PLL error reporting.
        setDecimatedSampleRate(decimatedSampleRate);

        int symbolLength = 16;
        float rrcAlpha = 0.2f;

        float[] taps = FilterFactory.getRootRaisedCosine(decimatedSampleRate / SYMBOL_RATE,
                symbolLength, rrcAlpha);
        mPulseShapingFilterI = new RealFIRFilter(taps);
        mPulseShapingFilterQ = new RealFIRFilter(taps);

        mBasebandFilterI = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mBasebandFilterQ = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mDemodulator = DifferentialDemodulatorFactory.getFloatDemodulator(decimatedSampleRate, SYMBOL_RATE);
        mSymbolProcessor.setSamplesPerSymbol(mDemodulator.getSamplesPerSymbol());
        mMessageFramer.setListener(mMessageProcessor);
        mMessageProcessor.setMessageListener(getMessageListener());

        // ~100ms of silence at the decimated sample rate triggers boundary detection
        mSilenceSamplesThreshold = (int)(decimatedSampleRate * 0.1f);
    }

    /**
     * Primary method for processing incoming complex sample buffers
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        mMessageFramer.setTimestamp(samples.timestamp());

        float[] i = mDecimationFilterI.decimateReal(samples.i());
        float[] q = mDecimationFilterQ.decimateReal(samples.q());

        // Detect transmission boundaries on raw decimated samples (before filtering distorts energy)
        detectTransmissionBoundary(i, q);

        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        i = mPulseShapingFilterI.filter(i);
        q = mPulseShapingFilterQ.filter(q);

        float[] demodulated = mDemodulator.demodulate(i, q);
        mSymbolProcessor.process(demodulated);
    }

    /**
     * Monitors energy on raw decimated I/Q samples to detect transmission boundaries.
     * When sustained silence is followed by signal return, triggers a cold-start reset
     * of the demodulator and framer to clear accumulated noise state.
     * Mirrors the proven pattern in P25P1DecoderLSMv2.detectTransmissionBoundary().
     */
    private void detectTransmissionBoundary(float[] i, float[] q)
    {
        for(int idx = 0; idx < i.length; idx++)
        {
            float energy = (i[idx] * i[idx]) + (q[idx] * q[idx]);
            mEnergyAverage += (energy - mEnergyAverage) * ENERGY_EMA_FACTOR;

            if(mEnergyAverage > mPeakEnergy)
            {
                mPeakEnergy = mEnergyAverage;
            }
            else
            {
                mPeakEnergy *= 0.99999f;
            }

            float silenceThreshold = mPeakEnergy * ENERGY_SILENCE_RATIO;

            if(mPeakEnergy > 0 && mEnergyAverage < silenceThreshold)
            {
                mSilenceSampleCount++;
                if(mSilenceSampleCount >= mSilenceSamplesThreshold && !mInSilence)
                {
                    mInSilence = true;
                    if(P25PipelineDiagnostics.isEnabled())
                    {
                        P25PipelineDiagnostics.log("C4FM", "BOUNDARY", "ENTER_SILENCE",
                            String.format("energy=%.2e peak=%.2e", mEnergyAverage, mPeakEnergy));
                    }
                }
            }
            else
            {
                if(mInSilence && mPeakEnergy > 0)
                {
                    if(P25PipelineDiagnostics.isEnabled())
                    {
                        P25PipelineDiagnostics.log("C4FM", "BOUNDARY", "SILENCE_TO_SIGNAL",
                            String.format("energy=%.2e peak=%.2e threshold=%.2e silenceSamples=%d",
                                mEnergyAverage, mPeakEnergy, mPeakEnergy * ENERGY_SILENCE_RATIO, mSilenceSampleCount));
                    }
                    mSymbolProcessor.coldStartReset();
                    mMessageFramer.coldStartReset();
                    mMessageFramer.setBoundaryRecoveryActive(true);
                    mMessageFramer.setInitialAcquisitionActive(true);
                    mInSilence = false;
                }
                mSilenceSampleCount = 0;
            }
        }
    }

    /**
     * Sets the configured NAC for improved NID error correction.
     * @param nac the configured NAC value (0-4095), or 0 to use automatic tracking
     */
    public void setConfiguredNAC(int nac)
    {
        mMessageFramer.setConfiguredNAC(nac);
    }

    /**
     * Accessor for the message framer's diagnostic counters.
     */
    public P25P1MessageFramer getMessageFramer() { return mMessageFramer; }

    /**
     * Accessor for the demodulator's diagnostic counters.
     */
    public P25P1DemodulatorC4FM getDemodulator() { return mSymbolProcessor; }

    /**
     * Constructs a baseband filter for this decoder using the current sample rate
     */
    private float[] getBasebandFilter(double sampleRate)
    {
        if(BASEBAND_FILTERS.containsKey(sampleRate))
        {
            return BASEBAND_FILTERS.get(sampleRate);
        }

        FIRFilterSpecification specification = FIRFilterSpecification
                .lowPassBuilder()
                .sampleRate(sampleRate)
                .passBandCutoff(5200)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(6500) //6500
                .stopBandRipple(0.01).build();

        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
        }
        catch(Exception fde) //FilterDesignException
        {
            System.out.println("Error");
        }

        if(coefficients == null)
        {
            throw new IllegalStateException("Unable to design low pass filter for sample rate [" + sampleRate + "]");
        }

        return coefficients;
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(listener);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(null);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public boolean hasBufferListeners()
    {
        return mSymbolProcessor.hasBufferListener();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::process;
    }

    @Override
    public void start()
    {
        super.start();
        mMessageFramer.start();
    }

    @Override
    public void stop()
    {
        super.stop();
        mMessageFramer.stop();
    }

    /**
     * Process source events
     */
    private void process(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mSymbolProcessor.resetPLL();
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                setSampleRate(sourceEvent.getValue().doubleValue());
                break;
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    public static void main(String[] args)
    {
        LOGGER.info("Starting ...");

//        String directory = "D:\\DQPSK Equalizer Research - P25\\"; //Windows
        String directory = "/media/denny/T9/DQPSK Equalizer Research - P25/"; //Linux

        String file = directory + "P25-S1-Conventional-repeater-20241115_212221_469325000_QPS_Digital_Kynoch_Kynoch_Digital_59_baseband.wav";
//        String file = directory + "P25-S3-C4FM-20241225_040459_152517500_NYSEG_Onondaga_Control_30_baseband.wav";

        boolean autoReplay = false;

        P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
        decoder.start();

        UserPreferences userPreferences = new UserPreferences();
        P25P1AudioModule audio1 = new P25P1AudioModule(userPreferences, new AliasList(""));

        decoder.setMessageListener(new Listener<>()
        {
            private long mBitCounter = 1;
            private int mBitErrorCounter;
            private int mValidMessageCounter;
            private int mTotalMessageCounter;

            @Override
            public void receive(IMessage iMessage)
            {
                int errors = 0;

                audio1.receive(iMessage);

                if(iMessage instanceof P25P1Message message)
                {
                    mBitCounter += 288;

                    if(message.getMessage() != null)
                    {
                        errors = message.getMessage().getCorrectedBitCount();
                    }

                    mTotalMessageCounter++;

                    if(mTotalMessageCounter == 492)
                    {
                        int a = 0;
                    }
                    if(message.isValid())
                    {
                        mBitErrorCounter += Math.max(errors, 0);
                        mValidMessageCounter++;
                    }
                }
                else if(iMessage instanceof LCMessage lcw)
                {
                    mTotalMessageCounter++;
                    errors = lcw.getMessage().getCorrectedBitCount();
                    if(lcw.isValid())
                    {
                        mBitErrorCounter += errors;
                        mValidMessageCounter++;
                    }
                }

                double bitErrorRate = (double)mBitErrorCounter / (double)mBitCounter * 100.0;

                boolean logEverything = true;
                boolean logFLC = true;
                boolean logSLC = true;
                boolean logCACH = true;
                boolean logIdles = true;

                if(logEverything)
                {
                    if(iMessage.toString().contains("PLACEHOLDER"))
                    {
                        int a = 0;
                    }
                    System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                }
                else
                {
                    if (logFLC)
                    {
                        if(iMessage instanceof FullLCMessage)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof Terminator terminator)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof DataMessageWithLinkControl)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                    }

                    if(logCACH && iMessage instanceof DMRBurst burst && burst.hasCACH())
                    {
                        System.out.println("CACH:" + burst.getCACH());
                    }

                    if(logSLC && iMessage instanceof ShortLCMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }

                    if(logIdles && iMessage instanceof SyncLossMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }
                }
            }
        });

        try(ComplexWaveSource source = new ComplexWaveSource(new File(file), autoReplay))
        {
            source.setListener(iNativeBuffer -> {
                Iterator<ComplexSamples> it = iNativeBuffer.iterator();

                while(it.hasNext())
                {
                    decoder.receive(it.next());
                }
            });
            source.start();
            decoder.setSampleRate(source.getSampleRate());

            while(true)
            {
                source.next(2048, true);
            }
        }
        catch(IOException ioe)
        {
            LOGGER.error("Error", ioe);
        }

        LOGGER.info("Finished");
    }
}
