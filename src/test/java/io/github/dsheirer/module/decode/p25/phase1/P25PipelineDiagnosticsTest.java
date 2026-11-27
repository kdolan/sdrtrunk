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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Size-capped rollover of the per-channel pipeline diagnostics log
 */
public class P25PipelineDiagnosticsTest
{
    @TempDir
    Path mTemp;

    @Test
    public void rollsOverAndKeepsOnlyTheNewestRolledFiles() throws IOException
    {
        P25PipelineDiagnostics.ChannelLog log = new P25PipelineDiagnostics.ChannelLog(mTemp, "p25-diag-X", 1000, 2);

        for(int x = 0; x < 100; x++)
        {
            log.println(String.format("line %03d %s", x, "x".repeat(80)));
        }

        log.close();

        assertTrue(Files.exists(log.getFile()));
        assertTrue(Files.exists(log.getRolledFile(1)));
        assertTrue(Files.exists(log.getRolledFile(2)));
        assertFalse(Files.exists(log.getRolledFile(3)), "only two rolled files are kept");

        try(var files = Files.list(mTemp))
        {
            assertEquals(3, files.count());
        }

        for(Path file: List.of(log.getFile(), log.getRolledFile(1), log.getRolledFile(2)))
        {
            assertTrue(Files.size(file) <= 1000 + 100, file + " is within the cap (plus at most one line)");
        }

        List<String> current = Files.readAllLines(log.getFile());
        assertTrue(current.get(current.size() - 1).startsWith("line 099"), "the newest lines are in the current file");
        assertTrue(Files.readAllLines(log.getRolledFile(1)).get(0).compareTo(current.get(0)) < 0,
            "the .1 file holds the lines just before the current file");
    }

    @Test
    public void oversizedExistingFileRollsOnFirstWrite() throws IOException
    {
        Path existing = mTemp.resolve("p25-diag-Y.log");
        Files.write(existing, new byte[5000]);

        P25PipelineDiagnostics.ChannelLog log = new P25PipelineDiagnostics.ChannelLog(mTemp, "p25-diag-Y", 1000, 1);
        log.println("first line after restart");
        log.close();

        assertEquals(5000, Files.size(log.getRolledFile(1)));
        assertEquals(List.of("first line after restart"), Files.readAllLines(log.getFile()));
    }
}
