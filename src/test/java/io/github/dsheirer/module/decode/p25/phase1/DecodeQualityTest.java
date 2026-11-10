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

import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3AudioConverter;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.IMBEFrameDiagnostic;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import org.jtransforms.fft.FloatFFT_1D;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Unified decode quality test. Reads a playlist XML to determine the correct decoder
 * per baseband file, runs the decode, and outputs metrics as JSON.
 *
 * Usage:
 *   java DecodeQualityTest --samples dir --playlist xml --output dir [--jmbe jar] [--mode quick|full]
 */
public class DecodeQualityTest
{
    record ChannelConfig(String name, String system, String site, long frequency, String modulation, int nac, String preferredTuner) {}
    record DecodeResult(int lduCount, int validMessages, int totalMessages, int syncBlockedCount, int bitErrors, int syncLosses, double signalSeconds, double totalFileSeconds, IMBEDiagSummary diagSummary) {}
    record IMBEDiagSummary(int totalFrames, int totalErrors, int uncorrectableFrames, int invalidFundamentals,
                           double avgErrorsPerFrame, int maxFrameErrors, int[] errorDistribution) {}
    record AudioResult(int segmentCount, double totalSeconds, double silenceSeconds, double silencePercentage,
                       int silenceRegionCount, int artifactFrames, double artifactSeconds, double artifactPercentage,
                       int speechFrames, double speechPercentage, double qualityScore) {}

    /** Wraps any P25P1 decoder to provide a uniform interface. */
    interface DecoderWrapper
    {
        void setMessageListener(Listener<IMessage> listener);
        void start();
        void stop();
        void setSampleRate(double rate);
        void receive(ComplexSamples samples);
        int getSyncBlockedCount();
        default String getFramerDiagnosticJson() { return ""; }
    }

    public static void main(String[] args)
    {
        String samplesDir = null, playlistPath = null, outputDir = null, jmbePath = null, mode = "quick";
        String forceMod = null;
        int forceNac = -1;
        boolean diagEnabled = false;
        int maxBchErrors = 11; // Default: no filtering
        int maxImbeErrors = -1; // Default: no quality gate (-1 = disabled)
        int segmentGapMs = 500; // Default: segment on 500ms gaps
        float silenceThreshold = 0.01f; // RMS threshold for decode-failure silence
        int silenceMinMs = 100; // Minimum silence region duration in ms
        float cmaAcqMu = 0.0f; // CMA acquisition mu (0 = system property fallback)
        float cmaTrkMu = 0.0f; // CMA tracking mu (0 = system property fallback)
        int cmaShiftMs = 0;    // CMA gear-shift timing in ms (0 = system property fallback)
        boolean verbose = false; // Print every message with full toString()

        for(int i = 0; i < args.length; i++)
        {
            switch(args[i])
            {
                case "--samples" -> samplesDir = args[++i];
                case "--playlist" -> playlistPath = args[++i];
                case "--output" -> outputDir = args[++i];
                case "--jmbe" -> jmbePath = args[++i];
                case "--mode" -> mode = args[++i];
                case "--force-mod" -> forceMod = args[++i];
                case "--force-nac" -> forceNac = Integer.parseInt(args[++i]);
                case "--diag" -> diagEnabled = true;
                case "--verbose" -> verbose = true;
                case "--max-bch-errors" -> maxBchErrors = Integer.parseInt(args[++i]);
                case "--max-imbe-errors" -> maxImbeErrors = Integer.parseInt(args[++i]);
                case "--segment-gap" -> segmentGapMs = Integer.parseInt(args[++i]);
                case "--silence-threshold" -> silenceThreshold = Float.parseFloat(args[++i]);
                case "--silence-min-ms" -> silenceMinMs = Integer.parseInt(args[++i]);
                case "--cma-acq-mu" -> cmaAcqMu = Float.parseFloat(args[++i]);
                case "--cma-trk-mu" -> cmaTrkMu = Float.parseFloat(args[++i]);
                case "--cma-shift-ms" -> cmaShiftMs = Integer.parseInt(args[++i]);
            }
        }

        if(samplesDir == null || playlistPath == null || outputDir == null)
        {
            System.out.println("Usage: DecodeQualityTest --samples <dir> --playlist <xml> --output <dir> [--jmbe <jar>] [--mode quick|full]");
            System.exit(1);
            return;
        }

        boolean fullMode = "full".equals(mode);
        boolean diagnosticMode = "diagnostic".equals(mode);

        TestJmbeCodecLoader codec = null;
        if(fullMode && jmbePath != null)
        {
            codec = new TestJmbeCodecLoader(Paths.get(jmbePath));
            if(!codec.isLoaded()) { System.err.println("WARNING: JMBE load failed: " + codec.getLoadError()); codec = null; }
        }

        List<ChannelConfig> channels = parsePlaylist(new File(playlistPath));
        if(channels.isEmpty()) { System.err.println("ERROR: No channels in playlist"); System.exit(1); return; }
        System.out.printf("Loaded %d channels from playlist%n", channels.size());

        List<File> basebandFiles = findBasebandFiles(new File(samplesDir));
        if(basebandFiles.isEmpty()) { System.err.println("ERROR: No baseband files found"); System.exit(1); return; }
        System.out.printf("Found %d baseband files%n", basebandFiles.size());

        Path outPath = Paths.get(outputDir);
        try { Files.createDirectories(outPath); } catch(IOException e) { System.exit(1); return; }

        StringBuilder json = new StringBuilder("[\n");
        boolean first = true;

        for(File bbFile : basebandFiles)
        {
            long freq = extractFrequency(bbFile.getName());
            ChannelConfig config = findChannel(channels, freq);

            String channelName = config != null ? config.name() : "Unknown";
            String modulation = forceMod != null ? forceMod : (config != null ? config.modulation() : "C4FM");
            int nac = forceNac >= 0 ? forceNac : (config != null ? config.nac() : 0);
            String tuner = config != null ? config.preferredTuner() : "N/A";
            String system = config != null ? config.system() : "";
            String site = config != null ? config.site() : "";
            boolean isFD = channelName.toLowerCase().contains("fire") || channelName.toLowerCase().contains(" fd");

            System.out.printf("%n━━━ %s ━━━%n", bbFile.getName());
            System.out.printf("  Channel: %s | Mod: %s | NAC: %d | Tuner: %s%n", channelName, modulation, nac, tuner);

            String[] framerDiagHolder = diagnosticMode ? new String[]{""} : null;
            DecodeResult result = runDecode(bbFile, modulation, nac, diagEnabled || diagnosticMode, maxBchErrors, cmaAcqMu, cmaTrkMu, cmaShiftMs, framerDiagHolder, verbose);

            int audioSegments = 0;
            double audioSeconds = 0;
            double silenceSeconds = 0;
            double silencePercentage = 0;
            int silenceRegionCount = 0;
            int artifactFrames = 0;
            double artifactSeconds = 0;
            double artifactPercentage = 0;
            int speechFrames = 0;
            double speechPercentage = 0;
            double qualityScore = 0;
            if(fullMode && codec != null)
            {
                Path audioDir = outPath.resolve(sanitize(bbFile.getName()));
                try { Files.createDirectories(audioDir); } catch(IOException e) { /* ignore */ }
                AudioResult ar = decodeAudio(bbFile, modulation, nac, codec, audioDir, maxBchErrors, maxImbeErrors, segmentGapMs, silenceThreshold, silenceMinMs, cmaAcqMu, cmaTrkMu, cmaShiftMs);
                audioSegments = ar.segmentCount;
                audioSeconds = ar.totalSeconds;
                silenceSeconds = ar.silenceSeconds;
                silencePercentage = ar.silencePercentage;
                silenceRegionCount = ar.silenceRegionCount;
                artifactFrames = ar.artifactFrames;
                artifactSeconds = ar.artifactSeconds;
                artifactPercentage = ar.artifactPercentage;
                speechFrames = ar.speechFrames;
                speechPercentage = ar.speechPercentage;
                qualityScore = ar.qualityScore;
                System.out.printf("  Audio: %.1fs in %d segments (avg %.1fs)%n",
                    audioSeconds, audioSegments, audioSegments > 0 ? audioSeconds / audioSegments : 0);
                System.out.printf("  Silence: %.1fs (%.1f%%) in %d regions%n",
                    silenceSeconds, silencePercentage, silenceRegionCount);
                System.out.printf("  Waveform: %d speech, %d artifact (%.1f%%), quality=%.3f%n",
                    speechFrames, artifactFrames, artifactPercentage, qualityScore);
            }

            double decodeSeconds = result.lduCount * 0.18; // ~180ms per LDU
            double decodeRatio = result.signalSeconds > 0 ? (decodeSeconds / result.signalSeconds) * 100 : 0;
            System.out.printf("  LDUs: %d | Valid: %d/%d | Sync Blocked: %d%n",
                result.lduCount, result.validMessages, result.totalMessages, result.syncBlockedCount);
            System.out.printf("  Signal: %.1fs / %.1fs file | Decode ratio: %.1f%%%n",
                result.signalSeconds, result.totalFileSeconds, decodeRatio);
            if(diagnosticMode && framerDiagHolder != null)
            {
                System.out.printf("  Framer: %s%n", framerDiagHolder[0].replace("\"", "").replace(",", " |"));
            }

            // Print IMBE diagnostic summary if available
            IMBEDiagSummary diag = result.diagSummary;
            if(diag != null && diag.totalFrames > 0)
            {
                System.out.printf("  IMBE Diag: %d frames, avg %.1f errors/frame, %d uncorrectable (%.1f%%), %d invalid fund%n",
                    diag.totalFrames, diag.avgErrorsPerFrame, diag.uncorrectableFrames,
                    100.0 * diag.uncorrectableFrames / diag.totalFrames, diag.invalidFundamentals);
                System.out.printf("  Error dist [0,1,2,3,4+]: %d, %d, %d, %d, %d%n",
                    diag.errorDistribution[0], diag.errorDistribution[1],
                    diag.errorDistribution[2], diag.errorDistribution[3], diag.errorDistribution[4]);
            }

            if(!first) json.append(",\n");
            first = false;

            String diagJson = "";
            if(diag != null)
            {
                diagJson = String.format(
                    ", \"diag_total_frames\": %d, \"diag_total_errors\": %d, \"diag_uncorrectable_frames\": %d, " +
                    "\"diag_invalid_fundamentals\": %d, \"diag_avg_errors_per_frame\": %.2f, \"diag_max_frame_errors\": %d, " +
                    "\"diag_error_dist\": [%d, %d, %d, %d, %d]",
                    diag.totalFrames, diag.totalErrors, diag.uncorrectableFrames,
                    diag.invalidFundamentals, diag.avgErrorsPerFrame, diag.maxFrameErrors,
                    diag.errorDistribution[0], diag.errorDistribution[1],
                    diag.errorDistribution[2], diag.errorDistribution[3], diag.errorDistribution[4]);
            }

            json.append(String.format(
                "  {\"file\": \"%s\", \"channel\": \"%s\", \"system\": \"%s\", \"site\": \"%s\", " +
                "\"modulation\": \"%s\", \"nac\": %d, \"tuner\": \"%s\", \"is_fd\": %s, " +
                "\"ldu_count\": %d, \"valid_messages\": %d, \"total_messages\": %d, \"sync_blocked\": %d, " +
                "\"bit_errors\": %d, \"sync_losses\": %d, \"audio_seconds\": %.2f, \"audio_segments\": %d, " +
                "\"silence_seconds\": %.2f, \"silence_percentage\": %.2f, \"silence_region_count\": %d, " +
                "\"artifact_frames\": %d, \"artifact_seconds\": %.2f, \"artifact_percentage\": %.2f, " +
                "\"speech_frames\": %d, \"speech_percentage\": %.2f, \"quality_score\": %.4f, " +
                "\"signal_seconds\": %.2f, \"total_file_seconds\": %.2f, \"decode_ratio\": %.2f%s%s}",
                escapeJson(bbFile.getName()), escapeJson(channelName), escapeJson(system), escapeJson(site),
                modulation, nac, escapeJson(tuner), isFD,
                result.lduCount, result.validMessages, result.totalMessages, result.syncBlockedCount,
                result.bitErrors, result.syncLosses, audioSeconds, audioSegments,
                silenceSeconds, silencePercentage, silenceRegionCount,
                artifactFrames, artifactSeconds, artifactPercentage,
                speechFrames, speechPercentage, qualityScore,
                result.signalSeconds, result.totalFileSeconds, decodeRatio, diagJson,
                framerDiagHolder != null ? framerDiagHolder[0] : ""));
        }

        json.append("\n]\n");

        Path metricsFile = outPath.resolve("metrics.json");
        try(FileWriter fw = new FileWriter(metricsFile.toFile()))
        {
            fw.write(json.toString());
            System.out.printf("%n✓ Metrics written to: %s%n", metricsFile);
        }
        catch(IOException e) { System.err.println("ERROR writing metrics: " + e.getMessage()); }
    }

    private static DecoderWrapper createDecoder(String modulation, int nac, int maxBchErrors, float cmaAcqMu, float cmaTrkMu, int cmaShiftMs)
    {
        return switch(modulation.toUpperCase())
        {
            case "CQPSK" ->
            {
                P25P1DecoderLSM d = new P25P1DecoderLSM();
                yield new DecoderWrapper()
                {
                    public void setMessageListener(Listener<IMessage> l) { d.setMessageListener(l); }
                    public void start() { d.start(); }
                    public void stop() { d.stop(); }
                    public void setSampleRate(double r) { d.setSampleRate(r); }
                    public void receive(ComplexSamples s) { d.receive(s); }
                    public int getSyncBlockedCount() { return extractSyncBlockedReflection(d); }
                };
            }
            case "CQPSK_V2" ->
            {
                P25P1DecoderLSMv2 d = new P25P1DecoderLSMv2();
                if(nac > 0) d.setConfiguredNAC(nac);
                if(maxBchErrors < 11) d.getMessageFramer().setMaxBchErrors(maxBchErrors);
                if(cmaAcqMu > 0 || cmaTrkMu > 0 || cmaShiftMs > 0) d.setCMAConfig(cmaAcqMu, cmaTrkMu, cmaShiftMs);
                yield new DecoderWrapper()
                {
                    public void setMessageListener(Listener<IMessage> l) { d.setMessageListener(l); }
                    public void start() { d.start(); }
                    public void stop() { d.stop(); }
                    public void setSampleRate(double r) { d.setSampleRate(r); }
                    public void receive(ComplexSamples s) { d.receive(s); }
                    public int getSyncBlockedCount() { return d.getMessageFramer().getSyncBlockedCount(); }
                };
            }
            case "C4FM_V2" ->
            {
                P25P1DecoderC4FMv2 d = new P25P1DecoderC4FMv2();
                if(nac > 0) d.setConfiguredNAC(nac);
                yield new DecoderWrapper()
                {
                    public void setMessageListener(Listener<IMessage> l) { d.setMessageListener(l); }
                    public void start() { d.start(); }
                    public void stop() { d.stop(); }
                    public void setSampleRate(double r) { d.setSampleRate(r); }
                    public void receive(ComplexSamples s) { d.receive(s); }
                    public int getSyncBlockedCount() { return extractSyncBlockedReflection(d); }
                };
            }
            default ->
            {
                P25P1DecoderC4FM d = new P25P1DecoderC4FM();
                if(nac > 0) d.setConfiguredNAC(nac);
                yield new DecoderWrapper()
                {
                    public void setMessageListener(Listener<IMessage> l) { d.setMessageListener(l); }
                    public void start() { d.start(); }
                    public void stop() { d.stop(); }
                    public void setSampleRate(double r) { d.setSampleRate(r); }
                    public void receive(ComplexSamples s) { d.receive(s); }
                    public int getSyncBlockedCount() { return extractSyncBlockedReflection(d); }
                    public String getFramerDiagnosticJson()
                    {
                        var framer = d.getMessageFramer();
                        return String.format(
                            ", \"framer_sync_detections\": %d, \"framer_nid_success\": %d, \"framer_nid_fail\": %d" +
                            ", \"framer_sync_blocked\": %d, \"framer_fallback_sync\": %d, \"framer_recovery_sync\": %d" +
                            ", \"framer_initial_acq_sync\": %d, \"framer_flywheel_attempts\": %d" +
                            ", \"framer_flywheel_success\": %d, \"framer_flywheel_miss\": %d",
                            framer.getSyncDetectionCount(), framer.getNIDDecodeSuccessCount(), framer.getNIDDecodeFailCount(),
                            framer.getSyncBlockedCount(), framer.getFallbackSyncCount(), framer.getRecoverySyncCount(),
                            framer.getInitialAcquisitionSyncCount(), framer.getFlywheelAttemptCount(),
                            framer.getFlywheelSuccessCount(), framer.getFlywheelMissCount());
                    }
                };
            }
        };
    }

    private static DecodeResult runDecode(File file, String modulation, int nac, boolean diagEnabled, int maxBchErrors, float cmaAcqMu, float cmaTrkMu, int cmaShiftMs)
    {
        return runDecode(file, modulation, nac, diagEnabled, maxBchErrors, cmaAcqMu, cmaTrkMu, cmaShiftMs, null, false);
    }

    private static DecodeResult runDecode(File file, String modulation, int nac, boolean diagEnabled, int maxBchErrors, float cmaAcqMu, float cmaTrkMu, int cmaShiftMs, String[] framerDiagHolder, boolean verbose)
    {
        int[] ldu = {0}, valid = {0}, total = {0}, bitErr = {0}, syncLoss = {0};
        double[] signalSeconds = {0}, totalFileSeconds = {0};
        Map<String, int[]> duidCounts = new HashMap<>();
        Map<String, int[]> nacCounts = new HashMap<>();

        // IMBE diagnostic accumulators
        int[] diagTotalFrames = {0}, diagTotalErrors = {0}, diagUncorrectable = {0};
        int[] diagInvalidFund = {0}, diagMaxFrameErrors = {0};
        // Error distribution: [0]=0 errors, [1]=1-3 errors, [2]=4-6 errors, [3]=7-10 errors, [4]=11+ errors
        int[] diagErrorDist = new int[5];

        // LCW/ESP validity tracking — measures whether the live DecoderState would produce CALL events
        int[] ldu1Total = {0}, ldu1ValidLcw = {0}, ldu2Total = {0}, ldu2ValidEsp = {0};
        int[] hduTotal = {0}, hduValidHeader = {0};

        Listener<IMessage> listener = msg -> {
            if(msg instanceof SyncLossMessage) { syncLoss[0]++; return; }
            if(msg instanceof P25P1Message p)
            {
                if(verbose)
                {
                    System.out.printf("  [%d] %s%n", total[0] + 1, p);
                }
                total[0]++;
                var duid = p.getDUID();
                duidCounts.computeIfAbsent(duid.name(), k -> new int[]{0, 0});
                duidCounts.get(duid.name())[0]++;
                if(p.isValid())
                {
                    valid[0]++;
                    duidCounts.get(duid.name())[1]++;
                    if(duid == P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1 ||
                       duid == P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2)
                    {
                        ldu[0]++;

                        // Run IMBE diagnostic on valid LDUs
                        if(diagEnabled && msg instanceof LDUMessage lduMsg)
                        {
                            IMBEFrameDiagnostic.LDUErrors lduErrors = IMBEFrameDiagnostic.analyzeLDU(lduMsg);
                            for(IMBEFrameDiagnostic.FrameErrors fe : lduErrors.frameErrors())
                            {
                                diagTotalFrames[0]++;
                                diagTotalErrors[0] += fe.totalErrors();
                                if(fe.uncorrectableCount() > 0) diagUncorrectable[0]++;
                                if(!fe.fundamentalValid()) diagInvalidFund[0]++;
                                if(fe.totalErrors() > diagMaxFrameErrors[0]) diagMaxFrameErrors[0] = fe.totalErrors();

                                int te = fe.totalErrors();
                                if(te == 0) diagErrorDist[0]++;
                                else if(te <= 3) diagErrorDist[1]++;
                                else if(te <= 6) diagErrorDist[2]++;
                                else if(te <= 10) diagErrorDist[3]++;
                                else diagErrorDist[4]++;
                            }
                        }
                    }
                }
                // Track LCW/ESP validity for live decoder state analysis
                if(p instanceof LDU1Message ldu1Msg)
                {
                    ldu1Total[0]++;
                    if(ldu1Msg.getLinkControlWord() != null && ldu1Msg.getLinkControlWord().isValid())
                        ldu1ValidLcw[0]++;
                }
                else if(p instanceof LDU2Message ldu2Msg)
                {
                    ldu2Total[0]++;
                    if(ldu2Msg.getEncryptionSyncParameters() != null && ldu2Msg.getEncryptionSyncParameters().isValid())
                        ldu2ValidEsp[0]++;
                }
                else if(p instanceof HDUMessage hduMsg)
                {
                    hduTotal[0]++;
                    if(hduMsg.getHeaderData() != null && hduMsg.getHeaderData().isValid())
                        hduValidHeader[0]++;
                }

                String nacStr = String.valueOf(p.getNAC());
                nacCounts.computeIfAbsent(nacStr, k -> new int[]{0})[0]++;
                if(p.getMessage() != null) bitErr[0] += p.getMessage().getCorrectedBitCount();
            }
        };

        int syncBlocked = 0;
        try(TestComplexWaveSource source = new TestComplexWaveSource(file))
        {
            double sampleRate = source.getSampleRate();
            long[] totalSamples = {0};
            List<Float> bufferRmsValues = new ArrayList<>();
            List<Integer> bufferSizes = new ArrayList<>();

            DecoderWrapper decoder = createDecoder(modulation, nac, maxBchErrors, cmaAcqMu, cmaTrkMu, cmaShiftMs);
            decoder.setMessageListener(listener);
            decoder.start();
            source.setListener(buf -> {
                var it = buf.iterator();
                while(it.hasNext())
                {
                    ComplexSamples cs = it.next();
                    decoder.receive(cs);

                    float[] iSamples = cs.i();
                    float[] qSamples = cs.q();
                    int len = Math.min(iSamples.length, qSamples.length);
                    totalSamples[0] += len;

                    float sumSq = 0;
                    for(int s = 0; s < len; s++)
                    {
                        sumSq += iSamples[s] * iSamples[s] + qSamples[s] * qSamples[s];
                    }
                    float rms = (float)Math.sqrt(sumSq / len);
                    bufferRmsValues.add(rms);
                    bufferSizes.add(len);
                }
            });
            decoder.setSampleRate(sampleRate);
            while(source.next(2048)) {}
            syncBlocked = decoder.getSyncBlockedCount();
            if(framerDiagHolder != null && framerDiagHolder.length > 0)
            {
                framerDiagHolder[0] = decoder.getFramerDiagnosticJson();
            }
            decoder.stop();

            totalFileSeconds[0] = totalSamples[0] / sampleRate;

            // Adaptive signal detection: compute threshold from distribution of RMS values
            signalSeconds[0] = computeSignalSeconds(bufferRmsValues, bufferSizes, sampleRate);
        }
        catch(Exception e) { System.err.println("  ERROR: " + e.getMessage()); }

        // Print DUID breakdown
        if(!duidCounts.isEmpty())
        {
            System.out.print("  DUIDs: ");
            duidCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> System.out.printf("%s=%d(%dv) ", e.getKey(), e.getValue()[0], e.getValue()[1]));
            System.out.println();
        }
        if(!nacCounts.isEmpty())
        {
            System.out.print("  NACs observed: ");
            nacCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> System.out.printf("%s(×%d) ", e.getKey(), e.getValue()[0]));
            System.out.println();
        }

        // Print LCW/ESP validity — shows whether live DecoderState would produce CALL events
        if(ldu1Total[0] > 0 || ldu2Total[0] > 0 || hduTotal[0] > 0)
        {
            System.out.printf("  Live decode: HDU %d/%d valid | LDU1-LCW %d/%d valid (%.0f%%) | LDU2-ESP %d/%d valid (%.0f%%)%n",
                hduValidHeader[0], hduTotal[0],
                ldu1ValidLcw[0], ldu1Total[0], ldu1Total[0] > 0 ? 100.0 * ldu1ValidLcw[0] / ldu1Total[0] : 0,
                ldu2ValidEsp[0], ldu2Total[0], ldu2Total[0] > 0 ? 100.0 * ldu2ValidEsp[0] / ldu2Total[0] : 0);
            int totalCallable = hduValidHeader[0] + ldu1ValidLcw[0] + ldu2ValidEsp[0];
            if(totalCallable == 0 && (ldu1Total[0] + ldu2Total[0]) > 0)
            {
                System.out.println("  *** WARNING: Zero valid LCW/ESP — live decoder would produce NO calls ***");
            }
        }

        IMBEDiagSummary diagSummary = null;
        if(diagEnabled && diagTotalFrames[0] > 0)
        {
            diagSummary = new IMBEDiagSummary(diagTotalFrames[0], diagTotalErrors[0], diagUncorrectable[0],
                diagInvalidFund[0], (double)diagTotalErrors[0] / diagTotalFrames[0], diagMaxFrameErrors[0], diagErrorDist);
        }

        return new DecodeResult(ldu[0], valid[0], total[0], syncBlocked, bitErr[0], syncLoss[0], signalSeconds[0], totalFileSeconds[0], diagSummary);
    }

    /**
     * Computes signal-present seconds using adaptive thresholding on per-buffer RMS values.
     * Uses percentile-based separation: estimates noise floor (10th pct) and signal level (90th pct),
     * then places threshold at geometric mean. Falls back to max*0.3 for unimodal distributions.
     */
    private static double computeSignalSeconds(List<Float> rmsValues, List<Integer> sizes, double sampleRate)
    {
        if(rmsValues.isEmpty()) return 0;

        List<Float> sorted = new ArrayList<>(rmsValues);
        Collections.sort(sorted);
        int n = sorted.size();

        float p10 = sorted.get(n / 10);
        float p90 = sorted.get(Math.min(9 * n / 10, n - 1));
        float maxRms = sorted.get(n - 1);

        float threshold;
        if(maxRms < 0.0001f)
        {
            // Essentially no energy at all
            return 0;
        }
        else if(p90 < p10 * 2.0f)
        {
            // Unimodal distribution — can't reliably separate signal from noise
            // Use 30% of max as threshold (generous for all-signal files)
            threshold = maxRms * 0.3f;
        }
        else
        {
            // Bimodal — geometric mean of noise and signal estimates
            threshold = (float)Math.sqrt(p10 * p90);
            // Ensure threshold is at least slightly above noise
            threshold = Math.max(threshold, p10 * 1.5f);
        }

        long signalSamples = 0;
        for(int i = 0; i < rmsValues.size(); i++)
        {
            if(rmsValues.get(i) > threshold) signalSamples += sizes.get(i);
        }

        return signalSamples / sampleRate;
    }

    private static AudioResult decodeAudio(File file, String modulation, int nac, TestJmbeCodecLoader codec, Path audioDir, int maxBchErrors, int maxImbeErrors, int segmentGapMs, float silenceThreshold, int silenceMinMs, float cmaAcqMu, float cmaTrkMu, int cmaShiftMs)
    {
        List<float[]> audioBuffers = new ArrayList<>();
        List<Long> lduTimestamps = new ArrayList<>();
        int[] gatedFrames = {0};
        float[][] lastGoodFrame = {null};
        int[] consecutiveGated = {0};
        int fadeStart = 9, fadeLength = 9;
        int codecResetThreshold = 5;
        // Adaptive gate state
        int adaptiveWindow = 27;
        int[] adaptiveIdx = {0};
        int[] adaptivePass = {0};
        boolean[] adaptiveDisabled = {false};

        Listener<IMessage> listener = msg -> {
            if(msg instanceof LDUMessage ldu && ((P25P1Message)msg).isValid())
            {
                lduTimestamps.add(((P25P1Message)msg).getTimestamp());
                for(byte[] frame : ldu.getIMBEFrames())
                {
                    // Pre-codec quality gate with adaptive disable
                    if(maxImbeErrors >= 0)
                    {
                        IMBEFrameDiagnostic.FrameErrors fe = IMBEFrameDiagnostic.analyzeFrame(frame);
                        boolean exceedsThreshold = fe.totalErrors() > maxImbeErrors;

                        // Update adaptive gate
                        adaptiveIdx[0]++;
                        if(!exceedsThreshold) adaptivePass[0]++;
                        if(adaptiveIdx[0] >= adaptiveWindow)
                        {
                            float passRate = (float)adaptivePass[0] / adaptiveIdx[0];
                            if(adaptiveDisabled[0] && passRate >= 0.50f) adaptiveDisabled[0] = false;
                            else if(!adaptiveDisabled[0] && passRate < 0.30f) adaptiveDisabled[0] = true;
                            adaptiveIdx[0] = 0;
                            adaptivePass[0] = 0;
                        }

                        if(exceedsThreshold && !adaptiveDisabled[0])
                        {
                            gatedFrames[0]++;
                            consecutiveGated[0]++;

                            // Reset codec after sustained corruption
                            if(consecutiveGated[0] == codecResetThreshold)
                            {
                                codec.reset();
                            }

                            // Repeat last good frame with fade-out
                            if(lastGoodFrame[0] != null && consecutiveGated[0] <= fadeStart + fadeLength)
                            {
                                float[] repeated = lastGoodFrame[0].clone();
                                if(consecutiveGated[0] > fadeStart)
                                {
                                    float fade = 1.0f - (float)(consecutiveGated[0] - fadeStart) / fadeLength;
                                    for(int s = 0; s < repeated.length; s++)
                                    {
                                        repeated[s] *= fade;
                                    }
                                }
                                audioBuffers.add(repeated);
                            }
                            else
                            {
                                audioBuffers.add(new float[160]);
                            }
                            continue;
                        }
                    }

                    consecutiveGated[0] = 0;
                    float[] audio = codec.decodeFrame(frame);
                    if(audio != null && audio.length > 0)
                    {
                        for(int s = 0; s < audio.length; s++)
                        {
                            float a = audio[s] * 5.0f;
                            audio[s] = Math.max(-0.95f, Math.min(0.95f, a));
                        }
                        lastGoodFrame[0] = audio.clone();
                        audioBuffers.add(audio);
                    }
                }
            }
        };

        try(TestComplexWaveSource source = new TestComplexWaveSource(file))
        {
            DecoderWrapper decoder = createDecoder(modulation, nac, maxBchErrors, cmaAcqMu, cmaTrkMu, cmaShiftMs);
            decoder.setMessageListener(listener);
            decoder.start();
            source.setListener(buf -> { var it = buf.iterator(); while(it.hasNext()) decoder.receive(it.next()); });
            decoder.setSampleRate(source.getSampleRate());
            while(source.next(2048)) {}
            decoder.stop();
        }
        catch(Exception e) { System.err.println("  AUDIO ERROR: " + e.getMessage()); }

        if(audioBuffers.isEmpty()) return new AudioResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        // Silence detection: scan audio frames for decode-failure silence
        int minSilenceFrames = Math.max(1, silenceMinMs / 20); // 20ms per IMBE frame
        int consecutiveSilent = 0;
        int totalSilentFrames = 0;
        int silRegionCount = 0;

        for(float[] frame : audioBuffers)
        {
            float sumSq = 0;
            for(float sample : frame)
            {
                sumSq += sample * sample;
            }
            float rms = (float)Math.sqrt(sumSq / frame.length);

            if(rms < silenceThreshold)
            {
                consecutiveSilent++;
            }
            else
            {
                if(consecutiveSilent >= minSilenceFrames)
                {
                    totalSilentFrames += consecutiveSilent;
                    silRegionCount++;
                }
                consecutiveSilent = 0;
            }
        }
        // Handle trailing silence region
        if(consecutiveSilent >= minSilenceFrames)
        {
            totalSilentFrames += consecutiveSilent;
            silRegionCount++;
        }

        double totalAudioSec = audioBuffers.size() * 160.0 / 8000.0;
        double silSec = totalSilentFrames * 160.0 / 8000.0;
        double silPct = totalAudioSec > 0 ? (silSec / totalAudioSec) * 100.0 : 0;

        // Segment by LDU gaps exceeding the configured threshold
        List<List<float[]>> segments = new ArrayList<>();
        List<float[]> current = new ArrayList<>();
        int framesPerLdu = 9;
        int bufIdx = 0;

        for(int i = 0; i < lduTimestamps.size(); i++)
        {
            if(i > 0 && (lduTimestamps.get(i) - lduTimestamps.get(i - 1)) > segmentGapMs)
            {
                if(!current.isEmpty()) { segments.add(current); current = new ArrayList<>(); }
            }
            for(int f = 0; f < framesPerLdu && bufIdx < audioBuffers.size(); f++, bufIdx++)
            {
                current.add(audioBuffers.get(bufIdx));
            }
        }
        if(!current.isEmpty()) segments.add(current);

        double totalSeconds = 0;
        for(int s = 0; s < segments.size(); s++)
        {
            double segSeconds = segments.get(s).size() * 160.0 / 8000.0;
            totalSeconds += segSeconds;
            writeMP3(segments.get(s), audioDir.resolve(String.format("segment_%03d.mp3", s + 1)));
        }

        // Waveform quality analysis (spectral)
        WaveformAnalysis waveform = analyzeWaveformQuality(audioBuffers, silenceThreshold);
        double totalFrames = audioBuffers.size();
        double artifactSec = waveform.artifactFrames() * 160.0 / 8000.0;
        double artifactPct = totalFrames > 0 ? (waveform.artifactFrames() / totalFrames) * 100.0 : 0;
        double speechPct = totalFrames > 0 ? (waveform.speechFrames() / totalFrames) * 100.0 : 0;

        return new AudioResult(segments.size(), totalSeconds, silSec, silPct, silRegionCount,
            waveform.artifactFrames(), artifactSec, artifactPct,
            waveform.speechFrames(), speechPct, waveform.qualityScore());
    }

    enum FrameClass { SILENCE, ARTIFACT, SPEECH }

    record WaveformAnalysis(int silenceFrames, int artifactFrames, int speechFrames, double qualityScore) {}

    /**
     * Analyzes decoded audio frames for silence, artifacts (squeals/tones), and speech using FFT-based spectral analysis.
     * Each 160-sample frame (20ms at 8kHz) is zero-padded to 256 for radix-2 FFT.
     *
     * Classification per frame:
     *   SILENCE:  RMS below threshold
     *   ARTIFACT: high spectral centroid (>2500Hz) with low flatness (<0.05), or centroid >3000Hz,
     *             or very low flatness (<0.02) with low crest factor (<2.5) indicating pure tone
     *   SPEECH:   everything else
     */
    private static WaveformAnalysis analyzeWaveformQuality(List<float[]> audioBuffers, float silenceThreshold)
    {
        if(audioBuffers.isEmpty()) return new WaveformAnalysis(0, 0, 0, 1.0);

        int fftSize = 256;
        FloatFFT_1D fft = new FloatFFT_1D(fftSize);
        float sampleRate = 8000.0f;
        float binWidth = sampleRate / fftSize; // 31.25 Hz/bin

        int silenceCount = 0, artifactCount = 0, speechCount = 0;

        for(float[] frame : audioBuffers)
        {
            // Compute RMS
            float sumSq = 0;
            for(float sample : frame)
            {
                sumSq += sample * sample;
            }
            float rms = (float)Math.sqrt(sumSq / frame.length);

            if(rms < silenceThreshold)
            {
                silenceCount++;
                continue;
            }

            // Zero-pad to FFT size
            float[] fftBuf = new float[fftSize];
            System.arraycopy(frame, 0, fftBuf, 0, Math.min(frame.length, fftSize));
            fft.realForward(fftBuf);

            // Compute power spectrum (skip DC bin)
            int numBins = fftSize / 2;
            float[] power = new float[numBins];
            for(int k = 1; k < numBins; k++)
            {
                float re = fftBuf[2 * k];
                float im = fftBuf[2 * k + 1];
                power[k] = re * re + im * im;
            }

            // Spectral centroid: sum(f_k * P_k) / sum(P_k)
            float sumFP = 0, sumP = 0;
            for(int k = 1; k < numBins; k++)
            {
                float freq = k * binWidth;
                sumFP += freq * power[k];
                sumP += power[k];
            }
            float centroid = sumP > 0 ? sumFP / sumP : 0;

            // Spectral flatness: geomean(P_k) / mean(P_k)
            // Use log-domain for geometric mean to avoid overflow/underflow
            double logSum = 0;
            int nonZeroBins = 0;
            for(int k = 1; k < numBins; k++)
            {
                if(power[k] > 0)
                {
                    logSum += Math.log(power[k]);
                    nonZeroBins++;
                }
            }
            float flatness;
            if(nonZeroBins < numBins / 2)
            {
                flatness = 0; // Too many zero bins — treat as tonal
            }
            else
            {
                double geoMean = Math.exp(logSum / nonZeroBins);
                double ariMean = sumP / (numBins - 1);
                flatness = ariMean > 0 ? (float)(geoMean / ariMean) : 0;
            }

            // Crest factor: peak / RMS
            float peak = 0;
            for(float sample : frame)
            {
                float abs = Math.abs(sample);
                if(abs > peak) peak = abs;
            }
            float crest = rms > 0 ? peak / rms : 0;

            // Classify frame
            boolean highCentroid = centroid > 2500 && flatness < 0.05f;
            boolean veryHighCentroid = centroid > 3000;
            boolean pureTone = flatness < 0.02f && crest < 2.5f;

            if(highCentroid || veryHighCentroid || pureTone)
            {
                artifactCount++;
            }
            else
            {
                speechCount++;
            }
        }

        int total = Math.max(1, silenceCount + artifactCount + speechCount);
        double quality = (double)speechCount / total;

        return new WaveformAnalysis(silenceCount, artifactCount, speechCount, quality);
    }

    private static void writeMP3(List<float[]> audioBuffers, Path mp3File)
    {
        try(FileOutputStream fos = new FileOutputStream(mp3File.toFile()))
        {
            MP3AudioConverter converter = new MP3AudioConverter(InputAudioFormat.SR_8000, MP3Setting.CBR_16, true);
            List<byte[]> mp3Chunks = converter.convert(audioBuffers);
            for(byte[] chunk : mp3Chunks)
            {
                if(chunk != null && chunk.length > 0) fos.write(chunk);
            }
            List<byte[]> flushChunks = converter.flush();
            for(byte[] chunk : flushChunks)
            {
                if(chunk != null && chunk.length > 0) fos.write(chunk);
            }
        }
        catch(IOException e) { System.err.println("  MP3 write error: " + e.getMessage()); }
    }

    private static int extractSyncBlockedReflection(Object decoder)
    {
        try
        {
            Class<?> clazz = decoder.getClass();
            while(clazz != null)
            {
                try
                {
                    Field f = clazz.getDeclaredField("mMessageFramer");
                    f.setAccessible(true);
                    Object framer = f.get(decoder);
                    if(framer instanceof P25P1MessageFramer mf) return mf.getSyncBlockedCount();
                }
                catch(NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
        }
        catch(Exception e) { /* ignore */ }
        return 0;
    }

    static List<ChannelConfig> parsePlaylist(File xmlFile)
    {
        List<ChannelConfig> configs = new ArrayList<>();
        try
        {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile);
            NodeList channelNodes = doc.getElementsByTagName("channel");

            for(int i = 0; i < channelNodes.getLength(); i++)
            {
                Element ch = (Element)channelNodes.item(i);
                String name = ch.getAttribute("name");
                String system = ch.getAttribute("system");
                String site = ch.getAttribute("site");

                NodeList decodeConfigs = ch.getElementsByTagName("decode_configuration");
                if(decodeConfigs.getLength() == 0) continue;
                Element dc = (Element)decodeConfigs.item(0);
                if(!"decodeConfigP25Phase1".equals(dc.getAttribute("type"))) continue;

                String modulation = dc.getAttribute("modulation");
                int nac = 0;
                try { nac = Integer.parseInt(dc.getAttribute("configuredNAC")); } catch(NumberFormatException e) {}

                NodeList sourceConfigs = ch.getElementsByTagName("source_configuration");
                long frequency = 0;
                String preferredTuner = "N/A";
                if(sourceConfigs.getLength() > 0)
                {
                    Element sc = (Element)sourceConfigs.item(0);
                    try { frequency = Long.parseLong(sc.getAttribute("frequency")); } catch(NumberFormatException e) {}
                    String t = sc.getAttribute("preferred_tuner");
                    if(t != null && !t.isEmpty()) preferredTuner = t;
                }

                if(frequency > 0 && modulation != null && !modulation.isEmpty())
                {
                    configs.add(new ChannelConfig(name, system, site, frequency, modulation, nac, preferredTuner));
                }
            }
        }
        catch(Exception e) { System.err.println("ERROR parsing playlist: " + e.getMessage()); }
        return configs;
    }

    static long extractFrequency(String filename)
    {
        String[] parts = filename.split("_");
        if(parts.length >= 3)
        {
            try { return Long.parseLong(parts[2]); } catch(NumberFormatException e) { /* ignore */ }
        }
        return 0;
    }

    static ChannelConfig findChannel(List<ChannelConfig> channels, long frequency)
    {
        for(ChannelConfig ch : channels) { if(ch.frequency() == frequency) return ch; }
        return null;
    }

    static List<File> findBasebandFiles(File dir)
    {
        List<File> files = new ArrayList<>();
        if(dir.isFile() && dir.getName().endsWith("_baseband.wav")) { files.add(dir); return files; }
        if(dir.isDirectory())
        {
            File[] children = dir.listFiles();
            if(children != null)
            {
                Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
                for(File child : children)
                {
                    if(child.isDirectory()) files.addAll(findBasebandFiles(child));
                    else if(child.getName().endsWith("_baseband.wav")) files.add(child);
                }
            }
        }
        return files;
    }

    private static String sanitize(String name)
    {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("_baseband\\.wav$", "");
    }

    private static String escapeJson(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
