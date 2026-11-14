package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Selection of the channels to restart when a removed tuner reconnects.
 */
public class TunerStoppedChannelsTest
{
    @Test
    public void takesOnlyChannelsStoppedByTheRemoval()
    {
        long removed = 100_000;
        Map<String,Long> stopped = new HashMap<>();
        stopped.put("just before removal", removed - 5);
        stopped.put("just after removal", removed + 5); //the tuner model stops channels after the removal broadcast
        stopped.put("edge of window", removed - ChannelProcessingManager.TUNER_STOP_WINDOW_MS);
        stopped.put("long before", removed - 60_000);
        stopped.put("after removal", removed + 3_000);

        List<String> restart = ChannelProcessingManager.takeStoppedAt(stopped, removed);

        assertEquals(3, restart.size());
        assertEquals(true, restart.containsAll(List.of("just before removal", "just after removal", "edge of window")));
        //Old entries are discarded and a later stop is kept for its own tuner's reconnect
        assertEquals(Map.of("after removal", removed + 3_000), stopped);
    }
}
