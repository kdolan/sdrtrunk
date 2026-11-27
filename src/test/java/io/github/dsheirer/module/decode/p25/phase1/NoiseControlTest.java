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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Known-truth control on the corpus's strongest capture (p25-c4fm-f_01, ~41 dB in-band SNR): noise is added to bring
 * it down to a weak-signal SNR and both C4FM decoders are scored against the clean decode.
 */
public class NoiseControlTest
{
    private static final File CLEAN = new File("testdata/baseband/p25-c4fm-f/p25-c4fm-f_01_100000007_baseband.wav");
    private static final int NAC = 2311;

    @TempDir
    Path mTemp;

    @Test
    public void injectedNoiseMeetsTheTargetSnr() throws Exception
    {
        assumeTrue(CLEAN.exists(), "corpus clip not present: " + CLEAN);
        NoiseInjector.Capture clean = NoiseInjector.load(CLEAN);
        NoiseInjector.Measurement measurement = NoiseInjector.measure(clean);
        assertTrue(measurement.snrDb() > 30, "clean reference is strong: " + measurement.snrDb());

        for(double target: new double[]{12, 6})
        {
            NoiseInjector.Capture noisy = NoiseInjector.degrade(clean, measurement, target, 1);
            double fs = noisy.sampleRate();
            double total = NoiseInjector.inBandPower(noisy, (int)(measurement.callStart() * fs),
                (int)(measurement.callEnd() * fs));
            double noise = NoiseInjector.inBandPower(noisy, (int)(measurement.quietStart() * fs),
                (int)(measurement.quietEnd() * fs));
            assertEquals(target, 10 * Math.log10((total - noise) / noise), 0.75, "measured in-band SNR");
        }
    }

    @Test
    public void diversityRecoversWeakSignalFramesWithoutLosingAny() throws Exception
    {
        assumeTrue(CLEAN.exists(), "corpus clip not present: " + CLEAN);
        NoiseInjector.Capture clean = NoiseInjector.load(CLEAN);
        File noisy = mTemp.resolve("snr07_baseband.wav").toFile();
        NoiseInjector.write(NoiseInjector.degrade(clean, NoiseInjector.measure(clean), 7.0, 1), noisy);

        List<DiversityProbe.Ldu> truth = NoiseControl.truth(CLEAN, NAC);
        assertTrue(truth.size() >= 25, "truth LDUs: " + truth.size());
        NoiseControl.Score score = NoiseControl.score(truth, noisy, NAC);
        double standard = 100.0 * score.standard() / score.frames();
        double diversity = 100.0 * score.diversity() / score.frames();

        //At 7 dB (3 seeds): standard 41.9%, diversity 71.0%, 220 frames gained and none lost
        assertTrue(standard > 25, "standard decoder still decodes some of the call: " + standard);
        assertTrue(diversity >= standard + 15, String.format("diversity %.1f%% vs standard %.1f%%", diversity, standard));
        assertTrue(score.lost() <= 2, "frames the standard decoder had right and diversity lost: " + score.lost());
    }
}
