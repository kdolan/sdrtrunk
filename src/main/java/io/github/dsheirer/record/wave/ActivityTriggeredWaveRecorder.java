/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.record.wave;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.ConversionUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;

/**
 * Activity-triggered baseband recorder. Monitors signal energy on the channel itself (see ChannelActivityDetector)
 * and records the channelized I/Q to WAV files only while the in-channel power is more than the trigger margin above
 * the channel's noise floor. Maintains a circular pre-trigger buffer and a post-activity hold timer.
 *
 * This module is completely independent of the decode pipeline and works with any decoder type.
 */
public class ActivityTriggeredWaveRecorder extends Module implements IComplexSamplesListener,
        Listener<ComplexSamples>, ISourceEventListener
{
    private static final Logger mLog = LoggerFactory.getLogger(ActivityTriggeredWaveRecorder.class);

    private static final int PRE_BUFFER_DURATION_MS = 2000;
    private static final int HOLD_DURATION_MS = 10000;
    private static final int POWER_LOG_INTERVAL_BLOCKS = 250;

    private enum RecordingState { IDLE, RECORDING, HOLDING }

    private final String mChannelName;
    private final long mChannelFrequency;
    private final ChannelActivityDetector mActivityDetector;
    private final Path mRecordingBaseDir;

    private float mSampleRate;
    private AudioFormat mAudioFormat;
    private CircularSampleBuffer mCircularBuffer;
    private WaveWriter mWaveWriter;
    private RecordingState mRecordingState = RecordingState.IDLE;
    private long mHoldSamplesRemaining;
    private boolean mRunning;
    private boolean mErrorState;
    private EventBus mEventBus;
    private Path mRecordingDirectory;
    private long mLastPowerLogBlock;

    /**
     * Constructs an instance.
     * @param sampleRate initial sample rate
     * @param channelName for subdirectory and file naming
     * @param channelFrequency for file naming
     * @param triggerMarginDb that the in-channel power must exceed the channel noise floor by to trigger recording
     * @param passbandHalfWidthHz in-channel measurement passband either side of the channel centre
     * @param recordingBaseDir base recording directory from user preferences
     */
    public ActivityTriggeredWaveRecorder(float sampleRate, String channelName, long channelFrequency,
                                         float triggerMarginDb, double passbandHalfWidthHz, Path recordingBaseDir)
    {
        mSampleRate = sampleRate;
        mChannelName = channelName;
        mChannelFrequency = channelFrequency;
        mActivityDetector = new ChannelActivityDetector(sampleRate, passbandHalfWidthHz, triggerMarginDb);
        mRecordingBaseDir = recordingBaseDir;
        mAudioFormat = new AudioFormat(sampleRate, 16, 2, true, false);

        // Create per-channel recording subdirectory path
        String sanitized = sanitizeChannelName(channelName);
        mRecordingDirectory = recordingBaseDir.resolve(sanitized);

        initCircularBuffer();
    }

    /**
     * Sanitizes a channel name for use as a filesystem directory name.
     */
    private static String sanitizeChannelName(String name)
    {
        return StringUtils.replaceIllegalCharacters(name);
    }

    /**
     * Initializes or re-initializes the circular buffer based on current sample rate.
     */
    private void initCircularBuffer()
    {
        // At 25kHz with ~2048 sample buffers, 2 seconds needs ~24 entries.
        // Use a generous estimate: sampleRate * durationSec / typical buffer size
        int estimatedBuffers = Math.max(10, (int)(mSampleRate * PRE_BUFFER_DURATION_MS / 1000.0 / 1024.0) + 2);
        mCircularBuffer = new CircularSampleBuffer(estimatedBuffers);
    }

    @Override
    public void setInterModuleEventBus(EventBus eventBus)
    {
        mEventBus = eventBus;
    }

    @Override
    public void start()
    {
        mRunning = true;
        mErrorState = false;
        mRecordingState = RecordingState.IDLE;
        mActivityDetector.reset();
        initCircularBuffer();
    }

    @Override
    public void stop()
    {
        mRunning = false;
        closeActiveRecording();
    }

    @Override
    public void reset()
    {
        closeActiveRecording();
        mRecordingState = RecordingState.IDLE;
        mActivityDetector.reset();

        if(mCircularBuffer != null)
        {
            mCircularBuffer.clear();
        }
    }

    @Override
    public void receive(ComplexSamples complexSamples)
    {
        if(!mRunning || mErrorState)
        {
            return;
        }

        boolean active = mActivityDetector.receive(complexSamples);

        if(mActivityDetector.getBlockCount() - mLastPowerLogBlock >= POWER_LOG_INTERVAL_BLOCKS)
        {
            mLastPowerLogBlock = mActivityDetector.getBlockCount();
            mLog.info("Activity recorder [{}] in-channel power={} dB  floor={} dB  margin={} dB  state={}  active={}",
                    mChannelName, String.format("%.1f", mActivityDetector.getPowerDb()),
                    String.format("%.1f", mActivityDetector.getNoiseFloorDb()),
                    String.format("%.1f", mActivityDetector.getTriggerMarginDb()), mRecordingState, active);
        }

        switch(mRecordingState)
        {
            case IDLE:
                mCircularBuffer.add(complexSamples);

                if(active)
                {
                    startRecording();
                    writePreTriggerBuffer();
                    writeSamples(complexSamples);
                }
                break;

            case RECORDING:
                writeSamples(complexSamples);

                if(!active)
                {
                    mLog.info("Activity recorder [{}] RECORDING->HOLDING at power={} dB",
                            mChannelName, String.format("%.1f", mActivityDetector.getPowerDb()));
                    mRecordingState = RecordingState.HOLDING;
                    mHoldSamplesRemaining = (long)(mSampleRate * HOLD_DURATION_MS / 1000.0);
                }
                break;

            case HOLDING:
                writeSamples(complexSamples);
                mHoldSamplesRemaining -= complexSamples.length();

                if(active)
                {
                    mLog.info("Activity recorder [{}] HOLDING->RECORDING (re-trigger) at power={} dB",
                            mChannelName, String.format("%.1f", mActivityDetector.getPowerDb()));
                    mRecordingState = RecordingState.RECORDING;
                }
                else if(mHoldSamplesRemaining <= 0)
                {
                    mLog.info("Activity recorder [{}] HOLDING->IDLE (hold expired)", mChannelName);
                    closeActiveRecording();
                    mRecordingState = RecordingState.IDLE;
                }
                break;
        }
    }

    /**
     * Opens a new WAV file for recording.
     */
    private void startRecording()
    {
        try
        {
            // Create directory if needed
            if(!Files.exists(mRecordingDirectory))
            {
                Files.createDirectories(mRecordingDirectory);
            }

            // Build filename: timestamp_frequency_baseband.wav
            String filename = TimeStamp.getTimeStamp("_") + "_" + mChannelFrequency + "_baseband.wav";
            Path filePath = mRecordingDirectory.resolve(filename);

            mWaveWriter = new WaveWriter(mAudioFormat, filePath);
            mRecordingState = RecordingState.RECORDING;

            postRecordingEvent(true);

            mLog.info("Activity-triggered recording started: {}", filePath);
        }
        catch(IOException e)
        {
            mLog.error("Failed to start activity-triggered recording", e);
            mErrorState = true;
            mRecordingState = RecordingState.IDLE;
        }
    }

    /**
     * Writes the contents of the pre-trigger buffer to the active WAV file.
     */
    private void writePreTriggerBuffer()
    {
        List<ComplexSamples> buffered = mCircularBuffer.drain();

        for(ComplexSamples samples : buffered)
        {
            writeSamples(samples);
        }
    }

    /**
     * Writes a ComplexSamples buffer to the active WAV file.
     */
    private void writeSamples(ComplexSamples samples)
    {
        if(mWaveWriter == null)
        {
            return;
        }

        try
        {
            mWaveWriter.writeData(ConversionUtils.convertToSigned16BitSamples(samples));
        }
        catch(IOException e)
        {
            mLog.error("IOException writing activity-triggered recording — closing file", e);
            closeActiveRecording();
            mErrorState = true;
            mRecordingState = RecordingState.IDLE;
        }
    }

    /**
     * Closes the active WAV file and posts a recording-stopped event.
     */
    private void closeActiveRecording()
    {
        if(mWaveWriter != null)
        {
            try
            {
                mWaveWriter.close();
                mLog.info("Activity-triggered recording closed");
            }
            catch(IOException e)
            {
                mLog.error("Error closing activity-triggered recording", e);
            }

            mWaveWriter = null;
            postRecordingEvent(false);
        }
    }

    /**
     * Posts an ActivityRecordingEvent on the inter-module EventBus.
     */
    private void postRecordingEvent(boolean recording)
    {
        if(mEventBus != null)
        {
            mEventBus.post(new ActivityRecordingEvent(recording));
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return sourceEvent ->
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                float newRate = sourceEvent.getValue().floatValue();

                if(newRate != mSampleRate)
                {
                    mSampleRate = newRate;
                    mAudioFormat = new AudioFormat(newRate, 16, 2, true, false);
                    mActivityDetector.setSampleRate(newRate);

                    // If recording, close and restart with new format
                    if(mRecordingState != RecordingState.IDLE)
                    {
                        closeActiveRecording();
                        mRecordingState = RecordingState.IDLE;
                    }

                    initCircularBuffer();
                }
            }
        };
    }
}
