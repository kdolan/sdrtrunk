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
import io.github.dsheirer.audio.UnitOffset;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Diagnostic main() that replays P25 Phase 1 baseband WAV recordings through the full ProcessingChain and reports the
 * source (FROM) unit timeline captured on each resulting AudioSegment — the data that backs the rdio-scanner 'units'
 * array.  Used to validate the rdio-units feature against real recordings and to identify which recordings are good
 * test cases (i.e. contain more than one distinct unit in a single call).
 *
 * Usage:
 *   --samples &lt;dir&gt; --freq &lt;hz&gt; [--nac &lt;decimal&gt;] [--mod C4FM|LSM] [--jmbe &lt;jar&gt;] [--max-files N]
 */
public class RdioUnitsReplayValidator
{
    public static void main(String[] args) throws Exception
    {
        String samplesDir = null, jmbePath = null, modulation = "C4FM";
        long frequency = 0;
        int nac = 0, maxFiles = -1;

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
            }
        }

        if(samplesDir == null || frequency == 0)
        {
            System.out.println("Usage: --samples <dir> --freq <hz> [--nac <decimal>] [--mod C4FM|LSM] [--jmbe <jar>] [--max-files N]");
            System.exit(1);
            return;
        }

        if(jmbePath != null)
        {
            try
            {
                java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(
                    io.github.dsheirer.preference.decoder.JmbeLibraryPreference.class);
                prefs.put("path.jmbe.library.1.0.0", jmbePath);
                prefs.flush();
            }
            catch(Exception e)
            {
                System.err.println("WARNING: Failed to set JMBE path: " + e.getMessage());
            }
        }

        File dir = new File(samplesDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith("_baseband.wav"));
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

        System.out.printf("Rdio Units Replay: %d files | Freq: %d | NAC: %d | Mod: %s%n%n",
            files.length, frequency, nac, modulation);

        List<String> goodCases = new ArrayList<>();
        int filesWithUnits = 0;

        for(File file : files)
        {
            List<SegmentUnits> segments = replayFile(file, frequency, nac, modulation);

            boolean anyUnits = segments.stream().anyMatch(s -> !s.units.isEmpty());
            if(anyUnits)
            {
                filesWithUnits++;
            }

            boolean multiUnit = segments.stream().anyMatch(s -> distinctUnitCount(s.units) >= 2);

            System.out.printf("━━━ %s%s%n", file.getName(), multiUnit ? "   <<< MULTI-UNIT (good test case)" : "");

            if(segments.isEmpty())
            {
                System.out.println("    (no audio segments)");
            }

            for(int i = 0; i < segments.size(); i++)
            {
                SegmentUnits s = segments.get(i);
                StringBuilder line = new StringBuilder();
                line.append(String.format("    segment %d  to=%s  dur=%.1fs  source(first)=%s  units=[",
                    i + 1, s.to, s.durationSeconds, s.units.isEmpty() ? "0" : s.units.get(0).radio().getValue()));

                for(int u = 0; u < s.units.size(); u++)
                {
                    UnitOffset uo = s.units.get(u);
                    if(u > 0) line.append(", ");
                    line.append(uo.radio().getValue()).append("@").append(String.format("%.2fs", uo.offsetSeconds()));
                }
                line.append("]");
                System.out.println(line);
            }

            if(multiUnit)
            {
                goodCases.add(file.getName());
            }
            System.out.println();
        }

        System.out.println("════════════════════════════════════");
        System.out.printf("%d files | %d produced units | %d MULTI-UNIT good test cases%n",
            files.length, filesWithUnits, goodCases.size());
        for(String g : goodCases)
        {
            System.out.println("  GOOD: " + g);
        }
    }

    private static int distinctUnitCount(List<UnitOffset> units)
    {
        return (int) units.stream().map(u -> u.radio().getValue().intValue()).distinct().count();
    }

    private static List<SegmentUnits> replayFile(File file, long frequency, int nac, String modulation) throws Exception
    {
        Channel channel = new Channel("RdioUnitsReplay");
        channel.setSystem("Test");
        channel.setSite("Replay");

        DecodeConfigP25Phase1 decodeConfig = new DecodeConfigP25Phase1();
        decodeConfig.setModulation(Modulation.valueOf(modulation));
        decodeConfig.setConfiguredNAC(nac);
        decodeConfig.setAudioHoldoverMs(180);
        channel.setDecodeConfiguration(decodeConfig);

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(frequency);
        channel.setSourceConfiguration(sourceConfig);

        AliasModel aliasModel = new AliasModel();
        ProcessingChain processingChain = new ProcessingChain(channel, aliasModel);

        UserPreferences userPreferences = new UserPreferences();
        ChannelMapModel channelMapModel = new ChannelMapModel();
        List<Module> modules = DecoderFactory.getModules(channelMapModel, channel, aliasModel, userPreferences, null, null);
        processingChain.addModules(modules);

        List<AudioSegment> captured = new CopyOnWriteArrayList<>();
        processingChain.addAudioSegmentListener(captured::add);

        TestComplexSource source = new TestComplexSource(file, frequency);
        processingChain.setSource(source);
        processingChain.start();

        while(source.next(2048)) {}

        Thread.sleep(150);

        processingChain.stop();
        source.close();

        List<SegmentUnits> results = new ArrayList<>();
        for(AudioSegment segment : captured)
        {
            Identifier<?> to = segment.getIdentifierCollection().getToIdentifier();
            results.add(new SegmentUnits(to != null ? to.toString() : "?",
                segment.getDuration() / 1000.0,
                new ArrayList<>(segment.getUnitHistory())));
        }
        return results;
    }

    private record SegmentUnits(String to, double durationSeconds, List<UnitOffset> units) {}
}
