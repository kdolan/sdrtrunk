package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import jmbe.binary.BinaryFrame;
import jmbe.frame.CorrectedIMBEFrame;

/**
 * Known-truth evaluation of the weak-signal diversity decoder: decodes a degraded copy with the standard C4FM decoder
 * and with P25P1DecoderC4FMDiversity, and scores both against the clean reference capture.
 * Usage: DiversityProbe <reference> <degraded> <nac>
 */
public class DiversityProbe
{
    record Ldu(double seconds, LDUMessage ldu) {}

    static long[] sReplaced = new long[2];

    static List<Ldu> decode(File file, int nac, boolean diversity) throws Exception
    {
        List<Ldu> out = new ArrayList<>();
        long[] samples = {0};
        double[] rate = {50000};
        P25P1DecoderC4FM decoder = diversity ? new P25P1DecoderC4FMDiversity() : new P25P1DecoderC4FM();
        decoder.setConfiguredNAC(nac);
        if(decoder instanceof P25P1DecoderC4FMDiversity d)
        {
            d.setMaxBchErrors(5);
        }
        else
        {
            decoder.getMessageFramer().setMaxBchErrors(5);
        }
        decoder.setMessageListener(message -> {
            if(message instanceof LDUMessage ldu && !ldu.isDuidCorrected() && !ldu.isTruncated())
            {
                out.add(new Ldu(samples[0] / rate[0], ldu));
            }
        });
        decoder.start();
        try(TestComplexWaveSource source = new TestComplexWaveSource(file))
        {
            rate[0] = source.getSampleRate();
            source.setListener(buffer -> {
                var iterator = buffer.iterator();
                while(iterator.hasNext())
                {
                    ComplexSamples s = iterator.next();
                    decoder.receive(s);
                    samples[0] += s.length();
                }
            });
            decoder.setSampleRate(source.getSampleRate());
            while(source.next(2048))
            {
            }
        }
        decoder.stop();
        if(decoder instanceof P25P1DecoderC4FMDiversity d)
        {
            sReplaced[0] = d.getFramesReplaced();
            sReplaced[1] = d.getFramesAssessed();
        }
        return out;
    }

    static boolean golayEqual(BinaryFrame a, BinaryFrame b)
    {
        return a.get(0, 92).equals(b.get(0, 92));
    }

    static Ldu match(List<Ldu> list, Ldu r, double window)
    {
        Ldu best = null;
        for(Ldu x: list)
        {
            if(x.ldu().getDUID() == r.ldu().getDUID() && Math.abs(x.seconds() - r.seconds()) < window &&
               (best == null || Math.abs(x.seconds() - r.seconds()) < Math.abs(best.seconds() - r.seconds())))
            {
                best = x;
            }
        }
        return best;
    }

    public static void main(String[] args) throws Exception
    {
        int nac = Integer.parseInt(args[2]);
        List<Ldu> reference = decode(new File(args[0]), nac, false);
        List<Ldu> standard = decode(new File(args[1]), nac, false);
        List<Ldu> diversity = decode(new File(args[1]), nac, true);

        int frames = 0, stdGolay = 0, divGolay = 0, stdWhole = 0, divWhole = 0, lost = 0, gained = 0;
        for(Ldu r: reference)
        {
            if(r.ldu().getCorrectedIMBEFrames().stream().anyMatch(f -> f.getErrorCountTotal() > 0))
            {
                continue;
            }
            //Diversity output is held 0.15 s, but positions are taken when the message arrives at the listener
            Ldu s = match(standard, r, 0.06);
            Ldu d = match(diversity, new Ldu(r.seconds() + 0.15, r.ldu()), 0.12);
            if(s == null || d == null)
            {
                continue;
            }
            for(int f = 0; f < 9; f++)
            {
                BinaryFrame truth = r.ldu().getCorrectedIMBEFrames().get(f).getFrame();
                CorrectedIMBEFrame sf = s.ldu().getCorrectedIMBEFrames().get(f);
                CorrectedIMBEFrame df = d.ldu().getCorrectedIMBEFrames().get(f);
                boolean sOk = golayEqual(sf.getFrame(), truth);
                boolean dOk = golayEqual(df.getFrame(), truth);
                frames++;
                stdGolay += sOk ? 1 : 0;
                divGolay += dOk ? 1 : 0;
                stdWhole += sf.getFrame().equals(truth) ? 1 : 0;
                divWhole += df.getFrame().equals(truth) ? 1 : 0;
                lost += (sOk && !dOk) ? 1 : 0;
                if(sOk && !dOk)
                {
                    boolean sameRaw = java.util.Arrays.equals(s.ldu().getIMBEFrames().get(f), d.ldu().getIMBEFrames().get(f));
                    System.out.printf("LOST t=%.2f frame %d | diversity LDU replaced %d frames | raw frame identical to standard %b | " +
                        "standard errors %d diversity errors %d | std-vs-div dt %.3f%n", r.seconds(), f,
                        d.ldu().getReplacedIMBEFrameCount(), sameRaw, sf.getErrorCountTotal(), df.getErrorCountTotal(),
                        d.seconds() - s.seconds());
                }
                gained += (!sOk && dOk) ? 1 : 0;
            }
        }

        System.out.printf("RESULT %s | LDUs standard %d diversity %d | frames %d | c0-c3 standard %.1f%% diversity %.1f%% " +
            "(gained %d, lost %d) | whole frame standard %.1f%% diversity %.1f%% | replaced %d of %d assessed%n",
            new File(args[1]).getName(), standard.size(), diversity.size(), frames,
            100.0 * stdGolay / Math.max(1, frames), 100.0 * divGolay / Math.max(1, frames), gained, lost,
            100.0 * stdWhole / Math.max(1, frames), 100.0 * divWhole / Math.max(1, frames), sReplaced[0], sReplaced[1]);
    }
}
