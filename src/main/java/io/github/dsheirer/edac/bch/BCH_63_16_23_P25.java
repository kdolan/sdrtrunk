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

package io.github.dsheirer.edac.bch;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;

/**
 * APCO 25 BCH(63,16,23) code with T=11 error bit correction capacity.
 */
public class BCH_63_16_23_P25 extends BCH_63
{
    public static final int K = 16;
    private static final int T = 11; // Error-correcting capability
    public static final IntField NAC_FIELD = IntField.length12(0);
    public static final IntField DUID_FIELD = IntField.length4(12);
    //Generator polynomial g(x) for BCH(63,16,23), degree 47 (octal 6331 1413 6723 5453 per TIA-102.BAAA)
    private static final long GENERATOR_POLYNOMIAL = 0xCD930BDD3B2BL;
    private static final int PARITY_LENGTH = 47;

    /**
     * Constructs a BCH decoder instance for processing APCO25 BCH(63,16,23) protected NID codewords
     */
    public BCH_63_16_23_P25()
    {
        super(K, T);
    }

    /**
     * Encodes the NAC and DUID as a systematic BCH(63,16,23) NID codeword.  The returned value holds the 63 codeword
     * bits with the first transmitted bit (NAC MSB) in bit position 62.  Note: the 64th NID bit is not included.
     *
     * @param nac network access code (12 bits)
     * @param duid data unit ID value (4 bits)
     * @return 63-bit codeword.
     */
    public static long encode(int nac, int duid)
    {
        long information = ((long)(nac & 0xFFF) << 4) | (duid & 0xF);
        long remainder = information << PARITY_LENGTH;

        for(int bit = 62; bit >= PARITY_LENGTH; bit--)
        {
            if(((remainder >> bit) & 1) == 1)
            {
                remainder ^= GENERATOR_POLYNOMIAL << (bit - PARITY_LENGTH);
            }
        }

        return (information << PARITY_LENGTH) | remainder;
    }

    @Override
    public void decode(CorrectedBinaryMessage message)
    {
        super.decode(message);
    }

    // Valid P25 Phase 1 DUID values that appear in the NID.
    // Order matters: LDU1/LDU2 are tried first because they are the most common frame types
    // (~10:1 ratio vs HDU/TDU). Previous order {0,3,5,7,10,12,15} caused NAC-assisted
    // correction to preferentially decode voice frames as TDU, producing a TDU flood.
    private static final int[] VALID_DUIDS = {5, 10, 0, 3, 7, 12, 15};

    /**
     * Attempts to error correct the NID message.  If the message is uncorrectable and a configured NAC is provided,
     * performs NAC-assisted correction by forcing the known NAC value and enumerating all valid DUID values.
     * This effectively reduces the error correction problem from 16 unknown information bits to just 47 parity bits
     * with known information, significantly improving recovery on simulcast channels where NID corruption is common.
     *
     * @param message to correct
     * @param configuredNAC that can be used for NAC-assisted correction (0 = disabled).
     */
    public void decode(CorrectedBinaryMessage message, int configuredNAC)
    {
        decode(message, configuredNAC, T);
    }

    /**
     * Attempts to error correct the NID message with a configurable BCH error threshold for NAC-assisted corrections.
     * Standard BCH decode always uses the full T=11 capability. The maxBchErrors threshold only applies to
     * NAC-forced and DUID-enumerated corrections, rejecting corrections that needed more bit corrections than
     * the threshold allows. This filters out frames where heavy NID correction indicates likely voice data corruption.
     *
     * @param message to correct
     * @param configuredNAC that can be used for NAC-assisted correction (0 = disabled).
     * @param maxBchErrors maximum BCH corrections allowed for NAC-assisted/DUID-enumerated corrections (1-11).
     */
    public void decode(CorrectedBinaryMessage message, int configuredNAC, int maxBchErrors)
    {
        // First: standard BCH decode (works for all channels, always uses full T=11 capability)
        decode(message);

        if(message.getCorrectedBitCount() != BCH.MESSAGE_NOT_CORRECTED || configuredNAC <= 0)
        {
            return;
        }

        // Standard BCH failed. Try NAC-assisted correction.
        // Save original NAC and DUID bits (BCH decode does not modify bits on failure)
        int originalNAC = message.getInt(NAC_FIELD);
        int originalDUID = message.getInt(DUID_FIELD);

        // First try: force configured NAC, keep original DUID (most likely to succeed)
        if(originalNAC != configuredNAC)
        {
            message.setInt(configuredNAC, NAC_FIELD);
            decode(message);

            if(message.getCorrectedBitCount() != BCH.MESSAGE_NOT_CORRECTED)
            {
                if(message.getCorrectedBitCount() <= maxBchErrors)
                {
                    return;
                }
                // Correction exceeded threshold — reject
                message.setCorrectedBitCount(BCH.MESSAGE_NOT_CORRECTED);
            }
        }

        // Second try: enumerate all valid DUIDs with forced NAC
        for(int duid : VALID_DUIDS)
        {
            if(duid == originalDUID && originalNAC == configuredNAC)
            {
                continue; // Already tried this combination in standard decode
            }
            if(duid == originalDUID)
            {
                continue; // Already tried this DUID with forced NAC above
            }

            message.setInt(configuredNAC, NAC_FIELD);
            message.setInt(duid, DUID_FIELD);
            decode(message);

            if(message.getCorrectedBitCount() != BCH.MESSAGE_NOT_CORRECTED)
            {
                if(message.getCorrectedBitCount() <= maxBchErrors)
                {
                    return;
                }
                // Correction exceeded threshold — reject
                message.setCorrectedBitCount(BCH.MESSAGE_NOT_CORRECTED);
            }
        }

        // All attempts failed. Restore original information bits.
        message.setInt(originalNAC, NAC_FIELD);
        message.setInt(originalDUID, DUID_FIELD);
        message.setCorrectedBitCount(BCH.MESSAGE_NOT_CORRECTED);
    }
}
