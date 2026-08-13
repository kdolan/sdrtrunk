/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

import io.github.dsheirer.dsp.filter.interpolator.LinearInterpolator;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.dsp.symbol.DibitToByteBufferAssembler;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.sample.Listener;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * P25 Phase 1 LSM v2 demodulator with improved cold-start handling for conventional (PTT) channels.
 *
 * Improvements over P25P1DemodulatorLSM:
 * - Cold-start reset of PLL and differential state on transmission boundary (triggered by decoder)
 * - Brief PLL acquisition boost (0.12 gain for 15 symbols) after reset for faster carrier lock
 * - Gardner TED suppression for first 2 symbols after reset to avoid timing instability
 * - First differential symbol skipped after reset (no valid reference)
 */
public class P25P1DemodulatorLSMv2
{
    private static final float HALF_PI = (float)(Math.PI / 2.0);
    private static final float MAX_PLL = (float)(Math.PI / 3.0); //+/- 800 Hz
    private static final float OBJECTIVE_MAGNITUDE = 1.0f;
    private static final int SYMBOL_RATE = 4800;

    // PLL gain - acquisition boost helps lock onto carrier at transmission start
    private static final float PLL_GAIN_ACQUISITION = 0.15f;  // Higher gain for faster initial lock
    private static final float PLL_GAIN_TRACKING = 0.1f;
    private static final int PLL_ACQUISITION_SYMBOLS = 24;    // Extended to full sync pattern length

    // AGC gain (matches original LSM)
    private static final float AGC_GAIN = 0.05f;

    // Gardner TED suppression - suppress timing adjustments during initial acquisition
    private static final int TED_SUPPRESSION_SYMBOLS = 4;

    private final DibitToByteBufferAssembler mDibitAssembler = new DibitToByteBufferAssembler(300);
    private final FeedbackDecoder mFeedbackDecoder;
    private final P25P1MessageFramer mMessageFramer;
    private double mSamplePoint;
    private double mSamplesPerSymbol;
    private double mSamplesPerHalfSymbol;
    private float[] mBufferI;
    private float[] mBufferQ;
    private float mPLL = 0f;
    private float mSampleGain = 1f;
    private float mPreviousMiddleI, mPreviousMiddleQ, mPreviousCurrentI, mPreviousCurrentQ;
    private float mPreviousSymbolI = 0f, mPreviousSymbolQ = 0f;
    private int mBufferPointer;
    private int mBufferReserve;

    // Cold-start state tracking
    private int mSymbolsSinceReset = 0;
    private boolean mFirstSymbolAfterReset = true;
    private int mColdStartResetCount = 0;

    // Sync-activity-based PLL reset: after no valid NID for threshold symbols,
    // zero PLL once to prepare for next transmission
    private static final int PLL_RESET_INACTIVITY_THRESHOLD = 4800; // 1 second at 4800 symbols/sec
    private int mSymbolsSinceLastValidNID = 0;
    private boolean mPLLResetApplied = false;

    // Enhanced diagnostics for root-cause analysis
    private int mValidNIDCount = 0;
    private int mSyncDetectCount = 0;
    private float mPLLAtLastReset = 0f;
    private int mSymbolsAtLastReset = 0;
    private float mMaxPLLDrift = 0f;
    private int mTotalSymbols = 0;

    /**
     * Constructs an instance
     * @param messageFramer for receiving demodulated symbol stream and providing sync detection events.
     * @param feedbackDecoder (parent) for receiving PLL carrier offset error reports.
     */
    public P25P1DemodulatorLSMv2(P25P1MessageFramer messageFramer, FeedbackDecoder feedbackDecoder)
    {
        mMessageFramer = messageFramer;
        mFeedbackDecoder = feedbackDecoder;
    }

    /**
     * Reset the PLL when the tuner source either changes center frequency or adjusts the PPM value.
     */
    public void resetPLL()
    {
        mPLL = 0f;
    }

    /**
     * Performs a cold-start reset when a transmission boundary is detected.
     *
     * Resets PLL (new transmitter may have different carrier offset) and differential state
     * (previous symbol is invalid after silence). Preserves AGC gain since signal level is
     * approximately the same on the channel.
     */
    public void coldStartReset()
    {
        // Record diagnostic info before reset
        mPLLAtLastReset = mPLL;
        mSymbolsAtLastReset = mSymbolsSinceLastValidNID;

        // Reset PLL and enable acquisition boost
        mPLL = 0f;
        mSymbolsSinceReset = 0;
        mFirstSymbolAfterReset = true;

        // Reset the inactivity tracking (we just got a boundary signal)
        mSymbolsSinceLastValidNID = 0;
        mPLLResetApplied = false;

        mColdStartResetCount++;
    }

    /**
     * Returns diagnostic statistics for testing purposes.
     */
    public String getDiagnostics()
    {
        return String.format("PLL resets: %d | Valid NIDs: %d | Max PLL drift: %.3f rad (%.0f Hz) | " +
                        "Last reset: PLL=%.3f at %d symbols",
                mColdStartResetCount, mValidNIDCount,
                mMaxPLLDrift, mMaxPLLDrift * SYMBOL_RATE / (2 * Math.PI),
                mPLLAtLastReset, mSymbolsAtLastReset);
    }

    /**
     * Returns detailed diagnostic info for analysis tools.
     */
    public DiagnosticInfo getDiagnosticInfo()
    {
        return new DiagnosticInfo(
                mColdStartResetCount,
                mValidNIDCount,
                mMaxPLLDrift,
                mPLLAtLastReset,
                mSymbolsAtLastReset,
                mTotalSymbols
        );
    }

    /**
     * Container for detailed diagnostic information.
     */
    public record DiagnosticInfo(
        int pllResetCount,
        int validNIDCount,
        float maxPLLDrift,
        float pllAtLastReset,
        int symbolsAtLastReset,
        int totalSymbols
    ) {}

    /**
     * Primary input method for receiving a stream of filtered, pulse-shaped samples to process into symbols.
     * @param i inphase samples to process
     * @param q quadrature samples to process
     */
    public void process(float[] i, float[] q)
    {
        //Shadow copy heap member variables onto the stack
        double samplePoint = mSamplePoint;
        double samplesPerSymbol = mSamplesPerSymbol;
        double samplesPerHalfSymbol = mSamplesPerHalfSymbol;
        float pll = mPLL;
        float previousSymbolI = mPreviousSymbolI;
        float previousSymbolQ = mPreviousSymbolQ;
        float previousMiddleI = mPreviousMiddleI;
        float previousMiddleQ = mPreviousMiddleQ;
        float previousCurrentI = mPreviousCurrentI;
        float previousCurrentQ = mPreviousCurrentQ;
        float sampleGain = mSampleGain;
        int bufferPointer = mBufferPointer;
        int symbolsSinceReset = mSymbolsSinceReset;
        boolean firstSymbolAfterReset = mFirstSymbolAfterReset;

        double tedGain = samplesPerSymbol / 4;
        double maxTimingAdjustment = samplesPerSymbol / 25;
        double pointer, residual, timingAdjustment;
        float magnitude, phaseError = 0, requiredGain, softSymbol, pllI, pllQ, pllTemp;
        float iMiddle, qMiddle, iCurrent, qCurrent, iMiddleDemodulated, qMiddleDemodulated, iSymbol, qSymbol;
        int offset;
        Dibit hardSymbol;

        //I/Q buffers are the same length as incoming samples padded by an extra symbol length for processing space.
        int bufferReserve = mBufferReserve;
        int requiredLength = i.length + bufferReserve;

        if(mBufferI.length != requiredLength)
        {
            mBufferI = Arrays.copyOf(mBufferI, requiredLength);
            mBufferQ = Arrays.copyOf(mBufferQ, requiredLength);
        }

        //Transfer extra reserve samples from last processing iteration to the front of the I/Q buffers
        System.arraycopy(mBufferI, bufferPointer, mBufferI, 0, bufferReserve);
        System.arraycopy(mBufferQ, bufferPointer, mBufferQ, 0, bufferReserve);

        //Copy incoming I/Q samples to the processing buffers
        System.arraycopy(i, 0, mBufferI, bufferReserve, i.length);
        System.arraycopy(q, 0, mBufferQ, bufferReserve, q.length);
        bufferPointer = 0;
        int bufferReload = mBufferI.length - bufferReserve;

        while(bufferPointer < bufferReload)
        {
            bufferPointer++;
            samplePoint--;

            if(samplePoint < 1)
            {
                //Sample point is the middle sample, between the previous and current symbols.
                iMiddle = LinearInterpolator.calculate(mBufferI[bufferPointer], mBufferI[bufferPointer + 1], samplePoint);
                qMiddle = LinearInterpolator.calculate(mBufferQ[bufferPointer], mBufferQ[bufferPointer + 1], samplePoint);

                //Calculate offset to next symbol.
                pointer = bufferPointer + samplePoint + samplesPerHalfSymbol;
                offset = (int)Math.floor(pointer);
                residual = pointer - offset;
                iCurrent = LinearInterpolator.calculate(mBufferI[offset], mBufferI[offset + 1], residual);
                qCurrent = LinearInterpolator.calculate(mBufferQ[offset], mBufferQ[offset + 1], residual);

                //Adjust sample gain based on highest magnitude and apply to both middle and current samples.
                magnitude = (float)(Math.sqrt(Math.pow(iCurrent, 2.0) + Math.pow(qCurrent, 2.0)));

                if(magnitude > 0 && !Float.isInfinite(magnitude))
                {
                    requiredGain = constrain(OBJECTIVE_MAGNITUDE / magnitude, 500);
                    sampleGain += (requiredGain - sampleGain) * AGC_GAIN;
                    sampleGain = Math.min(sampleGain, requiredGain);
                    sampleGain = Math.min(sampleGain, 500);
                }

                //Apply gain to the samples
                iMiddle *= sampleGain;
                qMiddle *= sampleGain;
                iCurrent *= sampleGain;
                qCurrent *= sampleGain;

                // Skip differential demodulation on the very first symbol after reset (previous state is zero/invalid)
                if(firstSymbolAfterReset)
                {
                    firstSymbolAfterReset = false;
                    previousSymbolI = iCurrent;
                    previousSymbolQ = qCurrent;
                    previousMiddleI = iMiddle;
                    previousMiddleQ = qMiddle;
                    previousCurrentI = iCurrent;
                    previousCurrentQ = qCurrent;
                    samplePoint += samplesPerSymbol;
                    symbolsSinceReset++;
                    continue;
                }

                //Create I/Q representation of current PLL state
                pllI = (float)Math.cos(pll);
                pllQ = (float)Math.sin(pll);

                //Differential demodulation of middle sample
                iMiddleDemodulated = (previousMiddleI * iMiddle) - (-previousMiddleQ * qMiddle);
                qMiddleDemodulated = (previousMiddleI * qMiddle) + (-previousMiddleQ * iMiddle);

                //Rotate middle sample by the PLL offset
                pllTemp = (iMiddleDemodulated * pllI) - (qMiddleDemodulated * pllQ);
                qMiddleDemodulated = (qMiddleDemodulated * pllI) + (iMiddleDemodulated * pllQ);
                iMiddleDemodulated = pllTemp;

                //Differential demodulation of symbol
                iSymbol = (previousCurrentI * iCurrent) - (-previousCurrentQ * qCurrent);
                qSymbol = (previousCurrentI * qCurrent) + (-previousCurrentQ * iCurrent);

                //Rotate symbol by the PLL offset
                pllTemp = (iSymbol * pllI) - (qSymbol * pllQ);
                qSymbol = (qSymbol * pllI) + (iSymbol * pllQ);
                iSymbol = pllTemp;

                //Calculate the symbol (radians) from the I/Q values
                softSymbol = (float)Math.atan2(qSymbol, iSymbol);

                //Apply Gardner timing error detection — suppress for first TED_SUPPRESSION_SYMBOLS after reset
                if(symbolsSinceReset >= TED_SUPPRESSION_SYMBOLS)
                {
                    timingAdjustment = ((previousSymbolI - iSymbol) * iMiddleDemodulated) +
                            ((previousSymbolQ - qSymbol) * qMiddleDemodulated);
                    timingAdjustment = constrain(timingAdjustment, maxTimingAdjustment);
                    timingAdjustment *= tedGain;
                    samplePoint += timingAdjustment;
                }

                //Phase Locked Loop adjustment with brief acquisition boost after cold-start
                if(softSymbol != 0)
                {
                    hardSymbol = toDibit(softSymbol);
                    phaseError = constrain(softSymbol - hardSymbol.getIdealPhase(), .3f);
                    float pllGain = (symbolsSinceReset < PLL_ACQUISITION_SYMBOLS) ?
                            PLL_GAIN_ACQUISITION : PLL_GAIN_TRACKING;
                    pll -= (phaseError * pllGain);
                    pll = constrain(pll, MAX_PLL);
                }
                else
                {
                    hardSymbol = Dibit.D00_PLUS_1;
                }

                mTotalSymbols++;

                // Track max PLL drift for diagnostics
                if(Math.abs(pll) > Math.abs(mMaxPLLDrift))
                {
                    mMaxPLLDrift = pll;
                }

                //Message framer returns boolean if valid sync and NID were detected/decoded.
                if(mMessageFramer.processWithSoftSyncDetect(softSymbol, hardSymbol))
                {
                    mFeedbackDecoder.processPLLError(pll);
                    mSymbolsSinceLastValidNID = 0;
                    mPLLResetApplied = false;
                    mValidNIDCount++;
                }
                else
                {
                    mSymbolsSinceLastValidNID++;

                    //One-shot PLL reset after prolonged inactivity (no valid NID)
                    //to prevent PLL drift from blocking sync detection on next transmission
                    if(mSymbolsSinceLastValidNID >= PLL_RESET_INACTIVITY_THRESHOLD && !mPLLResetApplied)
                    {
                        // Record diagnostic info before reset
                        mPLLAtLastReset = pll;
                        mSymbolsAtLastReset = mSymbolsSinceLastValidNID;

                        pll = 0f;
                        symbolsSinceReset = 0;  //Enable acquisition boost gain for next 15 symbols
                        mPLLResetApplied = true;
                        mColdStartResetCount++;
                    }
                }

                mDibitAssembler.receive(hardSymbol);
                mFeedbackDecoder.broadcast(softSymbol);

                //Shuffle the values
                previousSymbolI = iSymbol;
                previousSymbolQ = qSymbol;
                previousMiddleI = iMiddle;
                previousMiddleQ = qMiddle;
                previousCurrentI = iCurrent;
                previousCurrentQ = qCurrent;

                //Add another symbol's worth of samples to the counter
                samplePoint += samplesPerSymbol;
                symbolsSinceReset++;
            }
        }

        //Copy shadow variables back to member variables.
        mPLL = pll;
        mBufferPointer = bufferPointer;
        mPreviousMiddleI = previousMiddleI;
        mPreviousMiddleQ = previousMiddleQ;
        mPreviousCurrentI = previousCurrentI;
        mPreviousCurrentQ = previousCurrentQ;
        mPreviousSymbolI = previousSymbolI;
        mPreviousSymbolQ = previousSymbolQ;
        mSampleGain = sampleGain;
        mSamplePoint = samplePoint;
        mSymbolsSinceReset = symbolsSinceReset;
        mFirstSymbolAfterReset = firstSymbolAfterReset;
    }


    /**
     * Constrains value to range: -constraint to constraint
     */
    private static double constrain(double value, double constraint)
    {
        if(Double.isNaN(value) || Double.isInfinite(value))
        {
            return 0.0;
        }
        else if(value > constraint)
        {
            return constraint;
        }

        return Math.max(value, -constraint);
    }

    /**
     * Constrains value to range: -constraint to constraint
     */
    private static float constrain(float value, float constraint)
    {
        if(Float.isNaN(value) || Float.isInfinite(value))
        {
            return 0.0f;
        }

        if(value > constraint)
        {
            return constraint;
        }

        return Math.max(value, -constraint);
    }

    /**
     * Sets or updates the samples per symbol
     * @param samplesPerSymbol to apply.
     */
    public void setSamplesPerSymbol(float samplesPerSymbol)
    {
        mSamplesPerSymbol = samplesPerSymbol;
        mSamplesPerHalfSymbol = samplesPerSymbol / 2.0f;
        mSamplePoint = samplesPerSymbol;
        mBufferReserve = (int)Math.ceil(samplesPerSymbol);
        mBufferI = new float[mBufferReserve];
        mBufferQ = new float[mBufferReserve];
        mBufferPointer = 0;
    }

    /**
     * Registers the listener to receive demodulated bit stream buffers.
     * @param listener to register
     */
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mDibitAssembler.setBufferListener(listener);
    }

    /**
     * Indicates if there is a registered buffer listener
     */
    public boolean hasBufferListener()
    {
        return mDibitAssembler.hasBufferListeners();
    }

    /**
     * Decodes the sample value to determine the correct QPSK quadrant and maps the value to a Dibit symbol.
     * @param softSymbol in radians.
     * @return symbol decision.
     */
    public static Dibit toDibit(float softSymbol)
    {
        if(softSymbol > 0)
        {
            return softSymbol > HALF_PI ? Dibit.D01_PLUS_3 : Dibit.D00_PLUS_1;
        }
        else
        {
            return softSymbol < -HALF_PI ? Dibit.D11_MINUS_3 : Dibit.D10_MINUS_1;
        }
    }
}
