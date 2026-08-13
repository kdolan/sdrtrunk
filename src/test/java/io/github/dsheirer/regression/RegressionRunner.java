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
package io.github.dsheirer.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.module.decode.mdc1200.MDCBatchEvaluator;
import io.github.dsheirer.module.decode.p25.phase1.DecodeQualityTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decode regression gate for the committed test corpus.
 *
 * Runs the P25 decode-quality harness and the MDC-1200 batch evaluator over testdata/baseband and
 * compares the result against the committed baseline, exiting non-zero if decode got worse.
 *
 * Repeat runs of the same code on the same corpus were verified byte-identical on every decode
 * counter, so the gated metrics carry ZERO tolerance - any drop is a real regression, not noise.
 * The single exception is quality_score (waveform artifact analysis), which drifts ~0.5% between
 * runs; it is reported but never gated.
 *
 * Usage:
 *   ./gradlew runRegression                 - check against the baseline
 *   ./gradlew runRegression -Pupdate        - accept current results as the new baseline
 */
public class RegressionRunner
{
    private static final String SAMPLES = "testdata/baseband";
    private static final String PLAYLIST = "testdata/corpus-playlist.xml";
    private static final Path BASELINE = Path.of("testdata/baseline");
    private static final Path OUT = Path.of("build/regression");
    private static final int MDC_THRESHOLD = 8;

    /** Fraction by which audio_seconds may fall before it counts as a regression. */
    private static final double AUDIO_TOLERANCE = 0.01;

    /** Reported on every file; only the gated subset below can fail the run. */
    private static final String[] REPORTED = {"ldu_count", "valid_messages", "total_messages",
        "sync_blocked", "bit_errors", "sync_losses", "audio_seconds", "audio_segments", "quality_score"};

    /** Integer counters where any decrease is a regression. */
    private static final String[] GATED = {"ldu_count", "valid_messages"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception
    {
        boolean update = List.of(args).contains("--update");
        String jmbe = argValue(args, "--jmbe");

        Files.createDirectories(OUT);

        List<String> decodeArgs = new ArrayList<>(List.of("--samples", SAMPLES, "--playlist", PLAYLIST,
            "--output", OUT.toString(), "--mode", "full"));
        if(jmbe != null)
        {
            decodeArgs.addAll(List.of("--jmbe", jmbe));
        }
        else
        {
            System.out.println("NOTE: no --jmbe codec supplied; audio metrics will be zero for every file.");
        }

        System.out.println("── P25 decode quality ──");
        DecodeQualityTest.main(decodeArgs.toArray(new String[0]));

        System.out.println("── MDC-1200 batch ──");
        System.setProperty("mdc.eval.dir", SAMPLES);
        System.setProperty("mdc.eval.out", OUT.resolve("mdc.txt").toString());
        System.setProperty("mdc.eval.threshold", String.valueOf(MDC_THRESHOLD));
        MDCBatchEvaluator.main(new String[0]);

        if(update)
        {
            Files.createDirectories(BASELINE);
            Files.copy(OUT.resolve("metrics.json"), BASELINE.resolve("metrics.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(OUT.resolve("mdc.txt"), BASELINE.resolve("mdc.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("\n✓ Baseline updated from this run.");
            return;
        }

        int regressions = compareDecode() + compareMdc();

        System.out.println();
        if(regressions > 0)
        {
            System.out.printf("✗ REGRESSION: %d gated metric(s) got worse.%n", regressions);
            System.exit(1);
        }
        System.out.println("✓ No regressions. Corpus decode is at or above baseline.");
    }

    /**
     * Compares per-file P25 decode metrics against the baseline.
     * @return count of gated metrics that regressed
     */
    private static int compareDecode() throws IOException
    {
        Map<String,JsonNode> base = byFile(BASELINE.resolve("metrics.json"));
        Map<String,JsonNode> now = byFile(OUT.resolve("metrics.json"));

        System.out.println("\n── P25 decode vs baseline ──");
        System.out.printf("%-38s %-16s %10s %10s %9s%n", "file", "metric", "baseline", "current", "delta");

        int regressions = 0;

        for(String missing : base.keySet().stream().filter(f -> !now.containsKey(f)).toList())
        {
            System.out.printf("%-38s %-16s %10s %10s %9s%n", missing, "MISSING", "-", "-", "FAIL");
            regressions++;
        }

        for(Map.Entry<String,JsonNode> e : base.entrySet())
        {
            JsonNode b = e.getValue(), c = now.get(e.getKey());
            if(c == null) continue;

            for(String key : REPORTED)
            {
                if(!b.has(key) || !c.has(key)) continue;
                double bv = b.get(key).asDouble(), cv = c.get(key).asDouble();
                if(bv == cv) continue;

                boolean fail = switch(key)
                {
                    case "ldu_count", "valid_messages" -> cv < bv;
                    case "audio_seconds" -> cv < bv * (1 - AUDIO_TOLERANCE);
                    default -> false;
                };
                if(fail) regressions++;

                System.out.printf("%-38s %-16s %10.2f %10.2f %+9.2f %s%n", e.getKey(), key, bv, cv, cv - bv,
                    fail ? "FAIL" : (cv > bv ? "(improved)" : ""));
            }
        }

        if(regressions == 0)
        {
            System.out.println("  all gated decode metrics at or above baseline");
        }
        return regressions;
    }

    /**
     * Compares per-file MDC-1200 CRC-valid burst counts against the baseline.  A drop means the
     * decoder stopped recovering a burst it used to recover.
     * @return count of files whose burst count regressed
     */
    private static int compareMdc() throws IOException
    {
        Map<String,Integer> base = mdcValidCounts(BASELINE.resolve("mdc.txt"));
        Map<String,Integer> now = mdcValidCounts(OUT.resolve("mdc.txt"));

        System.out.println("\n── MDC-1200 CRC-valid bursts vs baseline ──");
        int regressions = 0, baseTotal = 0, nowTotal = 0;

        for(Map.Entry<String,Integer> e : base.entrySet())
        {
            int b = e.getValue(), c = now.getOrDefault(e.getKey(), -1);
            baseTotal += b;
            nowTotal += Math.max(c, 0);
            if(c < b)
            {
                System.out.printf("%-38s %d -> %d  FAIL%n", e.getKey(), b, c);
                regressions++;
            }
            else if(c > b)
            {
                System.out.printf("%-38s %d -> %d  (improved)%n", e.getKey(), b, c);
            }
        }

        System.out.printf("  total CRC-valid bursts: %d -> %d%n", baseTotal, nowTotal);
        return regressions;
    }

    private static Map<String,JsonNode> byFile(Path json) throws IOException
    {
        Map<String,JsonNode> map = new LinkedHashMap<>();
        for(JsonNode node : MAPPER.readTree(json.toFile()))
        {
            map.put(node.get("file").asText(), node);
        }
        return map;
    }

    /** Parses "<file> <sync> <valid> <op0/1> <bursts>" rows out of an MDCBatchEvaluator report. */
    private static Map<String,Integer> mdcValidCounts(Path report) throws IOException
    {
        Pattern row = Pattern.compile("^(\\S+\\.wav)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
        Map<String,Integer> map = new LinkedHashMap<>();
        for(String line : Files.readAllLines(report))
        {
            Matcher m = row.matcher(line);
            if(m.find())
            {
                map.put(m.group(1), Integer.parseInt(m.group(3)));
            }
        }
        return map;
    }

    private static String argValue(String[] args, String name)
    {
        for(int i = 0; i < args.length - 1; i++)
        {
            if(args[i].equals(name)) return args[i + 1];
        }
        return null;
    }
}
