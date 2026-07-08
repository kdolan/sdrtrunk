/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.dsp.afsk;

import io.github.dsheirer.bits.IBinarySymbolProcessor;
import io.github.dsheirer.buffer.FloatAveragingBuffer;
import io.github.dsheirer.dsp.filter.resample.RealResampler;
import io.github.dsheirer.dsp.oscillator.IComplexOscillator;
import io.github.dsheirer.dsp.oscillator.IRealOscillator;
import io.github.dsheirer.dsp.oscillator.OscillatorFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio Frequency Shift Keying (AFSK) 1200-baud correlation decoder for decoding 8 kHz FM-demodulated audio samples.
 *
 * Note: internally the incoming 8 kHz sample rate is resampled to 7200 Hz to be an integral of the 1200 Hz baud rate
 * which equates to six samples per symbol.  Each symbol is correlated over an eight sample period with correlation
 * values averaged over a seven correlation value period.
 *
 * Provides normal or inverted decoded output.
 */
public class AFSK1200Decoder implements Listener<float[]>
{
    private final static Logger mLog = LoggerFactory.getLogger(AFSK1200Decoder.class);

    public enum Output
    {
        NORMAL, INVERTED
    }

    public static final double SAMPLE_RATE = 7200.0d;
    public static final int SAMPLES_PER_SYMBOL = 6; //SAMPLE_RATE / 1200 baud;

    //Correlation period of 8 samples works well and should align nicely with SIMD intrinsic vector sizes
    public static final int CORRELATION_PERIOD = SAMPLES_PER_SYMBOL + 2;
    public static final int AVERAGING_PERIOD = SAMPLES_PER_SYMBOL + 1;
    public static final double MARK = 1200.0;
    public static final double SPACE = 1800.0;
    public static final float TIMING_ERROR_GAIN = 1.0f / 3.0f; //Timing error adjustments over 3 symbol periods

    private Correlator mCorrelatorMark;
    private Correlator mCorrelatorSpace;
    private float[] mCorrelationValuesMark;
    private float[] mCorrelationValuesSpace;

    protected boolean mNormalOutput;
    protected float mSymbolTimingGain = TIMING_ERROR_GAIN;
    protected AFSKSampleBuffer mSampleBuffer;
    protected AFSKTimingErrorDetector mTimingErrorDetector = new AFSKTimingErrorDetector(SAMPLES_PER_SYMBOL);
    protected IBinarySymbolProcessor mBinarySymbolProcessor;
    private boolean mSampleDecision;

    //Resample to an integral of the baud rate 1200 baud * 6 samples per symbol = 7200.0 Hertz
    private RealResampler mResampler = new RealResampler(8000.0, SAMPLE_RATE, 8192, 512);

    /**
     * Constructs a decoder using the provided arguments with the default (real, phase-sensitive) correlator.
     *
     * @param sampleBuffer for storing incoming samples and calculating symbols
     * @param detector for timing error alignment
     * @param output NORMAL: 1200Hz = Mark(1) and 1800Hz = Space(0), or INVERTED (vice-versa)
     */
    public AFSK1200Decoder(AFSKSampleBuffer sampleBuffer, AFSKTimingErrorDetector detector, Output output)
    {
        this(sampleBuffer, detector, output, false);
    }

    /**
     * Constructs a decoder using the provided arguments.
     *
     * @param sampleBuffer for storing incoming samples and calculating symbols
     * @param detector for timing error alignment
     * @param output NORMAL: 1200Hz = Mark(1) and 1800Hz = Space(0), or INVERTED (vice-versa)
     * @param quadratureCorrelation when true, use a phase-invariant I/Q correlation magnitude
     *      (sqrt(I^2+Q^2)) instead of the single real reference. More robust to marginal-signal
     *      phase, at ~2x correlator cost. Used to form a complementary diversity branch.
     */
    public AFSK1200Decoder(AFSKSampleBuffer sampleBuffer, AFSKTimingErrorDetector detector, Output output,
                           boolean quadratureCorrelation)
    {
        mCorrelatorMark = new Correlator(SAMPLE_RATE, MARK, AVERAGING_PERIOD, CORRELATION_PERIOD, quadratureCorrelation);
        mCorrelatorSpace = new Correlator(SAMPLE_RATE, SPACE, AVERAGING_PERIOD, CORRELATION_PERIOD, quadratureCorrelation);
        mTimingErrorDetector = detector;
        mSampleBuffer = sampleBuffer;
        mSampleBuffer.setTimingGain(mSymbolTimingGain);
        mResampler.setListener(new Decoder());
        mNormalOutput = (output == Output.NORMAL);
    }

    /**
     * Constructs a decoder with the default (real) correlator.
     *
     * @param output NORMAL: 1200Hz = Mark(1) and 1800Hz = Space(0), or INVERTED (vice-versa)
     */
    public AFSK1200Decoder(Output output)
    {
        this(output, false);
    }

    /**
     * Constructs a decoder using the provided arguments.
     *
     * @param output NORMAL: 1200Hz = Mark(1) and 1800Hz = Space(0), or INVERTED (vice-versa)
     * @param quadratureCorrelation true to use the phase-invariant I/Q correlator
     */
    public AFSK1200Decoder(Output output, boolean quadratureCorrelation)
    {
        this(new AFSKSampleBuffer(SAMPLES_PER_SYMBOL, TIMING_ERROR_GAIN),
            new AFSKTimingErrorDetector(SAMPLES_PER_SYMBOL), output, quadratureCorrelation);
    }

    /**
     * Disposes of all references to prepare for garbage collection
     */
    public void dispose()
    {
    }

    /**
     * Processes the buffer samples by converting all samples to boolean values reflecting if the sample value is
     * greater than zero (or not).  Average symbol timing offset is calculated for the full buffer and the offset is
     * adjusted and then each of the symbols are decoded using a simple majority decision.
     *
     * @param buffer containing 8.0 kHz unfiltered FM demodulated audio samples with sub-audible LTR signalling.
     */
    @Override
    public void receive(float[] buffer)
    {
        mResampler.resample(buffer);
    }

    protected void dispatch(boolean symbol)
    {
        if(mBinarySymbolProcessor != null)
        {
            mBinarySymbolProcessor.process(mNormalOutput ? symbol : !symbol);
        }
    }

    /**
     * Registers a listener to receive decoded LTR symbols.
     *
     * @param binarySymbolProcessor to receive symbols.
     */
    public void setSymbolProcessor(IBinarySymbolProcessor binarySymbolProcessor)
    {
        mBinarySymbolProcessor = binarySymbolProcessor;
    }

    /**
     * Removes the symbol listener from receiving decoded LTR symbols.
     */
    public void removeListener()
    {
        mBinarySymbolProcessor = null;
    }


    public class Decoder implements Listener<float[]>
    {
        @Override
        public void receive(float[] buffer)
        {
            //Calculate correlation values against each 1200/1800 reference signal
            mCorrelationValuesMark = mCorrelatorMark.process(buffer);
            mCorrelationValuesSpace = mCorrelatorSpace.process(buffer);

            for(int x = 0; x < mCorrelationValuesMark.length; x++)
            {
                //1200 = Mark (1) and 1800 = Space (0)
                mSampleDecision = mCorrelationValuesMark[x] > mCorrelationValuesSpace[x];
                mSampleBuffer.receive(mSampleDecision);
                mTimingErrorDetector.receive(mSampleDecision);

                if(mSampleBuffer.hasSymbol())
                {
                    dispatch(mSampleBuffer.getSymbol());
                    mSampleBuffer.resetAndAdjust(mTimingErrorDetector.getError());
                }
            }
        }
    }

    /**
     * Generates a correlation value for each FM demodulated sample against samples generated for an established
     * reference frequency, correlated and averaged over separate periods relative to the samples per symbol period.
     *
     * The sample rate of the correlator should be an integral value of the target frequency for optimal performance,
     * This correlator only works across integral sample periods and does not provide intra-sample interpolation.
     *
     * Correlation period (ie number of samples) should be relative to the samples per symbol.  You may have to
     * experiment with various values of (samplesPerSymbol +/- 1, 2 or 3) to obtain the optimal decoder performance.
     *
     * Averaging period defines the number of correlation values to average before producing the final correlation
     * value for each sample.
     */
    public class Correlator
    {
        private FloatAveragingBuffer mAveragingBuffer;
        private final boolean mQuadrature;
        private float[] mReferenceSamples;   //real (cosine) reference - default mode
        private float[] mReferenceI;         //quadrature references
        private float[] mReferenceQ;
        private float[] mDemodulatedSamples;
        private float[] mCorrelationValues;
        private float mCorrelationAccumulator;

        /**
         * Constructs a correlator instance,
         *
         * @param sampleRate of the incoming sample stream.  Note: this should be an integral of the symbol rate.
         * @param frequency of the mark or space symbol to test for correlation
         * @param averagingPeriod is the number of correlation values to average each period
         * @param correlationPeriod is the number of samples to correlate each period
         * @param quadrature true to correlate against quadrature (I/Q) references and use magnitude
         *      sqrt(I^2+Q^2) (phase-invariant); false for the original single real reference with abs().
         */
        public Correlator(double sampleRate, double frequency, int averagingPeriod, int correlationPeriod,
                          boolean quadrature)
        {
            mAveragingBuffer = new FloatAveragingBuffer(averagingPeriod);
            mQuadrature = quadrature;

            if(quadrature)
            {
                //Quadrature (I/Q) reference so tone detection is phase-invariant: a mark/space tone with an
                //arbitrary phase relative to the reference still produces its full magnitude. A single real
                //reference nulls out tones ~90 degrees out of phase, injecting bit errors on marginal bursts.
                IComplexOscillator generator = OscillatorFactory.getComplexOscillator(frequency, sampleRate);
                ComplexSamples reference = generator.generateComplexSamples(correlationPeriod, 0L);
                mReferenceI = reference.i();
                mReferenceQ = reference.q();
            }
            else
            {
                IRealOscillator generator = OscillatorFactory.getRealOscillator(frequency, sampleRate);
                mReferenceSamples = generator.generate(correlationPeriod);
            }

            mDemodulatedSamples = new float[correlationPeriod];
        }

        /**
         * Processes each sample in the incoming sample buffer against a generated reference sample set for one symbol
         * period to derive a correlation value that is in-turn averaged over one symbol period.
         *
         * @param samples containing FM demodulated samples
         * @return a reusable array of correlation values for each sample
         */
        public float[] process(float[] samples)
        {
            if(mCorrelationValues == null || mCorrelationValues.length != samples.length)
            {
                mCorrelationValues = new float[samples.length];
            }

            int y;

            for(int x = 0; x < samples.length; x++)
            {
                //Note: we're using array copy and dot product structures that the JRE can promote to SIMD intrinsics
                //when the host processor supports SIMD instructions
                System.arraycopy(mDemodulatedSamples, 1, mDemodulatedSamples, 0, mDemodulatedSamples.length - 1);
                mDemodulatedSamples[mDemodulatedSamples.length - 1] = samples[x];

                if(mQuadrature)
                {
                    float iAccumulator = 0.0f;
                    float qAccumulator = 0.0f;

                    for(y = 0; y < mDemodulatedSamples.length; y++)
                    {
                        iAccumulator += mDemodulatedSamples[y] * mReferenceI[y];
                        qAccumulator += mDemodulatedSamples[y] * mReferenceQ[y];
                    }

                    //Phase-invariant correlation magnitude sqrt(I^2 + Q^2), averaged over one symbol period.
                    float magnitude = (float)Math.sqrt((iAccumulator * iAccumulator) + (qAccumulator * qAccumulator));
                    mCorrelationValues[x] = mAveragingBuffer.get(magnitude);
                }
                else
                {
                    mCorrelationAccumulator = 0.0f;

                    for(y = 0; y < mDemodulatedSamples.length; y++)
                    {
                        mCorrelationAccumulator += mDemodulatedSamples[y] * mReferenceSamples[y];
                    }

                    //Add the absolute value of correlation accumulator value to the averaging buffer and store the
                    //current average as the correlation value for this sample.  We use absolute value because we
                    //don't care if the signal is out of phase with the reference samples
                    mCorrelationValues[x] = mAveragingBuffer.get(FastMath.abs(mCorrelationAccumulator));
                }
            }

            return mCorrelationValues;
        }
    }
}
