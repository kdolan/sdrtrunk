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

package io.github.dsheirer.monitor;

import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.directory.DirectorySettings;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes recordings, event logs and application logs that are older than the retention period (Settings >> Storage),
 * so that a node that records many channels does not fill its disk.  Runs shortly after startup and then hourly.
 *
 * Only regular files whose last-modified time is older than the cutoff are deleted, and then any sub-folders left
 * empty.  The output folder itself is never removed.  A folder is skipped when it is, or contains, the application
 * root, configuration, playlist or JMBE folder, so a recordings folder pointed at the wrong place can't remove the
 * user's settings.  Files that are open (being written) can't be deleted on Windows and are retried next pass.
 */
public class StorageRetention
{
    private static final Logger mLog = LoggerFactory.getLogger(StorageRetention.class);
    private static final long INITIAL_DELAY_MINUTES = 2;
    private static final long INTERVAL_MINUTES = 60;

    private final Settings mSettings;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private ScheduledFuture<?> mFuture;

    public StorageRetention(Settings settings)
    {
        mSettings = settings;
    }

    public void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> ThreadPool.CACHED.execute(this::runOnce),
                INITIAL_DELAY_MINUTES, INTERVAL_MINUTES, TimeUnit.MINUTES);
        }
    }

    public void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(false);
            mFuture = null;
        }
    }

    /**
     * One retention pass over the recordings, event logs and application logs folders.
     */
    void runOnce()
    {
        if(!mRunning.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            DirectorySettings directories = mSettings.getDirectorySettings();
            int days = directories.getDirectoryRetentionDays();

            if(days <= 0)
            {
                return;
            }

            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            Set<Path> protectedPaths = new LinkedHashSet<>();
            protectedPaths.add(directories.getDirectoryApplicationRoot());
            protectedPaths.add(directories.getDirectoryConfiguration());
            protectedPaths.add(directories.getDirectoryPlaylist());
            protectedPaths.add(directories.getDirectoryJmbe());

            for(Path folder: List.of(directories.getDirectoryRecording(), directories.getDirectoryEventLog(),
                directories.getDirectoryApplicationLog()))
            {
                Result result = purge(folder, cutoff, protectedPaths);

                if(result.deletedFiles() > 0 || result.failedFiles() > 0)
                {
                    mLog.info("Storage retention (" + days + " days): " + folder + " - deleted " +
                        result.deletedFiles() + " files (" + FileUtils.byteCountToDisplaySize(result.deletedBytes()) +
                        ")" + (result.failedFiles() > 0 ? ", " + result.failedFiles() + " in use or not deletable" : ""));
                }
            }
        }
        catch(Throwable t)
        {
            mLog.error("Storage retention pass failed", t);
        }
        finally
        {
            mRunning.set(false);
        }
    }

    /**
     * Outcome of a purge
     * @param deletedFiles count
     * @param deletedBytes total size of the deleted files
     * @param failedFiles that were old enough but could not be deleted
     * @param skipped true if the folder was not purged because it holds a protected folder
     */
    public record Result(int deletedFiles, long deletedBytes, int failedFiles, boolean skipped) {}

    /**
     * Deletes regular files under the folder last modified before the cutoff, then sub-folders left empty.
     * @param folder to purge; it is never deleted itself
     * @param cutoff files modified before this instant are deleted
     * @param protectedPaths folders that must survive: when the folder is one of these or contains one, nothing is done
     * @return result
     */
    public static Result purge(Path folder, Instant cutoff, Set<Path> protectedPaths)
    {
        if(folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
        {
            return new Result(0, 0, 0, false);
        }

        Path root = normalize(folder);

        for(Path p: protectedPaths)
        {
            if(p != null && normalize(p).startsWith(root))
            {
                mLog.warn("Storage retention skipped [" + folder + "] because it contains [" + p + "]");
                return new Result(0, 0, 0, true);
            }
        }

        int[] counts = new int[2];
        long[] bytes = new long[1];
        purgeFolder(root, root, cutoff, counts, bytes);
        return new Result(counts[0], bytes[0], counts[1], false);
    }

    /**
     * Recursive purge of one folder.  Symbolic links are neither followed nor deleted.
     * @return true if the folder is now empty
     */
    private static boolean purgeFolder(Path root, Path folder, Instant cutoff, int[] counts, long[] bytes)
    {
        List<Path> entries = new ArrayList<>();

        try(DirectoryStream<Path> stream = Files.newDirectoryStream(folder))
        {
            stream.forEach(entries::add);
        }
        catch(IOException e)
        {
            return false;
        }

        int remaining = entries.size();

        for(Path entry: entries)
        {
            try
            {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);

                if(attributes.isDirectory())
                {
                    //An emptied folder is removed only once it has itself aged out (deleting its files updates its
                    //modified time), so a folder a recorder is using is never pulled out from under it
                    if(purgeFolder(root, entry, cutoff, counts, bytes) &&
                        Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff))
                    {
                        Files.delete(entry);
                        remaining--;
                    }
                }
                else if(attributes.isRegularFile() && attributes.lastModifiedTime().toInstant().isBefore(cutoff))
                {
                    try
                    {
                        Files.delete(entry);
                        counts[0]++;
                        bytes[0] += attributes.size();
                        remaining--;
                    }
                    catch(IOException e)
                    {
                        counts[1]++;
                    }
                }
            }
            catch(IOException e)
            {
                //Gone or unreadable: leave it
            }
        }

        return remaining == 0 && !folder.equals(root);
    }

    private static Path normalize(Path path)
    {
        Path absolute = path.toAbsolutePath().normalize();

        try
        {
            return absolute.toRealPath();
        }
        catch(IOException e)
        {
            return absolute;
        }
    }
}
