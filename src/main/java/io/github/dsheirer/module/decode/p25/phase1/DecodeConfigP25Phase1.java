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


import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * APCO25 Phase 1 decoder configuration
 */
public class DecodeConfigP25Phase1 extends DecodeConfigP25
{
    public static final int CHANNEL_ROTATION_DELAY_MINIMUM_MS = 400;
    public static final int CHANNEL_ROTATION_DELAY_DEFAULT_MS = 500;
    public static final int CHANNEL_ROTATION_DELAY_MAXIMUM_MS = 2000;
    public static final int NAC_MINIMUM = 0;
    public static final int NAC_MAXIMUM = 4095;

    /**
     * Default audio holdover period in milliseconds (approximately 1 LDU frame duration)
     */
    public static final int DEFAULT_AUDIO_HOLDOVER_MS = 180;

    /**
     * Maximum audio holdover period in milliseconds.
     * Increased to 1000ms to support simulcast channels where valid LDUs may arrive at longer intervals
     * due to multipath NID corruption.
     */
    public static final int MAX_AUDIO_HOLDOVER_MS = 1000;

    /**
     * Maximum IMBE errors before quality gate activates.
     */
    public static final int MAX_IMBE_ERRORS_MINIMUM = 0;
    public static final int MAX_IMBE_ERRORS_MAXIMUM = 10;

    /**
     * BCH error correction threshold for NAC-assisted NID decode.
     */
    public static final int MAX_BCH_ERRORS_MINIMUM = 1;
    public static final int MAX_BCH_ERRORS_MAXIMUM = 11;
    public static final int MAX_BCH_ERRORS_DEFAULT = 5;

    /**
     * CMA equalizer configuration bounds. Per-channel values override system properties.
     * Set to 0 to disable per-channel override and use system property fallback.
     */
    public static final float CMA_MU_MINIMUM = 0.0f;
    public static final float CMA_MU_MAXIMUM = 0.010f;
    public static final int CMA_GEAR_SHIFT_MS_MINIMUM = 0;
    public static final int CMA_GEAR_SHIFT_MS_MAXIMUM = 1000;

    private Modulation mModulation = Modulation.C4FM;
    private String mModulationRaw; // Preserves unknown modulation enum values for round-trip serialization
    private int mConfiguredNAC = 0; // 0 = auto-detect, 1-4095 = configured NAC
    private int mAudioHoldoverMs = DEFAULT_AUDIO_HOLDOVER_MS;
    private boolean mIgnoreEncryptionState = false;
    private int mMaxImbeErrors = 0; // 0 = disabled, 1-10 = quality gate threshold
    private int mMaxBchErrors = MAX_BCH_ERRORS_DEFAULT;
    private float mCmaAcquisitionMu = 0.003f; // Fast convergence for initial lock after cold-start
    private float mCmaTrackingMu = 0.001f;   // Stable tracking, proven +69% LDUs on ROC W simulcast
    private int mCmaGearShiftMs = 200;        // 200ms acquisition before switching to tracking

    private boolean mPipelineDiagnostics = false;

    // C4FM V2 configuration
    private float mGardnerBandwidth = 0.01f;
    private float mAfcAlpha = 0.01f;
    private boolean mAdaptiveThresholds = false;
    private boolean mDfeEnabled = false;
    private float mDfeMu = 0.005f;

    /**
     * Constructs an instance
     */
    public DecodeConfigP25Phase1()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    /**
     * Gets the modulation enum for runtime use.
     */
    @JsonIgnore
    public Modulation getModulation()
    {
        return mModulation;
    }

    /**
     * Sets the modulation for runtime use.
     */
    @JsonIgnore
    public void setModulation(Modulation modulation)
    {
        mModulation = modulation;
        mModulationRaw = modulation.name();
    }

    /**
     * Jackson serialization getter — returns the raw modulation string to preserve
     * unknown enum values (e.g. C4FM_V2 on a codebase that doesn't define it yet).
     */
    @JacksonXmlProperty(isAttribute = true, localName = "modulation")
    @JsonGetter("modulation")
    public String getModulationString()
    {
        return mModulationRaw != null ? mModulationRaw : mModulation.name();
    }

    /**
     * Jackson deserialization setter — captures the raw string to preserve unknown enum values,
     * while resolving to the best-matching enum for runtime use.
     */
    @JsonSetter("modulation")
    public void setModulationString(String value)
    {
        mModulationRaw = value;
        try
        {
            mModulation = Modulation.valueOf(value);
        }
        catch(IllegalArgumentException e)
        {
            // Unknown modulation value — preserve raw string, use C4FM as safe fallback
            mModulation = Modulation.C4FM;
        }
    }

    /**
     * Gets the configured NAC (Network Access Code) for this channel.
     * When set to a non-zero value (1-4095), the decoder will use this NAC for improved NID error correction.
     * When set to 0 (default), the decoder will auto-detect the NAC from received transmissions.
     *
     * @return configured NAC value, or 0 for auto-detect
     */
    @JacksonXmlProperty(isAttribute = true, localName = "configuredNAC")
    public int getConfiguredNAC()
    {
        return mConfiguredNAC;
    }

    /**
     * Sets the configured NAC (Network Access Code) for this channel.
     * Set to a value between 1-4095 to use a known NAC for improved error correction.
     * Set to 0 to auto-detect the NAC from received transmissions.
     *
     * @param nac the NAC value (0-4095)
     */
    public void setConfiguredNAC(int nac)
    {
        if(nac < NAC_MINIMUM || nac > NAC_MAXIMUM)
        {
            throw new IllegalArgumentException("NAC must be between " + NAC_MINIMUM + " and " + NAC_MAXIMUM);
        }
        mConfiguredNAC = nac;
    }

    /**
     * Indicates if a NAC is configured for this channel.
     * @return true if a NAC is configured (non-zero)
     */
    @JsonIgnore
    public boolean hasConfiguredNAC()
    {
        return mConfiguredNAC > 0;
    }

    /**
     * Gets the audio holdover period in milliseconds. During decode errors, if RF signal energy
     * indicates an active transmission is still present, the decoder will maintain the call state
     * for up to this duration before ending the audio segment.
     *
     * @return holdover period in milliseconds (0 to MAX_AUDIO_HOLDOVER_MS)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "audioHoldoverMs")
    public int getAudioHoldoverMs()
    {
        return mAudioHoldoverMs;
    }

    /**
     * Sets the audio holdover period in milliseconds. When set to 0, holdover is disabled.
     * Values above MAX_AUDIO_HOLDOVER_MS will be clamped to the maximum.
     *
     * @param holdoverMs the holdover period in milliseconds (0 to MAX_AUDIO_HOLDOVER_MS)
     */
    public void setAudioHoldoverMs(int holdoverMs)
    {
        if(holdoverMs < 0)
        {
            mAudioHoldoverMs = 0;
        }
        else if(holdoverMs > MAX_AUDIO_HOLDOVER_MS)
        {
            mAudioHoldoverMs = MAX_AUDIO_HOLDOVER_MS;
        }
        else
        {
            mAudioHoldoverMs = holdoverMs;
        }
    }

    /**
     * Indicates if encryption detection should be ignored for this channel.
     * When true, the decoder will assume all calls are unencrypted and begin audio processing
     * immediately without waiting to verify encryption state. Use this for dedicated voice
     * channels that are known to never carry encrypted traffic.
     *
     * @return true if encryption detection should be bypassed
     */
    @JacksonXmlProperty(isAttribute = true, localName = "ignoreEncryptionState")
    public boolean isIgnoreEncryptionState()
    {
        return mIgnoreEncryptionState;
    }

    /**
     * Sets whether encryption detection should be ignored.
     *
     * @param ignore true to bypass encryption detection and assume unencrypted
     */
    public void setIgnoreEncryptionState(boolean ignore)
    {
        mIgnoreEncryptionState = ignore;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "pipelineDiagnostics")
    public boolean isPipelineDiagnostics()
    {
        return mPipelineDiagnostics;
    }

    public void setPipelineDiagnostics(boolean enabled)
    {
        mPipelineDiagnostics = enabled;
    }

    /**
     * Gets the maximum IMBE FEC errors allowed per frame before the quality gate activates.
     * Frames exceeding this threshold are replaced with silence or frame repetition before
     * being sent to the JMBE codec, preventing codec state contamination.
     * Set to 0 to disable the quality gate (default).
     *
     * @return max IMBE errors threshold (0 = disabled, recommended: 3 for simulcast)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "maxImbeErrors")
    public int getMaxImbeErrors()
    {
        return mMaxImbeErrors;
    }

    /**
     * Sets the maximum IMBE FEC errors per frame for the pre-codec quality gate.
     * Set to 0 to disable. Values 1-10 enable the gate at that threshold.
     *
     * @param maxErrors maximum total FEC errors per IMBE frame (0 = disabled)
     */
    public void setMaxImbeErrors(int maxErrors)
    {
        mMaxImbeErrors = Math.max(MAX_IMBE_ERRORS_MINIMUM, Math.min(MAX_IMBE_ERRORS_MAXIMUM, maxErrors));
    }

    /**
     * Gets the maximum BCH error corrections allowed for NAC-assisted NID decode.
     * Controls how many bit corrections are accepted when using configured NAC and DUID enumeration.
     * Lower values reject more aggressively (filtering corrupted frames), higher values accept more corrections.
     *
     * @return max BCH errors threshold (1-11, default 5)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "maxBchErrors")
    public int getMaxBchErrors()
    {
        return mMaxBchErrors;
    }

    /**
     * Sets the maximum BCH error corrections for NAC-assisted NID decode.
     *
     * @param maxErrors maximum BCH corrections allowed (1-11)
     */
    public void setMaxBchErrors(int maxErrors)
    {
        mMaxBchErrors = Math.max(MAX_BCH_ERRORS_MINIMUM, Math.min(MAX_BCH_ERRORS_MAXIMUM, maxErrors));
    }

    /**
     * Gets the CMA equalizer acquisition step size for this channel.
     * Used during initial convergence after cold-start. Larger values converge faster but
     * may introduce noise. Set to 0 to use the system-wide default.
     *
     * @return acquisition mu (default: 0.003, 0.0 = use system property fallback)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "cmaAcquisitionMu")
    public float getCmaAcquisitionMu()
    {
        return mCmaAcquisitionMu;
    }

    public void setCmaAcquisitionMu(float mu)
    {
        mCmaAcquisitionMu = Math.max(CMA_MU_MINIMUM, Math.min(CMA_MU_MAXIMUM, mu));
    }

    /**
     * Gets the CMA equalizer tracking step size for this channel.
     * Used during steady-state tracking after gear-shift. Smaller values track more
     * accurately but respond slower to changes. Set to 0 to use the system-wide default.
     *
     * @return tracking mu (default: 0.001, 0.0 = use system property fallback)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "cmaTrackingMu")
    public float getCmaTrackingMu()
    {
        return mCmaTrackingMu;
    }

    public void setCmaTrackingMu(float mu)
    {
        mCmaTrackingMu = Math.max(CMA_MU_MINIMUM, Math.min(CMA_MU_MAXIMUM, mu));
    }

    /**
     * Gets the CMA gear-shift timing for this channel.
     * After this many milliseconds, the equalizer switches from acquisition mu to tracking mu.
     * Set to 0 to use the system-wide default (no gear-shifting if no system property set).
     *
     * @return gear-shift timing in ms (default: 200, 0 = use system property fallback)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "cmaGearShiftMs")
    public int getCmaGearShiftMs()
    {
        return mCmaGearShiftMs;
    }

    public void setCmaGearShiftMs(int ms)
    {
        mCmaGearShiftMs = Math.max(CMA_GEAR_SHIFT_MS_MINIMUM, Math.min(CMA_GEAR_SHIFT_MS_MAXIMUM, ms));
    }

    /**
     * Gets the Gardner TED loop bandwidth for C4FM V2.
     * @return bandwidth (default: 0.01)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "gardnerBandwidth")
    public float getGardnerBandwidth()
    {
        return mGardnerBandwidth;
    }

    public void setGardnerBandwidth(float bandwidth)
    {
        mGardnerBandwidth = Math.max(0.001f, Math.min(0.1f, bandwidth));
    }

    /**
     * Gets the AFC tracking alpha for C4FM V2.
     * @return alpha (default: 0.01)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "afcAlpha")
    public float getAfcAlpha()
    {
        return mAfcAlpha;
    }

    public void setAfcAlpha(float alpha)
    {
        mAfcAlpha = Math.max(0.001f, Math.min(0.1f, alpha));
    }

    /**
     * Gets whether adaptive decision thresholds are enabled for C4FM V2.
     * @return true if enabled (default: true)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "adaptiveThresholds")
    public boolean isAdaptiveThresholds()
    {
        return mAdaptiveThresholds;
    }

    public void setAdaptiveThresholds(boolean enabled)
    {
        mAdaptiveThresholds = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "dfeEnabled")
    public boolean isDfeEnabled()
    {
        return mDfeEnabled;
    }

    public void setDfeEnabled(boolean enabled)
    {
        mDfeEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "dfeMu")
    public float getDfeMu()
    {
        return mDfeMu;
    }

    public void setDfeMu(float mu)
    {
        mDfeMu = Math.max(0.0001f, Math.min(0.05f, mu));
    }

    /**
     * Source channel specification for this decoder
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(24000.0, 12500, 5750.0, 6500.0);
    }
}
