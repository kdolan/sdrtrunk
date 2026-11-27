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

import java.io.File;
import java.util.List;
import jmbe.binary.BinaryFrame;

/**
 * Known-truth control: degrades a strong, clean capture to a sweep of in-band SNRs (several noise seeds each) and
 * scores the standard and weak-signal diversity C4FM decoders against the clean capture's decode.  Truth is every voice
 * frame of the clean decode's error-free LDUs; a degraded frame is right when its Golay-protected bits (c0-c3, the
 * bits that carry most of the speech) match.
 *
 * Usage: NoiseControl <clean.wav> <nac> <work folder> <seeds> <snr dB>...
 */
public class NoiseControl
{
    /**
     * Frame score for one degraded copy
     * @param frames truth frames that both decoders produced a matching LDU for
     * @param standard frames right with the standard decoder
     * @param diversity frames right with the diversity decoder
     * @param gained right with diversity only
     * @param lost right with standard only
     * @param truthFrames frames in the truth
     * @param standardLdus LDUs matched with the standard decoder
     */
    public record Score(int frames, int standard, int diversity, int gained, int lost, int truthFrames, int standardLdus)
    {
        Score plus(Score o)
        {
            return new Score(frames + o.frames, standard + o.standard, diversity + o.diversity, gained + o.gained,
                lost + o.lost, truthFrames + o.truthFrames, standardLdus + o.standardLdus);
        }
    }

    /**
     * Scores both decoders on the degraded capture against the reference (truth) decode.
     */
    public static Score score(List<DiversityProbe.Ldu> reference, File degraded, int nac) throws Exception
    {
        List<DiversityProbe.Ldu> standard = DiversityProbe.decode(degraded, nac, false);
        List<DiversityProbe.Ldu> diversity = DiversityProbe.decode(degraded, nac, true);
        int frames = 0, standardRight = 0, diversityRight = 0, gained = 0, lost = 0, truth = 0, matched = 0;

        for(DiversityProbe.Ldu r: reference)
        {
            truth += 9;
            DiversityProbe.Ldu s = DiversityProbe.match(standard, r, 0.06);
            //Diversity output is held 0.15 s
            DiversityProbe.Ldu d = DiversityProbe.match(diversity, new DiversityProbe.Ldu(r.seconds() + 0.15, r.ldu()), 0.12);

            if(s != null)
            {
                matched++;
            }

            for(int f = 0; f < 9; f++)
            {
                BinaryFrame t = r.ldu().getCorrectedIMBEFrames().get(f).getFrame();
                boolean sOk = s != null && DiversityProbe.golayEqual(s.ldu().getCorrectedIMBEFrames().get(f).getFrame(), t);
                boolean dOk = d != null && DiversityProbe.golayEqual(d.ldu().getCorrectedIMBEFrames().get(f).getFrame(), t);
                frames++;
                standardRight += sOk ? 1 : 0;
                diversityRight += dOk ? 1 : 0;
                gained += (!sOk && dOk) ? 1 : 0;
                lost += (sOk && !dOk) ? 1 : 0;
            }
        }

        return new Score(frames, standardRight, diversityRight, gained, lost, truth, matched);
    }

    /**
     * Truth: the clean capture's error-free LDUs
     */
    public static List<DiversityProbe.Ldu> truth(File clean, int nac) throws Exception
    {
        return DiversityProbe.decode(clean, nac, false).stream()
            .filter(l -> l.ldu().getCorrectedIMBEFrames().stream().allMatch(f -> f.getErrorCountTotal() == 0))
            .toList();
    }

    public static void main(String[] args) throws Exception
    {
        File clean = new File(args[0]);
        int nac = Integer.parseInt(args[1]);
        File work = new File(args[2]);
        int seeds = Integer.parseInt(args[3]);
        //Optional flat fading ahead of the noise: -Dnoise.doppler=Hz (0 = none) -Dnoise.k=Rician K (0 = Rayleigh)
        double doppler = Double.parseDouble(System.getProperty("noise.doppler", "0"));
        double kFactor = Double.parseDouble(System.getProperty("noise.k", "0"));
        work.mkdirs();

        NoiseInjector.Capture capture = NoiseInjector.load(clean);
        NoiseInjector.Measurement measurement = NoiseInjector.measure(capture);
        List<DiversityProbe.Ldu> truth = truth(clean, nac);
        System.out.printf("CLEAN %s | in-band SNR %.1f dB (call %.1f-%.1f s, quiet %.1f-%.1f s) | truth %d error-free " +
            "LDUs (%d frames)%n", clean.getName(), measurement.snrDb(), measurement.callStart(), measurement.callEnd(),
            measurement.quietStart(), measurement.quietEnd(), truth.size(), truth.size() * 9);
        System.out.println(doppler > 0 ? String.format("FADING Rician K %.1f, max Doppler %.1f Hz (average SNR)", kFactor,
            doppler) : "NO FADING (white noise only)");
        System.out.println("SNR dB | LDUs found | frames right: standard  diversity | gained lost");

        for(int a = 4; a < args.length; a++)
        {
            double snr = Double.parseDouble(args[a]);
            Score total = new Score(0, 0, 0, 0, 0, 0, 0);

            for(int seed = 1; seed <= seeds; seed++)
            {
                File degraded = new File(work, String.format("snr%04.1f_seed%d_baseband.wav", snr, seed));
                NoiseInjector.Capture faded = doppler > 0 ? NoiseInjector.fade(capture, doppler, kFactor, seed) : capture;
                NoiseInjector.write(NoiseInjector.degrade(faded, measurement, snr, seed), degraded);
                total = total.plus(score(truth, degraded, nac));
                degraded.delete();
            }

            System.out.printf("%6.1f | %5.1f%% | %8.1f%% %9.1f%% | %6d %4d%n", snr,
                100.0 * total.standardLdus() / Math.max(1, total.truthFrames() / 9),
                100.0 * total.standard() / Math.max(1, total.frames()),
                100.0 * total.diversity() / Math.max(1, total.frames()), total.gained(), total.lost());
        }
    }
}
