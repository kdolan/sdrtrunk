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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StorageRetentionTest
{
    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));

    @TempDir
    Path mTemp;

    private static Path file(Path folder, String name, int ageDays, int size) throws IOException
    {
        Files.createDirectories(folder);
        Path file = folder.resolve(name);
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofDays(ageDays))));
        return file;
    }

    private static void age(Path folder, int ageDays) throws IOException
    {
        Files.setLastModifiedTime(folder, FileTime.from(NOW.minus(Duration.ofDays(ageDays))));
    }

    @Test
    public void deletesOnlyFilesOlderThanTheCutoff() throws IOException
    {
        Path recordings = mTemp.resolve("recordings");
        Path old = file(recordings, "20261101_baseband.wav", 20, 1000);
        Path edge = file(recordings, "20261119_call.mp3", 8, 10);
        Path fresh = file(recordings, "20261126_call.mp3", 1, 10);
        Path channel = recordings.resolve("Municipalities_Raymond_Raymond-PD_42");
        Path oldInChannel = file(channel, "old_baseband.wav", 30, 500);
        Path freshInChannel = file(channel, "new_baseband.wav", 0, 500);

        StorageRetention.Result result = StorageRetention.purge(recordings, CUTOFF, Set.of());

        assertEquals(3, result.deletedFiles());
        assertEquals(1510, result.deletedBytes());
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(edge));
        assertFalse(Files.exists(oldInChannel));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(freshInChannel));
        assertTrue(Files.isDirectory(channel), "a folder that still holds files is kept");
    }

    @Test
    public void removesEmptiedFoldersOnlyOnceTheyHaveAgedOut() throws IOException
    {
        Path recordings = mTemp.resolve("recordings");
        Path stale = recordings.resolve("Rock-County-PD-W_1211");
        file(stale, "a_baseband.wav", 30, 10);
        Path emptyOld = recordings.resolve("Old-Channel_3");
        Files.createDirectories(emptyOld);
        age(emptyOld, 30);
        Path emptyNew = recordings.resolve("New-Channel_4");
        Files.createDirectories(emptyNew);

        StorageRetention.purge(recordings, CUTOFF, Set.of());
        assertFalse(Files.exists(emptyOld), "an empty folder older than the cutoff is removed");
        assertTrue(Files.exists(emptyNew), "a new empty folder may be in use by a recorder");
        assertTrue(Files.isDirectory(recordings), "the output folder itself is never removed");

        //Deleting the file updated the folder's modified time, so it goes on a later pass once it has aged out
        age(stale, 30);
        StorageRetention.purge(recordings, CUTOFF, Set.of());
        assertFalse(Files.exists(stale));
        assertTrue(Files.isDirectory(recordings));
    }

    @Test
    public void folderHoldingProtectedFoldersIsSkipped() throws IOException
    {
        //Recordings folder pointed at the application root by mistake: the playlist must survive
        Path root = mTemp.resolve("SDRTrunk");
        Path playlist = file(root.resolve("playlist"), "default.xml", 60, 100);
        Path recording = file(root, "old_call.mp3", 60, 100);

        StorageRetention.Result result = StorageRetention.purge(root, CUTOFF, Set.of(root.resolve("playlist")));

        assertTrue(result.skipped());
        assertEquals(0, result.deletedFiles());
        assertTrue(Files.exists(playlist));
        assertTrue(Files.exists(recording));
    }

    @Test
    public void siblingOfAProtectedFolderIsPurged() throws IOException
    {
        Path root = mTemp.resolve("SDRTrunk");
        Path playlist = file(root.resolve("playlist"), "default.xml", 60, 100);
        Path logs = root.resolve("logs");
        Path oldLog = file(logs, "20261101_sdrtrunk_app.log", 26, 100);

        StorageRetention.Result result = StorageRetention.purge(logs, CUTOFF, Set.of(root, root.resolve("playlist")));

        assertFalse(result.skipped());
        assertFalse(Files.exists(oldLog));
        assertTrue(Files.exists(playlist));
    }

    @Test
    public void guardDeletesOldestRecordingsFirstAndNeverActiveOnes() throws IOException
    {
        Path recordings = mTemp.resolve("recordings");
        Path oldest = file(recordings.resolve("Rock-W_9"), "a_baseband.wav", 5, 100);
        Path second = file(recordings, "b_call.mp3", 4, 100);
        Path third = file(recordings.resolve("Raymond-PD_42"), "c_baseband.wav", 3, 100);
        Path newest = file(recordings, "d_call.mp3", 2, 100);
        Path active = file(recordings.resolve("Raymond-PD_42"), "e_baseband.wav", 0, 100);
        Instant notAfter = NOW.minus(Duration.ofMinutes(10));

        StorageRetention.Result result = StorageRetention.purgeOldest(recordings, 150, notAfter, Set.of());
        assertEquals(2, result.deletedFiles(), "deletes in age order until the target is reached");
        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(second));
        assertTrue(Files.exists(third));
        assertTrue(Files.exists(newest));

        result = StorageRetention.purgeOldest(recordings, 10_000, notAfter, Set.of());
        assertEquals(2, result.deletedFiles());
        assertTrue(Files.exists(active), "a recording being written is never deleted");
        assertTrue(Files.isDirectory(recordings.resolve("Raymond-PD_42")));
    }

    @Test
    public void guardSkipsAFolderHoldingProtectedFolders() throws IOException
    {
        Path root = mTemp.resolve("SDRTrunk");
        Path playlist = file(root.resolve("playlist"), "default.xml", 60, 100);
        StorageRetention.Result result = StorageRetention.purgeOldest(root, 10_000, NOW,
            Set.of(root.resolve("playlist")));
        assertTrue(result.skipped());
        assertTrue(Files.exists(playlist));
    }

    @Test
    public void missingFolderIsIgnored()
    {
        StorageRetention.Result result = StorageRetention.purge(mTemp.resolve("nope"), CUTOFF, Set.of());
        assertEquals(0, result.deletedFiles());
        assertFalse(result.skipped());
    }
}
