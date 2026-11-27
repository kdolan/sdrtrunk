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

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P25 pipeline diagnostics with per-channel log files.
 * Each enabled channel gets its own log file under {SDRTrunk logs}/p25_logs/.
 * Zero cost for channels with diagnostics disabled.
 *
 * Enabled via the "Pipeline Diagnostics" toggle in the P25 Phase 1 channel editor.
 * File location: {SDRTrunk logs dir}/p25_logs/p25-diag-{ChannelName}.log
 *
 * A busy channel writes about 200 MB an hour, so each log rolls over at {@link #MAX_FILE_BYTES} to
 * p25-diag-{ChannelName}.1.log (the previous .1 becomes .2, and so on) and only {@link #MAX_ROLLED_FILES} rolled
 * files are kept: a channel's diagnostics never take more than (1 + MAX_ROLLED_FILES) x MAX_FILE_BYTES.
 */
public class P25PipelineDiagnostics
{
    private static final Logger mLog = LoggerFactory.getLogger(P25PipelineDiagnostics.class);
    private static final String SUBDIR = "p25_logs";
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    static final int MAX_ROLLED_FILES = 2;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());
    private static final Map<String, ChannelLog> CHANNEL_WRITERS = new ConcurrentHashMap<>();

    /**
     * Enable diagnostics for a channel. Creates a per-channel log file.
     * @param channelName the channel name (used in the log filename)
     * @param logsDirectory the SDRTrunk application logs directory
     */
    public static void enableChannel(String channelName, Path logsDirectory)
    {
        if(channelName == null || logsDirectory == null) return;
        if(CHANNEL_WRITERS.containsKey(channelName)) return;

        try
        {
            Path p25LogDir = logsDirectory.resolve(SUBDIR);
            Files.createDirectories(p25LogDir);
            String safeName = channelName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            ChannelLog log = new ChannelLog(p25LogDir, "p25-diag-" + safeName, MAX_FILE_BYTES, MAX_ROLLED_FILES);
            log.println(String.format("--- P25 Pipeline Diagnostics for [%s] started at %s ---", channelName, Instant.now()));
            CHANNEL_WRITERS.put(channelName, log);
            mLog.info("Pipeline diagnostics enabled for [{}] → {}", channelName, log.getFile());
        }
        catch(IOException e)
        {
            mLog.error("Failed to open pipeline diagnostics log for [{}]: {}", channelName, e.getMessage());
        }
    }

    /**
     * Disable diagnostics for a channel. Closes the log file.
     */
    public static void disableChannel(String channelName)
    {
        if(channelName == null) return;
        ChannelLog log = CHANNEL_WRITERS.remove(channelName);
        if(log != null)
        {
            log.println(String.format("--- P25 Pipeline Diagnostics for [%s] stopped at %s ---", channelName, Instant.now()));
            log.close();
            mLog.info("Pipeline diagnostics disabled for [{}]", channelName);
        }
    }

    public static boolean isEnabled()
    {
        return !CHANNEL_WRITERS.isEmpty();
    }

    public static boolean isEnabled(String channel)
    {
        return channel != null && CHANNEL_WRITERS.containsKey(channel);
    }

    public static void log(String channel, String stage, String event, String detail)
    {
        ChannelLog log = CHANNEL_WRITERS.get(channel);
        if(log != null)
        {
            log.println(String.format("%s | %-12s | %-24s | %s", TIME_FMT.format(Instant.now()), stage, event, detail));
        }
    }

    public static void log(String channel, String stage, String event)
    {
        log(channel, stage, event, "");
    }

    /**
     * Append-only text log that rolls over by size and keeps a fixed number of rolled files.
     */
    static class ChannelLog
    {
        private final Path mFolder;
        private final String mBaseName;
        private final long mMaxBytes;
        private final int mMaxRolled;
        private PrintWriter mWriter;
        private long mBytes;

        ChannelLog(Path folder, String baseName, long maxBytes, int maxRolled) throws IOException
        {
            mFolder = folder;
            mBaseName = baseName;
            mMaxBytes = maxBytes;
            mMaxRolled = maxRolled;
            open();
        }

        Path getFile()
        {
            return mFolder.resolve(mBaseName + ".log");
        }

        Path getRolledFile(int index)
        {
            return mFolder.resolve(mBaseName + "." + index + ".log");
        }

        private void open() throws IOException
        {
            Path file = getFile();
            mBytes = Files.exists(file) ? Files.size(file) : 0;
            mWriter = new PrintWriter(new FileWriter(file.toFile(), true), true);
        }

        synchronized void println(String line)
        {
            if(mWriter == null)
            {
                return;
            }

            if(mBytes >= mMaxBytes)
            {
                roll();

                if(mWriter == null)
                {
                    return;
                }
            }

            mWriter.println(line);
            mBytes += line.length() + System.lineSeparator().length();
        }

        /**
         * Closes the current file, shifts the rolled files up by one (dropping the oldest) and starts a new file.
         */
        private void roll()
        {
            mWriter.close();
            mWriter = null;

            try
            {
                Files.deleteIfExists(getRolledFile(mMaxRolled));

                for(int index = mMaxRolled - 1; index >= 1; index--)
                {
                    if(Files.exists(getRolledFile(index)))
                    {
                        Files.move(getRolledFile(index), getRolledFile(index + 1), StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                if(mMaxRolled > 0)
                {
                    Files.move(getFile(), getRolledFile(1), StandardCopyOption.REPLACE_EXISTING);
                }
                else
                {
                    Files.deleteIfExists(getFile());
                }
            }
            catch(IOException e)
            {
                mLog.error("Unable to roll over pipeline diagnostics log [{}]: {}", getFile(), e.getMessage());
            }

            try
            {
                //If the move failed the file is still there: truncate it so the size cap holds
                if(Files.exists(getFile()) && Files.size(getFile()) >= mMaxBytes)
                {
                    Files.delete(getFile());
                }

                open();
            }
            catch(IOException e)
            {
                mLog.error("Unable to reopen pipeline diagnostics log [{}] - diagnostics stopped: {}", getFile(),
                    e.getMessage());
            }
        }

        synchronized void close()
        {
            if(mWriter != null)
            {
                mWriter.close();
                mWriter = null;
            }
        }
    }
}
