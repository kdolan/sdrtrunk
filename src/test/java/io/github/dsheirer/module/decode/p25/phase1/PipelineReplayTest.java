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

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full pipeline replay diagnostic. Feeds baseband WAV files through the complete
 * ProcessingChain (DecoderState, StateMachine, SquelchController, AudioModule) to
 * reproduce live-only bugs that don't appear in the decoder-only offline test.
 *
 * Usage:
 *   gradle runPipelineReplay -Psamples=<dir> -Pfreq=<hz> -Pnac=<decimal> [-Pmod=C4FM] [-Pjmbe=<jar>]
 */
public class PipelineReplayTest
{
    public static void main(String[] args) throws Exception
    {
        String samplesDir = null, jmbePath = null;
        long frequency = 0;
        int nac = 0;
        String modulation = "C4FM";
        int maxFiles = -1;
        boolean continuous = false;
        String playlistPath = null, channelName = null;

        for(int i = 0; i < args.length; i++)
        {
            switch(args[i])
            {
                case "--samples" -> samplesDir = args[++i];
                case "--freq" -> frequency = Long.parseLong(args[++i]);
                case "--nac" -> nac = Integer.parseInt(args[++i]);
                case "--mod" -> modulation = args[++i];
                case "--jmbe" -> jmbePath = args[++i];
                case "--max-files" -> maxFiles = Integer.parseInt(args[++i]);
                case "--continuous" -> continuous = true;
                case "--playlist" -> playlistPath = args[++i];
                case "--channel" -> channelName = args[++i];
                case "--audio-out" -> sAudioOut = new File(args[++i]);
            }
        }

        if(playlistPath != null && channelName != null)
        {
            sPlaylistChannel = loadChannel(playlistPath, channelName);
            if(sPlaylistChannel.getSourceConfiguration() instanceof SourceConfigTuner sct && frequency == 0)
            {
                frequency = sct.getFrequency();
            }
            System.out.println("Using playlist channel config: " + channelName);
        }

        if(sAudioOut != null)
        {
            sAudioOut.mkdirs();
        }

        if(samplesDir == null || frequency == 0)
        {
            System.out.println("Usage: PipelineReplayTest --samples <dir> --freq <hz> --nac <decimal> [--mod C4FM] [--jmbe <jar>] [--max-files N]");
            System.out.println("       [--playlist <xml> --channel <name>] (live channel decode config) [--audio-out <dir>] (segment WAVs)");
            System.exit(1);
            return;
        }

        // Use the JMBE jar for this run only.  Writing the Java preference would change the live application's
        // codec at its next start, so the path is injected into each run's Settings instead.
        sJmbePath = jmbePath;

        // Find baseband files
        File dir = new File(samplesDir);
        File[] files = dir.isFile() ? new File[]{dir} : dir.listFiles((d, name) -> name.endsWith("_baseband.wav"));
        if(files == null || files.length == 0)
        {
            System.err.println("No baseband files found in: " + samplesDir);
            System.exit(1);
            return;
        }
        java.util.Arrays.sort(files);
        if(maxFiles > 0 && files.length > maxFiles)
        {
            files = java.util.Arrays.copyOf(files, maxFiles);
        }

        System.out.printf("Pipeline Replay: %d files | Freq: %d | NAC: %d | Mod: %s | Mode: %s%n%n",
            files.length, frequency, nac, modulation, continuous ? "CONTINUOUS" : "per-file");

        if(continuous)
        {
            replayContinuous(files, frequency, nac, modulation, jmbePath);
        }
        else
        {
            int totalCalls = 0, totalNoCall = 0;
            double totalAudioSeconds = 0;

            for(File file : files)
            {
                System.out.printf("━━━ %s ━━━%n", file.getName());
                ReplayResult result = replayFile(file, frequency, nac, modulation, jmbePath);

                if(result.audioSegmentCount > 0)
                {
                    totalCalls += result.audioSegmentCount;
                    totalAudioSeconds += result.audioSeconds;
                    System.out.printf("  RESULT: %d audio segments, %.1fs audio%n", result.audioSegmentCount, result.audioSeconds);
                }
                else
                {
                    totalNoCall++;
                    System.out.printf("  RESULT: *** NO AUDIO SEGMENTS ***%n");
                }
                System.out.println();
            }

            System.out.println("════════════════════════════════════");
            System.out.printf("Total: %d files | %d with audio | %d WITHOUT audio | %.1fs total audio%n",
                files.length, files.length - totalNoCall, totalNoCall, totalAudioSeconds);
        }
    }

    static ReplayResult replayFile(File file, long frequency, int nac, String modulation, String jmbePath) throws Exception
    {
        // Build a Channel with the right decode config
        Channel channel = new Channel("PipelineReplay");
        channel.setSystem("Test");
        channel.setSite("Replay");

        if(sPlaylistChannel != null)
        {
            channel.setDecodeConfiguration(sPlaylistChannel.getDecodeConfiguration());
        }
        else
        {
            DecodeConfigP25Phase1 decodeConfig = new DecodeConfigP25Phase1();
            decodeConfig.setModulation(Modulation.valueOf(modulation));
            decodeConfig.setConfiguredNAC(nac);
            decodeConfig.setAudioHoldoverMs(180);
            channel.setDecodeConfiguration(decodeConfig);
        }

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(frequency);
        channel.setSourceConfiguration(sourceConfig);

        // Instrumentation counters
        Map<String, AtomicInteger> duidCounts = new HashMap<>();
        List<String> stateTransitions = new ArrayList<>();
        List<String> squelchEvents = new ArrayList<>();
        AtomicInteger audioSegmentCount = new AtomicInteger(0);
        double[] audioSeconds = {0};
        AtomicInteger messageCount = new AtomicInteger(0);
        AtomicInteger decodeEventCount = new AtomicInteger(0);
        AtomicInteger hduCount = new AtomicInteger(0);
        AtomicInteger ldu1Count = new AtomicInteger(0);
        AtomicInteger ldu2Count = new AtomicInteger(0);

        // Create ProcessingChain — this creates SingleChannelState, StateMachine, SquelchController
        AliasModel aliasModel = new AliasModel();
        ProcessingChain processingChain = new ProcessingChain(channel, aliasModel);

        // Create decoder modules via DecoderFactory (same as live system)
        Settings settings = createSettings();
        ChannelMapModel channelMapModel = new ChannelMapModel();
        List<Module> modules = DecoderFactory.getModules(channelMapModel, channel, aliasModel, settings, null, null);
        processingChain.addModules(modules);

        // Check JMBE codec status via reflection (hasAudioCodec is protected)
        boolean jmbeLoaded = false;
        for(Module m : modules)
        {
            if(m instanceof P25P1AudioModule audioMod)
            {
                try
                {
                    var method = audioMod.getClass().getSuperclass().getSuperclass().getDeclaredMethod("hasAudioCodec");
                    method.setAccessible(true);
                    jmbeLoaded = (boolean) method.invoke(audioMod);
                }
                catch(Exception e) { /* ignore */ }
                System.out.printf("  JMBE codec: %s | maxImbeErrors: %d%n",
                    jmbeLoaded ? "LOADED" : "*** NOT LOADED ***", audioMod.getMaxImbeErrors());
            }
        }
        if(!jmbeLoaded)
        {
            System.out.println("  *** WARNING: JMBE codec not loaded — AudioModule will produce NO audio ***");
            System.out.println("  *** Ensure JMBE is configured in SDRTrunk preferences ***");
        }

        // Add instrumentation listeners
        // 1. Message listener — count messages by DUID
        long[] samplesFed = {0};
        StringBuilder frameLog = new StringBuilder("t,duid,corrected,frame,total,errors,received,unrecoverable\n");
        processingChain.addMessageListener(msg -> {
            messageCount.incrementAndGet();
            if(sAudioOut != null && msg instanceof io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage ldu)
            {
                List<jmbe.frame.CorrectedIMBEFrame> cf = ldu.getCorrectedIMBEFrames();
                boolean unrecoverable = P25P1AudioModule.isUnrecoverable(ldu);                for(int k = 0; k < cf.size(); k++)
                {
                    frameLog.append(String.format("%.3f,%s,%b,%d,%d,%s,%b,%b%n", samplesFed[0] / 50000.0,
                        ldu.getDUID().name(), ldu.isDuidCorrected(), k, cf.get(k).getErrorCountTotal(),
                        java.util.Arrays.toString(cf.get(k).getErrors()).replace(",", " "),
                        ldu.isIMBEFrameReceived(k), unrecoverable));
                }
            }
            if(msg instanceof P25P1Message p)
            {
                String duid = p.getDUID().name();
                duidCounts.computeIfAbsent(duid, k -> new AtomicInteger(0)).incrementAndGet();
                if(p instanceof HDUMessage) hduCount.incrementAndGet();
                else if(p instanceof LDU1Message) ldu1Count.incrementAndGet();
                else if(p instanceof LDU2Message) ldu2Count.incrementAndGet();
            }
        });

        // 2. Decoder state event listener — track state transitions
        processingChain.addDecoderStateEventListener(event -> {
            String src = event.getSource() != null ? event.getSource().getClass().getSimpleName() : "null";
            String entry = String.format("[%s] %s → %s", event.getEvent(), event.getState(), src);
            stateTransitions.add(entry);
        });

        // 3. Squelch state listener — track squelch open/close
        processingChain.addSquelchStateListener(event -> {
            squelchEvents.add(event.getSquelchState().name());
        });

        // 4. Audio segment listener — count and measure audio output
        processingChain.addAudioSegmentListener(segment -> {
            int index = audioSegmentCount.incrementAndGet();
            double startSeconds = samplesFed[0] / 50000.0;
            // Track when segment completes
            segment.completeProperty().addListener((obs, oldVal, newVal) -> {
                if(newVal)
                {
                    audioSeconds[0] += segment.getDuration() / 1000.0;
                    writeSegment(file, index, startSeconds, segment);
                }
            });
        });

        // 5. Decode event listener
        processingChain.addDecodeEventListener(event -> {
            decodeEventCount.incrementAndGet();
        });

        // Create source and wire to processing chain
        TestComplexSource source = new TestComplexSource(file, frequency);
        processingChain.setSource(source);
        processingChain.start();

        // Feed samples through the pipeline
        while(source.next(2048))
        {
            samplesFed[0] += 2048;
        }

        // Allow any pending events to settle
        Thread.sleep(100);

        if(sAudioOut != null)
        {
            java.nio.file.Files.writeString(new File(sAudioOut,
                file.getName().replace("_baseband.wav", "_frames.csv")).toPath(), frameLog.toString());
        }

        // Stop and collect results
        processingChain.stop();
        source.close();

        // Print diagnostics
        System.out.printf("  Messages: %d total | HDU: %d | LDU1: %d | LDU2: %d%n",
            messageCount.get(), hduCount.get(), ldu1Count.get(), ldu2Count.get());

        if(!duidCounts.isEmpty())
        {
            System.out.print("  DUIDs: ");
            duidCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> System.out.printf("%s=%d ", e.getKey(), e.getValue().get()));
            System.out.println();
        }

        // Summarize state transitions
        Map<String, Integer> stateSummary = new HashMap<>();
        for(String t : stateTransitions)
        {
            stateSummary.merge(t, 1, Integer::sum);
        }
        if(!stateSummary.isEmpty())
        {
            System.out.print("  State events: ");
            stateSummary.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> System.out.printf("%s(×%d) ", e.getKey(), e.getValue()));
            System.out.println();
        }

        // Count specific state types
        long callStarts = stateTransitions.stream().filter(s -> s.contains("START") && s.contains("CALL")).count();
        long callContinuations = stateTransitions.stream().filter(s -> s.contains("CONTINUATION") && s.contains("CALL")).count();
        long fadeEvents = stateTransitions.stream().filter(s -> s.contains("FADE")).count();
        System.out.printf("  State summary: %d CALL starts | %d CALL continuations | %d FADE events%n",
            callStarts, callContinuations, fadeEvents);

        // Squelch summary
        long unsquelch = squelchEvents.stream().filter(s -> s.equals("UNSQUELCH")).count();
        long squelch = squelchEvents.stream().filter(s -> s.equals("SQUELCH")).count();
        System.out.printf("  Squelch: %d UNSQUELCH | %d SQUELCH%n", unsquelch, squelch);

        System.out.printf("  Audio: %d segments | %.1fs | Decode events: %d%n",
            audioSegmentCount.get(), audioSeconds[0], decodeEventCount.get());

        return new ReplayResult(messageCount.get(), audioSegmentCount.get(), audioSeconds[0],
            (int)callStarts, (int)callContinuations, (int)fadeEvents, (int)unsquelch, (int)squelch);
    }

    private static Channel sPlaylistChannel = null;
    private static String sJmbePath = null;

    /**
     * Creates settings whose JMBE library path is the --jmbe argument, set on the cached field so that nothing is
     * persisted to the user's preferences.
     */
    static Settings createSettings() throws Exception
    {
        Settings settings = new Settings();
        if(sJmbePath != null)
        {
            var jmbe = settings.getJmbeLibrarySettings();
            var field = jmbe.getClass().getDeclaredField("mPathJmbeLibrary");
            field.setAccessible(true);
            field.set(jmbe, java.nio.file.Path.of(sJmbePath));
        }
        return settings;
    }
    private static File sAudioOut = null;

    /**
     * Loads the named channel from a playlist so the replay runs with the exact live decode configuration.
     */
    static Channel loadChannel(String playlistPath, String channelName) throws Exception
    {
        com.fasterxml.jackson.dataformat.xml.JacksonXmlModule xmlModule =
            new com.fasterxml.jackson.dataformat.xml.JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.dataformat.xml.XmlMapper(xmlModule)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try(java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(playlistPath)))
        {
            io.github.dsheirer.playlist.PlaylistV2 playlist =
                mapper.readValue(in, io.github.dsheirer.playlist.PlaylistV2.class);
            for(Channel c : playlist.getChannels())
            {
                if(channelName.equals(c.getName()))
                {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("Channel not found in playlist: " + channelName);
    }

    /**
     * Writes a completed audio segment as 8 kHz 16-bit mono WAV, named with its approximate start offset in the
     * baseband file so that a segment can be matched back to the I/Q.
     */
    static void writeSegment(File baseband, int index, double startSeconds, AudioSegment segment)
    {
        if(sAudioOut == null)
        {
            return;
        }

        try
        {
            List<float[]> buffers = segment.getAudioBuffers();
            int total = 0;
            for(float[] b : buffers)
            {
                total += b.length;
            }
            byte[] pcm = new byte[total * 2];
            int p = 0;
            for(float[] b : buffers)
            {
                for(float f : b)
                {
                    int v = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
                    pcm[p++] = (byte)(v & 0xFF);
                    pcm[p++] = (byte)((v >> 8) & 0xFF);
                }
            }
            String base = baseband.getName().replace("_baseband.wav", "");
            File out = new File(sAudioOut, String.format("%s_seg%02d_t%06.2f.wav", base, index, startSeconds));
            javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(8000f, 16, 1, true, false);
            try(javax.sound.sampled.AudioInputStream ais = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), format, total))
            {
                javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, out);
            }
            System.out.printf("  wrote %s (%.2fs)%n", out.getName(), total / 8000.0);
        }
        catch(Exception e)
        {
            System.err.println("Failed to write segment audio: " + e.getMessage());
        }
    }

    record ReplayResult(int messageCount, int audioSegmentCount, double audioSeconds,
                        int callStarts, int callContinuations, int fadeEvents,
                        int unsquelchCount, int squelchCount) {}

    /**
     * Injects random noise samples to simulate the inter-call noise floor.
     * In the live system, the decoder continuously processes noise between calls,
     * which can corrupt framer state (flywheel, DUID prediction, sync detectors).
     */
    private static void injectNoise(Listener<ComplexSamples> listener, int totalSamples, float amplitude)
    {
        java.util.Random rng = new java.util.Random(42);
        int chunkSize = 2048;
        long timestamp = System.currentTimeMillis();

        for(int offset = 0; offset < totalSamples; offset += chunkSize)
        {
            int len = Math.min(chunkSize, totalSamples - offset);
            float[] iSamples = new float[len];
            float[] qSamples = new float[len];
            for(int i = 0; i < len; i++)
            {
                iSamples[i] = (float)(rng.nextGaussian() * amplitude);
                qSamples[i] = (float)(rng.nextGaussian() * amplitude);
            }
            listener.receive(new ComplexSamples(iSamples, qSamples, timestamp));
            timestamp += (long)(len / 50.0); // ~50kHz sample rate
        }
    }

    /**
     * Continuous replay: feeds ALL files through a SINGLE ProcessingChain without restarting.
     * This simulates the live system's continuous stream where framer/decoder/audio state persists.
     * Between files, feeds silence (zeros) to simulate inter-call noise gaps.
     */
    static void replayContinuous(File[] files, long frequency, int nac, String modulation, String jmbePath) throws Exception
    {
        Channel channel = new Channel("ContinuousReplay");
        channel.setSystem("Test");
        channel.setSite("Replay");

        if(sPlaylistChannel != null)
        {
            channel.setDecodeConfiguration(sPlaylistChannel.getDecodeConfiguration());
        }
        else
        {
            DecodeConfigP25Phase1 decodeConfig = new DecodeConfigP25Phase1();
            decodeConfig.setModulation(Modulation.valueOf(modulation));
            decodeConfig.setConfiguredNAC(nac);
            decodeConfig.setAudioHoldoverMs(180);
            channel.setDecodeConfiguration(decodeConfig);
        }

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(frequency);
        channel.setSourceConfiguration(sourceConfig);

        // Instrumentation — per-file tracking
        AtomicInteger totalAudioSegments = new AtomicInteger(0);
        double[] totalAudioSeconds = {0};
        List<String> perFileResults = new ArrayList<>();

        // Create ONE ProcessingChain for ALL files
        AliasModel aliasModel = new AliasModel();
        ProcessingChain processingChain = new ProcessingChain(channel, aliasModel);

        Settings settings = createSettings();
        ChannelMapModel channelMapModel = new ChannelMapModel();
        List<Module> modules = DecoderFactory.getModules(channelMapModel, channel, aliasModel, settings, null, null);
        processingChain.addModules(modules);

        // Check JMBE
        for(Module m : modules)
        {
            if(m instanceof P25P1AudioModule audioMod)
            {
                try
                {
                    var method = audioMod.getClass().getSuperclass().getSuperclass().getDeclaredMethod("hasAudioCodec");
                    method.setAccessible(true);
                    boolean loaded = (boolean) method.invoke(audioMod);
                    System.out.printf("JMBE codec: %s%n%n", loaded ? "LOADED" : "*** NOT LOADED ***");
                    if(!loaded)
                    {
                        System.out.println("*** ABORTING: JMBE codec required for continuous replay ***");
                        return;
                    }
                }
                catch(Exception e) { /* ignore */ }
            }
        }

        // Audio segment listener
        File[] currentFile = {files[0]};
        processingChain.addAudioSegmentListener(segment -> {
            int index = totalAudioSegments.incrementAndGet();
            File segmentFile = currentFile[0];
            segment.completeProperty().addListener((obs, oldVal, newVal) -> {
                if(newVal)
                {
                    totalAudioSeconds[0] += segment.getDuration() / 1000.0;
                    writeSegment(new File(segmentFile.getName().replace("_baseband.wav", "_continuous_baseband.wav")),
                        index, 0, segment);
                }
            });
        });

        // Create source from FIRST file and start the chain ONCE
        TestComplexSource firstSource = new TestComplexSource(files[0], frequency);
        processingChain.setSource(firstSource);
        processingChain.start();
        // Don't feed first source yet — we'll handle all files uniformly below.
        // But the chain is now started with the correct sample rate and frequency.

        int filesWithAudio = 0;

        for(int f = 0; f < files.length; f++)
        {
            int segmentsBefore = totalAudioSegments.get();
            currentFile[0] = files[f];

            Listener<ComplexSamples> chainBroadcaster = firstSource.getListener();

            // Inject noise between files to simulate inter-call noise floor (like the live system)
            if(f > 0)
            {
                injectNoise(chainBroadcaster, 50000, 0.001f); // ~1 second at 50kHz, low amplitude noise
            }

            try(TestComplexSource fileSource = new TestComplexSource(files[f], frequency))
            {
                fileSource.setListener(chainBroadcaster);
                while(fileSource.next(2048))
                {
                    // Simulate heartbeat every ~50ms worth of samples (2500 samples at 50kHz)
                    // This triggers checkState() which enforces fade timeouts like the live system
                    firstSource.getHeartbeatManager().broadcast();
                }
            }
            // Final heartbeat after file ends
            firstSource.getHeartbeatManager().broadcast();

            // Small delay to let events settle
            Thread.sleep(50);

            int segmentsThisFile = totalAudioSegments.get() - segmentsBefore;
            boolean hasAudio = segmentsThisFile > 0;
            if(hasAudio) filesWithAudio++;

            String result = String.format("  [%d/%d] %s → %s",
                f + 1, files.length, files[f].getName(),
                hasAudio ? segmentsThisFile + " segments" : "*** NO AUDIO ***");
            perFileResults.add(result);
            System.out.println(result);
        }

        // Stop the chain
        processingChain.stop();
        firstSource.close();

        System.out.println();
        System.out.println("════════════════════════════════════");
        System.out.printf("CONTINUOUS MODE: %d files | %d with audio | %d WITHOUT audio | %.1fs total%n",
            files.length, filesWithAudio, files.length - filesWithAudio, totalAudioSeconds[0]);
        System.out.printf("Success rate: %.1f%%%n", 100.0 * filesWithAudio / files.length);
    }
}
