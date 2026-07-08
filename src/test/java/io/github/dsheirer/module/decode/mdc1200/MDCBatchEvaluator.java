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
package io.github.dsheirer.module.decode.mdc1200;

import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.module.demodulate.fm.FMDemodulatorModule;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Batch evaluation harness for MDC-1200 detection against a directory of baseband I/Q captures.
 *
 * MDC-1200 frames carry a CRC-16, so a CRC-valid decode at a CONSERVATIVE sync threshold is
 * near-certainly a genuine burst. False-positive analysis (C(40,k) sync-match probability x
 * 1/65536 CRC x framing attempts) shows the false-positive rate is negligible for sync
 * thresholds <= 10 and grows rapidly beyond 12. This harness therefore measures only in the
 * false-positive-safe regime (threshold 8, the production default) so that any increase in
 * CRC-valid bursts from a DSP change is a real improvement, not noise.
 *
 * Ground truth (all real): the union of CRC-valid bursts across the safe configs below.
 * Baseline: the production decoder at threshold 8, normal polarity.
 *
 * Gradle: ./gradlew runMdcBatch -Pdir=/home/kdolan/GitHub/sdrtrunk/_recordings [-Pout=build/mdc-eval.txt]
 */
public class MDCBatchEvaluator
{
    private static final double FM_CHANNEL_BANDWIDTH = 12500.0;
    private static final int IQ_CHUNK = 8192;
    /** Production sync bit-error threshold - the false-positive-safe operating point (override -Pthreshold). */
    private static final int THRESHOLD = Integer.getInteger("mdc.eval.threshold", 8);

    private record Decode(String unit, int opcode, boolean bot)
    {
        @Override public String toString() { return unit + "/op" + opcode + (bot ? "/BOT" : "/EOT"); }
    }

    private record Result(Set<Decode> valid, int syncs) {}

    /**
     * A decode is trusted-real when its opcode is 0 or 1 (emergency / PTT-ID ANI - the traffic that
     * dominates these conventional dispatch channels). Combined with a sync threshold <= 12 this drives
     * the false-positive expectation to ~0.001, so op0/1 counts are a trustworthy realness measure even
     * at slightly permissive thresholds.
     */
    private static boolean trustedReal(Decode d)
    {
        return d.opcode() == 0 || d.opcode() == 1;
    }

    public static void main(String[] args) throws Exception
    {
        String dir = System.getProperty("mdc.eval.dir", args.length > 0 ? args[0] : null);
        if(dir == null || !new File(dir).isDirectory())
        {
            System.err.println("Usage: -Pdir=<directory of *.wav I/Q captures>");
            System.exit(1);
        }

        List<Path> wavs;
        try(var stream = Files.walk(Path.of(dir)))
        {
            wavs = stream.filter(p -> p.toString().toLowerCase().endsWith(".wav"))
                .sorted().collect(Collectors.toList());
        }

        StringBuilder report = new StringBuilder();
        report.append("MDC-1200 batch evaluation (threshold=").append(THRESHOLD)
            .append(", false-positive-safe): ").append(dir).append('\n');
        report.append("files: ").append(wavs.size()).append('\n');
        report.append(String.format("%-46s %5s %6s %6s   %s%n",
            "file", "sync", "valid", "op0/1", "bursts"));

        int files = 0, bursts = 0, trustedBursts = 0, totalSyncs = 0;
        //Count how often each unit recurs across files - a strong corroboration signal for realness.
        TreeMap<String,Integer> unitFileCount = new TreeMap<>();

        List<String[]> rows = new ArrayList<>();
        for(Path wav : wavs)
        {
            Result r = decode(demod(wav, false), THRESHOLD);
            long trusted = r.valid().stream().filter(MDCBatchEvaluator::trustedReal).count();

            if(!r.valid().isEmpty()) files++;
            bursts += r.valid().size();
            trustedBursts += trusted;
            totalSyncs += r.syncs();

            for(Decode d : r.valid()) unitFileCount.merge(d.unit(), 1, Integer::sum);

            String list = r.valid().stream().sorted(java.util.Comparator.comparing(Decode::toString))
                .map(Decode::toString).collect(Collectors.joining(","));
            rows.add(new String[]{shorten(wav.getFileName().toString()),
                String.valueOf(r.syncs()), String.valueOf(r.valid().size()),
                String.valueOf(trusted), list});
        }

        for(String[] r : rows)
        {
            report.append(String.format("%-46s %5s %6s %6s   %s%n", r[0], r[1], r[2], r[3], r[4]));
        }

        report.append("----------------------------------------------------------------\n");
        report.append(String.format("T=%d: %d/%d files, %d CRC-valid bursts (%d op0/1 trusted-real), %d sync detections%n",
            THRESHOLD, files, wavs.size(), bursts, trustedBursts, totalSyncs));
        report.append("recurring units (>=2 files, strongly real): ");
        report.append(unitFileCount.entrySet().stream().filter(e -> e.getValue() >= 2)
            .map(e -> e.getKey() + "x" + e.getValue()).collect(Collectors.joining(", ")));
        report.append('\n');

        String out = System.getProperty("mdc.eval.out", "build/mdc-eval-report.txt");
        Files.writeString(Path.of(out), report.toString());
        System.out.println(report);
        System.out.println("Report written to " + out);
    }

    /** Runs the production MDC decoder at the given sync threshold, returns CRC-valid decodes + sync count. */
    private static Result decode(float[] audio, int syncThreshold)
    {
        MDCDecoder decoder = new MDCDecoder(syncThreshold);
        Set<Decode> results = new LinkedHashSet<>();
        int[] syncs = {0};
        decoder.getMessageFramer().setSyncDetectListener(new ISyncDetectListener()
        {
            @Override public void syncDetected(int bitErrors) { syncs[0]++; }
            @Override public void syncLost(int bitsProcessed) {}
        });
        decoder.setMessageListener(msg ->
        {
            if(msg instanceof MDCMessage mdc && mdc.isValid())
            {
                results.add(new Decode(mdc.getFromIdentifier().getValue().toString(),
                    mdc.getOpcode(), mdc.isBOT()));
            }
        });

        int pos = 0;
        while(pos < audio.length)
        {
            int len = Math.min(512, audio.length - pos);
            float[] chunk = new float[len];
            System.arraycopy(audio, pos, chunk, 0, len);
            decoder.receive(chunk);
            pos += len;
        }
        return new Result(results, syncs[0]);
    }

    /** Loads a stereo I/Q WAV, FM-demodulates to 8 kHz audio (same chain as the live NBFM aux decoder). */
    private static float[] demod(Path wav, boolean swapIQ) throws IOException, UnsupportedAudioFileException
    {
        try(AudioInputStream ais = AudioSystem.getAudioInputStream(wav.toFile()))
        {
            AudioFormat format = ais.getFormat();
            byte[] bytes = ais.readAllBytes();
            boolean bigEndian = format.isBigEndian();
            int channels = format.getChannels();
            double inputRate = format.getSampleRate();
            int perChannel = bytes.length / (2 * channels);

            if(channels != 2)
            {
                throw new IOException("expected stereo I/Q, got " + channels + " channels: " + wav);
            }

            FMDemodulatorModule fm = new FMDemodulatorModule(FM_CHANNEL_BANDWIDTH);
            List<float[]> demods = new ArrayList<>();
            fm.setBufferListener(demods::add);
            fm.getSourceEventListener().receive(SourceEvent.sampleRateChange(inputRate));

            int iOffset = swapIQ ? 2 : 0;
            int qOffset = swapIQ ? 0 : 2;
            int usable = (perChannel / IQ_CHUNK) * IQ_CHUNK;
            for(int p = 0; p < usable; p += IQ_CHUNK)
            {
                float[] i = new float[IQ_CHUNK];
                float[] q = new float[IQ_CHUNK];
                for(int k = 0; k < IQ_CHUNK; k++)
                {
                    i[k] = read16(bytes, 4 * (p + k) + iOffset, bigEndian) / 32768.0f;
                    q[k] = read16(bytes, 4 * (p + k) + qOffset, bigEndian) / 32768.0f;
                }
                fm.receive(new ComplexSamples(i, q, 0L));
            }

            int total = demods.stream().mapToInt(d -> d.length).sum();
            float[] audio = new float[total];
            int off = 0;
            for(float[] d : demods)
            {
                System.arraycopy(d, 0, audio, off, d.length);
                off += d.length;
            }
            return audio;
        }
    }

    private static short read16(byte[] b, int off, boolean bigEndian)
    {
        int lo = b[off] & 0xFF;
        int hi = b[off + 1] & 0xFF;
        return (short)(bigEndian ? (lo << 8) | hi : (hi << 8) | lo);
    }

    private static String shorten(String s)
    {
        return s.length() <= 46 ? s : "..." + s.substring(s.length() - 43);
    }
}
