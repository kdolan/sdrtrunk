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

package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.gui.playlist.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.playlist.record.RecordConfigurationEditor;
import io.github.dsheirer.gui.playlist.source.FrequencyEditor;
import io.github.dsheirer.gui.playlist.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P25 Phase 1 channel configuration editor
 */
public class P25P1ConfigurationEditor extends ChannelConfigurationEditor
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P1ConfigurationEditor.class);
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private SourceConfigurationEditor mSourceConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private RecordConfigurationEditor mRecordConfigurationEditor;
    private ToggleSwitch mIgnoreDataCallsButton;
    private Spinner<Integer> mTrafficChannelPoolSizeSpinner;
    private Spinner<Integer> mNACSpinner;
    private Label mNACLabel;
    private SegmentedButton mModulationSegmentedButton;
    private ToggleButton mC4FMToggleButton;
    private ToggleButton mC4FMv2ToggleButton;
    private ToggleButton mLSMToggleButton;
    private ToggleButton mLSMv2ToggleButton;

    // Pipeline diagnostics
    private ToggleSwitch mPipelineDiagnosticsSwitch;
    private Label mPipelineDiagnosticsLabel;

    // LSM v2 channel options
    private ToggleSwitch mIgnoreEncryptionSwitch;
    private Label mIgnoreEncryptionLabel;
    private Spinner<Integer> mMaxImbeErrorsSpinner;
    private Label mMaxImbeErrorsLabel;
    private Spinner<Integer> mMaxBchErrorsSpinner;
    private Label mMaxBchErrorsLabel;
    private Spinner<Double> mCmaAcquisitionMuSpinner;
    private Label mCmaAcquisitionMuLabel;
    private Spinner<Double> mCmaTrackingMuSpinner;
    private Label mCmaTrackingMuLabel;
    private Spinner<Integer> mCmaGearShiftMsSpinner;
    private Label mCmaGearShiftMsLabel;

    // JMBE v2 audio controls
    private ToggleSwitch mJmbeAGCButton;
    private Slider mJmbeToneGainSlider;
    private Slider mJmbeNoiseGainSlider;

    /**
     * Constructs an instance
     * @param playlistManager for playlists
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    public P25P1ConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                    UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    private TitledPane getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new TitledPane("Source", getSourceConfigurationEditor());
            mSourcePane.setExpanded(true);
        }

        return mSourcePane;
    }

    private TitledPane getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new TitledPane();
            mDecoderPane.setText("Decoder: P25 Phase 1 (also used for P25 Phase 2 system with FDMA control channels)");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            int row = 0;

            Label modulationLabel = new Label("Modulation");
            GridPane.setHalignment(modulationLabel, HPos.RIGHT);
            GridPane.setConstraints(modulationLabel, 0, row);
            gridPane.getChildren().add(modulationLabel);

            GridPane.setConstraints(getModulationSegmentedButton(), 1, row);
            gridPane.getChildren().addAll(getModulationSegmentedButton());

            Label poolSizeLabel = new Label("Max Traffic Channels");
            GridPane.setHalignment(poolSizeLabel, HPos.RIGHT);
            GridPane.setConstraints(poolSizeLabel, 2, row);
            gridPane.getChildren().add(poolSizeLabel);

            GridPane.setConstraints(getTrafficChannelPoolSizeSpinner(), 3, row);
            gridPane.getChildren().add(getTrafficChannelPoolSizeSpinner());

            GridPane.setConstraints(getIgnoreDataCallsButton(), 4, row);
            gridPane.getChildren().add(getIgnoreDataCallsButton());

            Label directionLabel = new Label("Ignore Data Calls");
            GridPane.setHalignment(directionLabel, HPos.LEFT);
            GridPane.setConstraints(directionLabel, 5, row);
            gridPane.getChildren().add(directionLabel);

            // NAC configuration (row 1) - visible for all modulations
            mNACLabel = new Label("NAC (0=auto)");
            GridPane.setHalignment(mNACLabel, HPos.RIGHT);
            GridPane.setConstraints(mNACLabel, 0, 1);
            gridPane.getChildren().add(mNACLabel);

            GridPane.setConstraints(getNACSpinner(), 1, 1);
            gridPane.getChildren().add(getNACSpinner());

            Label modulationHelpLabel = new Label("C4FM: repeaters and non-simulcast trunked.  C4FM v2: C4FM with equalizer for multipath.  LSM: simulcast trunked.  LSM v2: conventional (PTT) CQPSK channels.");
            GridPane.setConstraints(modulationHelpLabel, 0, 2, 6, 1);
            gridPane.getChildren().add(modulationHelpLabel);

            // JMBE Audio controls (row 3)
            Label jmbeLabel = new Label("AMBE/IMBE Audio");
            GridPane.setHalignment(jmbeLabel, HPos.LEFT);
            gridPane.add(jmbeLabel, 0, 3);

            GridPane.setHalignment(getJmbeAGCButton(), HPos.LEFT);
            gridPane.add(getJmbeAGCButton(), 1, 3);

            gridPane.add(new Label("Tone Volume:"), 2, 3);

            GridPane.setHgrow(getJmbeToneGainSlider(), Priority.ALWAYS);
            gridPane.add(getJmbeToneGainSlider(), 3, 3);

            gridPane.add(new Label("Comfort Noise Volume:"), 4, 3);

            GridPane.setHgrow(getJmbeNoiseGainSlider(), Priority.ALWAYS);
            gridPane.add(getJmbeNoiseGainSlider(), 5, 3);

            // LSM v2 options (row 4) - only visible for LSM v2
            mIgnoreEncryptionLabel = new Label("Skip Encryption Check:");
            mIgnoreEncryptionLabel.setTooltip(new Tooltip("Start audio immediately without waiting for encryption status (for known unencrypted channels)"));
            GridPane.setHalignment(mIgnoreEncryptionLabel, HPos.RIGHT);
            GridPane.setConstraints(mIgnoreEncryptionLabel, 0, 4);
            gridPane.getChildren().add(mIgnoreEncryptionLabel);

            GridPane.setConstraints(getIgnoreEncryptionSwitch(), 1, 4);
            gridPane.getChildren().add(getIgnoreEncryptionSwitch());

            mMaxImbeErrorsLabel = new Label("Max IMBE Errors (0=off):");
            mMaxImbeErrorsLabel.setTooltip(new Tooltip("Pre-codec quality gate: frames exceeding this IMBE FEC error threshold are silenced.\n0 = disabled (default). Try 3 for simulcast channels with bimodal error patterns."));
            GridPane.setHalignment(mMaxImbeErrorsLabel, HPos.RIGHT);
            GridPane.setConstraints(mMaxImbeErrorsLabel, 2, 4, 2, 1);
            gridPane.getChildren().add(mMaxImbeErrorsLabel);

            GridPane.setConstraints(getMaxImbeErrorsSpinner(), 4, 4);
            gridPane.getChildren().add(getMaxImbeErrorsSpinner());

            // BCH threshold (row 5) - only visible for LSM v2
            mMaxBchErrorsLabel = new Label("BCH Threshold:");
            mMaxBchErrorsLabel.setTooltip(new Tooltip(
                "BCH error correction threshold for NAC-assisted NID decode.\n" +
                "5 = recommended default (good for most simulcast)\n" +
                "11 = accept all corrections (better for severe simulcast like LFD)\n" +
                "1-3 = strict (may miss valid frames)\n" +
                "Only active when NAC is configured."));
            GridPane.setHalignment(mMaxBchErrorsLabel, HPos.RIGHT);
            GridPane.setConstraints(mMaxBchErrorsLabel, 0, 5);
            gridPane.getChildren().add(mMaxBchErrorsLabel);

            GridPane.setConstraints(getMaxBchErrorsSpinner(), 1, 5);
            gridPane.getChildren().add(getMaxBchErrorsSpinner());

            // CMA Acquisition Mu (row 6) - only visible for LSM v2
            mCmaAcquisitionMuLabel = new Label("CMA Acq. Mu:");
            mCmaAcquisitionMuLabel.setTooltip(new Tooltip(
                "CMA equalizer acquisition step size.\n" +
                "Controls how fast the equalizer converges after cold-start.\n" +
                "0.000 = use system default\n" +
                "0.003 = recommended for severe simulcast (LFD, WPD)\n" +
                "0.001 = conservative (ROC W)"));
            GridPane.setHalignment(mCmaAcquisitionMuLabel, HPos.RIGHT);
            GridPane.setConstraints(mCmaAcquisitionMuLabel, 0, 6);
            gridPane.getChildren().add(mCmaAcquisitionMuLabel);

            GridPane.setConstraints(getCmaAcquisitionMuSpinner(), 1, 6);
            gridPane.getChildren().add(getCmaAcquisitionMuSpinner());

            // CMA Tracking Mu (row 7) - only visible for LSM v2
            mCmaTrackingMuLabel = new Label("CMA Trk. Mu:");
            mCmaTrackingMuLabel.setTooltip(new Tooltip(
                "CMA equalizer tracking step size.\n" +
                "Controls steady-state tracking accuracy after gear-shift.\n" +
                "0.000 = use system default\n" +
                "0.001 = recommended for all simulcast channels"));
            GridPane.setHalignment(mCmaTrackingMuLabel, HPos.RIGHT);
            GridPane.setConstraints(mCmaTrackingMuLabel, 0, 7);
            gridPane.getChildren().add(mCmaTrackingMuLabel);

            GridPane.setConstraints(getCmaTrackingMuSpinner(), 1, 7);
            gridPane.getChildren().add(getCmaTrackingMuSpinner());

            // CMA Gear Shift Ms (row 8) - only visible for LSM v2
            mCmaGearShiftMsLabel = new Label("CMA Shift (ms):");
            mCmaGearShiftMsLabel.setTooltip(new Tooltip(
                "Time before equalizer switches from acquisition to tracking mu.\n" +
                "0 = use system default (no gear-shifting)\n" +
                "200 = recommended for simulcast channels\n" +
                "Set all three CMA values to enable per-channel gear-shifting."));
            GridPane.setHalignment(mCmaGearShiftMsLabel, HPos.RIGHT);
            GridPane.setConstraints(mCmaGearShiftMsLabel, 0, 8);
            gridPane.getChildren().add(mCmaGearShiftMsLabel);

            GridPane.setConstraints(getCmaGearShiftMsSpinner(), 1, 8);
            gridPane.getChildren().add(getCmaGearShiftMsSpinner());

            // Pipeline diagnostics (row 9) - visible for all modulations
            mPipelineDiagnosticsLabel = new Label("Pipeline Diagnostics:");
            mPipelineDiagnosticsLabel.setTooltip(new Tooltip("Log decode pipeline events to SDRTrunk/logs/p25_logs/ for this channel.\nCaptures messages, state transitions, squelch, and boundary detection."));
            GridPane.setHalignment(mPipelineDiagnosticsLabel, HPos.RIGHT);
            GridPane.setConstraints(mPipelineDiagnosticsLabel, 0, 9);
            gridPane.getChildren().add(mPipelineDiagnosticsLabel);

            GridPane.setConstraints(getPipelineDiagnosticsSwitch(), 1, 9);
            gridPane.getChildren().add(getPipelineDiagnosticsSwitch());

            // Update visibility based on modulation selection
            updateLSMv2OptionsVisibility();

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
    }

    private TitledPane getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new TitledPane("Logging", getEventLogConfigurationEditor());
            mEventLogPane.setExpanded(false);
        }

        return mEventLogPane;
    }

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            Label notice = new Label("Note: use aliases to control call audio recording");
            notice.setPadding(new Insets(10, 10, 0, 10));

            VBox vBox = new VBox();
            vBox.getChildren().addAll(getRecordConfigurationEditor(), notice);

            mRecordPane.setContent(vBox);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_MINIMUM_MS,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_MAXIMUM_MS,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_DEFAULT_MS);

            //Add a listener so that we can push change notifications up to this editor
            mSourceConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mSourceConfigurationEditor;
    }

    private EventLogConfigurationEditor getEventLogConfigurationEditor()
    {
        if(mEventLogConfigurationEditor == null)
        {
            List<EventLogType> types = new ArrayList<>();
            types.add(EventLogType.CALL_EVENT);
            types.add(EventLogType.DECODED_MESSAGE);
            types.add(EventLogType.TRAFFIC_CALL_EVENT);
            types.add(EventLogType.TRAFFIC_DECODED_MESSAGE);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private SegmentedButton getModulationSegmentedButton()
    {
        if(mModulationSegmentedButton == null)
        {
            mModulationSegmentedButton = new SegmentedButton();
            mModulationSegmentedButton.getStyleClass().add(SegmentedButton.STYLE_CLASS_DARK);
            mModulationSegmentedButton.getButtons().addAll(getC4FMToggleButton(), getC4FMv2ToggleButton(), getLSMToggleButton(), getLSMv2ToggleButton());
            mModulationSegmentedButton.getToggleGroup().selectedToggleProperty().addListener(new ChangeListener<Toggle>()
            {
                @Override
                public void changed(ObservableValue<? extends Toggle> observable, Toggle oldValue, Toggle newValue)
                {
                    if(newValue == null)
                    {
                        //Ensure at least one toggle is always selected
                        oldValue.setSelected(true);
                    }
                    else if(oldValue != null && newValue != null)
                    {
                        //Only set modified if the toggle changed from one to the other
                        modifiedProperty().set(true);
                    }
                    //Update LSM v2 options visibility based on modulation
                    updateLSMv2OptionsVisibility();
                }
            });
        }

        return mModulationSegmentedButton;
    }

    public ToggleSwitch getJmbeAGCButton()
    {
        if(mJmbeAGCButton == null)
        {
            mJmbeAGCButton = new ToggleSwitch("AGC:");
            mJmbeAGCButton.setTooltip(new Tooltip("Automatic Gain Control to automatically adjust call audio volume"));
            mJmbeAGCButton.setDisable(true);
            mJmbeAGCButton.selectedProperty().addListener((o, old, n) -> modifiedProperty().set(true));
        }

        return mJmbeAGCButton;
    }

    public Slider getJmbeToneGainSlider()
    {
        if(mJmbeToneGainSlider == null)
        {
            mJmbeToneGainSlider = new Slider(0.0, 1.0, 1.0);
            mJmbeToneGainSlider.setTooltip(new Tooltip("Tone generation volume: 0.0 (no tones) to 1.0 (default). Note: Phase 2 AMBE only"));
            mJmbeToneGainSlider.setDisable(true);
            mJmbeToneGainSlider.valueProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mJmbeToneGainSlider;
    }

    public Slider getJmbeNoiseGainSlider()
    {
        if(mJmbeNoiseGainSlider == null)
        {
            mJmbeNoiseGainSlider = new Slider(0.0, 1.0, 1.0);
            mJmbeNoiseGainSlider.setTooltip(new Tooltip("Comfort noise insertion volume: 0.0 (no noise) to 1.0 (default)"));
            mJmbeNoiseGainSlider.setDisable(true);
            mJmbeNoiseGainSlider.valueProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mJmbeNoiseGainSlider;
    }

    private ToggleButton getC4FMToggleButton()
    {
        if(mC4FMToggleButton == null)
        {
            mC4FMToggleButton = new ToggleButton("C4FM");
        }

        return mC4FMToggleButton;
    }

    private ToggleButton getC4FMv2ToggleButton()
    {
        if(mC4FMv2ToggleButton == null)
        {
            mC4FMv2ToggleButton = new ToggleButton("C4FM v2");
        }

        return mC4FMv2ToggleButton;
    }

    private ToggleButton getLSMToggleButton()
    {
        if(mLSMToggleButton == null)
        {
            mLSMToggleButton = new ToggleButton("LSM");
        }

        return mLSMToggleButton;
    }

    private ToggleButton getLSMv2ToggleButton()
    {
        if(mLSMv2ToggleButton == null)
        {
            mLSMv2ToggleButton = new ToggleButton("LSM v2");
            mLSMv2ToggleButton.setTooltip(new Tooltip("LSM v2: improved cold-start for conventional (PTT) channels"));
        }

        return mLSMv2ToggleButton;
    }

    private ToggleSwitch getIgnoreDataCallsButton()
    {
        if(mIgnoreDataCallsButton == null)
        {
            mIgnoreDataCallsButton = new ToggleSwitch();
            mIgnoreDataCallsButton.setDisable(true);
            mIgnoreDataCallsButton.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mIgnoreDataCallsButton;
    }

    private Spinner<Integer> getTrafficChannelPoolSizeSpinner()
    {
        if(mTrafficChannelPoolSizeSpinner == null)
        {
            mTrafficChannelPoolSizeSpinner = new Spinner();
            mTrafficChannelPoolSizeSpinner.setDisable(true);
            mTrafficChannelPoolSizeSpinner.setTooltip(
                new Tooltip("Maximum number of traffic channels that can be created by the decoder"));
            mTrafficChannelPoolSizeSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50);
            mTrafficChannelPoolSizeSpinner.setValueFactory(svf);
            mTrafficChannelPoolSizeSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mTrafficChannelPoolSizeSpinner;
    }

    private Spinner<Integer> getNACSpinner()
    {
        if(mNACSpinner == null)
        {
            mNACSpinner = new Spinner();
            mNACSpinner.setDisable(true);
            mNACSpinner.setTooltip(new Tooltip("Network Access Code (NAC) for this channel. Set to 0 for auto-detect, " +
                    "or enter the known NAC (1-4095) for improved NID error correction."));
            mNACSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mNACSpinner.setEditable(true);
            mNACSpinner.setPrefWidth(100);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    DecodeConfigP25Phase1.NAC_MINIMUM, DecodeConfigP25Phase1.NAC_MAXIMUM, 0);
            mNACSpinner.setValueFactory(svf);
            mNACSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mNACSpinner;
    }

    /**
     * Updates the visibility of LSM v2 specific options based on the selected modulation.
     * NAC is shown for all modulations. Other options are LSM v2 only.
     */
    private void updateLSMv2OptionsVisibility()
    {
        boolean showLsmV2Options = getLSMv2ToggleButton().isSelected();
        boolean showCmaOptions = showLsmV2Options;

        // Skip encryption switch - LSM v2 only
        if(mIgnoreEncryptionLabel != null)
        {
            mIgnoreEncryptionLabel.setVisible(showLsmV2Options);
            mIgnoreEncryptionLabel.setManaged(showLsmV2Options);
        }
        getIgnoreEncryptionSwitch().setVisible(showLsmV2Options);
        getIgnoreEncryptionSwitch().setManaged(showLsmV2Options);

        // Max IMBE errors spinner - LSM v2 only
        if(mMaxImbeErrorsLabel != null)
        {
            mMaxImbeErrorsLabel.setVisible(showLsmV2Options);
            mMaxImbeErrorsLabel.setManaged(showLsmV2Options);
        }
        getMaxImbeErrorsSpinner().setVisible(showLsmV2Options);
        getMaxImbeErrorsSpinner().setManaged(showLsmV2Options);

        // BCH threshold spinner - LSM v2 only
        if(mMaxBchErrorsLabel != null)
        {
            mMaxBchErrorsLabel.setVisible(showLsmV2Options);
            mMaxBchErrorsLabel.setManaged(showLsmV2Options);
        }
        getMaxBchErrorsSpinner().setVisible(showLsmV2Options);
        getMaxBchErrorsSpinner().setManaged(showLsmV2Options);

        // CMA acquisition mu spinner - both LSM v2 and C4FM v2
        if(mCmaAcquisitionMuLabel != null)
        {
            mCmaAcquisitionMuLabel.setVisible(showCmaOptions);
            mCmaAcquisitionMuLabel.setManaged(showCmaOptions);
        }
        getCmaAcquisitionMuSpinner().setVisible(showCmaOptions);
        getCmaAcquisitionMuSpinner().setManaged(showCmaOptions);

        // CMA tracking mu spinner - both LSM v2 and C4FM v2
        if(mCmaTrackingMuLabel != null)
        {
            mCmaTrackingMuLabel.setVisible(showCmaOptions);
            mCmaTrackingMuLabel.setManaged(showCmaOptions);
        }
        getCmaTrackingMuSpinner().setVisible(showCmaOptions);
        getCmaTrackingMuSpinner().setManaged(showCmaOptions);

        // CMA gear shift ms spinner - both LSM v2 and C4FM v2
        if(mCmaGearShiftMsLabel != null)
        {
            mCmaGearShiftMsLabel.setVisible(showCmaOptions);
            mCmaGearShiftMsLabel.setManaged(showCmaOptions);
        }
        getCmaGearShiftMsSpinner().setVisible(showCmaOptions);
        getCmaGearShiftMsSpinner().setManaged(showCmaOptions);
    }

    private ToggleSwitch getPipelineDiagnosticsSwitch()
    {
        if(mPipelineDiagnosticsSwitch == null)
        {
            mPipelineDiagnosticsSwitch = new ToggleSwitch();
            mPipelineDiagnosticsSwitch.setDisable(true);
            mPipelineDiagnosticsSwitch.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mPipelineDiagnosticsSwitch;
    }

    private ToggleSwitch getIgnoreEncryptionSwitch()
    {
        if(mIgnoreEncryptionSwitch == null)
        {
            mIgnoreEncryptionSwitch = new ToggleSwitch();
            mIgnoreEncryptionSwitch.setDisable(true);
            mIgnoreEncryptionSwitch.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mIgnoreEncryptionSwitch;
    }

    private Spinner<Integer> getMaxImbeErrorsSpinner()
    {
        if(mMaxImbeErrorsSpinner == null)
        {
            mMaxImbeErrorsSpinner = new Spinner<>();
            mMaxImbeErrorsSpinner.setDisable(true);
            mMaxImbeErrorsSpinner.setTooltip(new Tooltip(
                "Pre-codec quality gate threshold.\n" +
                "0 = disabled (default, best for severe simulcast like LFD/WPD)\n" +
                "3 = recommended for moderate simulcast (ROC W)\n" +
                "Frames with more IMBE FEC errors than this are silenced.\n" +
                "Severe simulcast channels may need 0 (disabled) since most\n" +
                "frames arrive with high error counts."));
            mMaxImbeErrorsSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mMaxImbeErrorsSpinner.setPrefWidth(80);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                DecodeConfigP25Phase1.MAX_IMBE_ERRORS_MINIMUM, DecodeConfigP25Phase1.MAX_IMBE_ERRORS_MAXIMUM, 0);
            mMaxImbeErrorsSpinner.setValueFactory(svf);
            mMaxImbeErrorsSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mMaxImbeErrorsSpinner;
    }

    private Spinner<Integer> getMaxBchErrorsSpinner()
    {
        if(mMaxBchErrorsSpinner == null)
        {
            mMaxBchErrorsSpinner = new Spinner<>();
            mMaxBchErrorsSpinner.setDisable(true);
            mMaxBchErrorsSpinner.setTooltip(new Tooltip(
                "BCH error correction threshold for NAC-assisted NID decode.\n" +
                "5 = recommended for moderate simulcast (ROC W)\n" +
                "11 = accept all corrections (recommended for severe simulcast\n" +
                "     like LFD/WPD where heavy NID correction is needed)\n" +
                "1-3 = strict (may miss valid frames)"));
            mMaxBchErrorsSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mMaxBchErrorsSpinner.setPrefWidth(80);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                DecodeConfigP25Phase1.MAX_BCH_ERRORS_MINIMUM, DecodeConfigP25Phase1.MAX_BCH_ERRORS_MAXIMUM,
                DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
            mMaxBchErrorsSpinner.setValueFactory(svf);
            mMaxBchErrorsSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mMaxBchErrorsSpinner;
    }

    private Spinner<Double> getCmaAcquisitionMuSpinner()
    {
        if(mCmaAcquisitionMuSpinner == null)
        {
            mCmaAcquisitionMuSpinner = new Spinner<>();
            mCmaAcquisitionMuSpinner.setDisable(true);
            mCmaAcquisitionMuSpinner.setTooltip(new Tooltip(
                "CMA equalizer acquisition step size.\n" +
                "0.003 = default, fast convergence for simulcast\n" +
                "0.001 = conservative\n" +
                "0.000 = disable per-channel override (use system property)"));
            mCmaAcquisitionMuSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mCmaAcquisitionMuSpinner.setPrefWidth(80);
            SpinnerValueFactory<Double> svf = new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.000, 0.010, 0.003, 0.001);
            mCmaAcquisitionMuSpinner.setValueFactory(svf);
            mCmaAcquisitionMuSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mCmaAcquisitionMuSpinner;
    }

    private Spinner<Double> getCmaTrackingMuSpinner()
    {
        if(mCmaTrackingMuSpinner == null)
        {
            mCmaTrackingMuSpinner = new Spinner<>();
            mCmaTrackingMuSpinner.setDisable(true);
            mCmaTrackingMuSpinner.setTooltip(new Tooltip(
                "CMA equalizer tracking step size.\n" +
                "0.001 = default, stable tracking for simulcast\n" +
                "0.000 = disable per-channel override (use system property)"));
            mCmaTrackingMuSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mCmaTrackingMuSpinner.setPrefWidth(80);
            SpinnerValueFactory<Double> svf = new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.000, 0.010, 0.001, 0.001);
            mCmaTrackingMuSpinner.setValueFactory(svf);
            mCmaTrackingMuSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mCmaTrackingMuSpinner;
    }

    private Spinner<Integer> getCmaGearShiftMsSpinner()
    {
        if(mCmaGearShiftMsSpinner == null)
        {
            mCmaGearShiftMsSpinner = new Spinner<>();
            mCmaGearShiftMsSpinner.setDisable(true);
            mCmaGearShiftMsSpinner.setTooltip(new Tooltip(
                "CMA gear-shift timing in milliseconds.\n" +
                "200 = default, switches from acq to tracking mu after 200ms\n" +
                "0 = disable per-channel override (use system property)"));
            mCmaGearShiftMsSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            mCmaGearShiftMsSpinner.setPrefWidth(80);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 1000, 200, 50);
            mCmaGearShiftMsSpinner.setValueFactory(svf);
            mCmaGearShiftMsSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }
        return mCmaGearShiftMsSpinner;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            types.add(RecorderType.DEMODULATED_BIT_STREAM);
            types.add(RecorderType.MBE_CALL_SEQUENCE);
            types.add(RecorderType.TRAFFIC_BASEBAND);
            types.add(RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM);
            types.add(RecorderType.TRAFFIC_MBE_CALL_SEQUENCE);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        boolean enabled = config != null;
        getIgnoreDataCallsButton().setDisable(!enabled);
        getTrafficChannelPoolSizeSpinner().setDisable(!enabled);
        getNACSpinner().setDisable(!enabled);
        getIgnoreEncryptionSwitch().setDisable(!enabled);
        getMaxImbeErrorsSpinner().setDisable(!enabled);
        getMaxBchErrorsSpinner().setDisable(!enabled);
        getCmaAcquisitionMuSpinner().setDisable(!enabled);
        getCmaTrackingMuSpinner().setDisable(!enabled);
        getCmaGearShiftMsSpinner().setDisable(!enabled);
        getPipelineDiagnosticsSwitch().setDisable(!enabled);

        if(config instanceof DecodeConfigP25Phase1 decodeConfig)
        {
            getIgnoreDataCallsButton().setSelected(decodeConfig.getIgnoreDataCalls());
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(decodeConfig.getTrafficChannelPoolSize());
            getNACSpinner().getValueFactory().setValue(decodeConfig.getConfiguredNAC());
            getPipelineDiagnosticsSwitch().setSelected(decodeConfig.isPipelineDiagnostics());

            // LSM v2 options
            getIgnoreEncryptionSwitch().setSelected(decodeConfig.isIgnoreEncryptionState());
            getMaxImbeErrorsSpinner().getValueFactory().setValue(decodeConfig.getMaxImbeErrors());
            getMaxBchErrorsSpinner().getValueFactory().setValue(decodeConfig.getMaxBchErrors());
            getCmaAcquisitionMuSpinner().getValueFactory().setValue((double)decodeConfig.getCmaAcquisitionMu());
            getCmaTrackingMuSpinner().getValueFactory().setValue((double)decodeConfig.getCmaTrackingMu());
            getCmaGearShiftMsSpinner().getValueFactory().setValue(decodeConfig.getCmaGearShiftMs());

            getC4FMToggleButton().setSelected(false);
            getC4FMv2ToggleButton().setSelected(false);
            getLSMToggleButton().setSelected(false);
            getLSMv2ToggleButton().setSelected(false);
            switch(decodeConfig.getModulation())
            {
                case C4FM:
                    getC4FMToggleButton().setSelected(true);
                    break;
                case C4FM_V2:
                    getC4FMv2ToggleButton().setSelected(true);
                    break;
                case CQPSK_V2:
                    getLSMv2ToggleButton().setSelected(true);
                    break;
                default:
                    getLSMToggleButton().setSelected(true);
                    break;
            }
            updateLSMv2OptionsVisibility();
            getJmbeAGCButton().setDisable(false);
            getJmbeNoiseGainSlider().setDisable(false);
            getJmbeToneGainSlider().setDisable(false);
            getJmbeAGCButton().setSelected(decodeConfig.isAGC());
            getJmbeNoiseGainSlider().setValue(decodeConfig.getNoiseGain());
            getJmbeToneGainSlider().setValue(decodeConfig.getToneGain());
        }
        else
        {
            getIgnoreDataCallsButton().setSelected(false);
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(0);
            getNACSpinner().getValueFactory().setValue(0);
            getIgnoreEncryptionSwitch().setSelected(false);
            getMaxImbeErrorsSpinner().getValueFactory().setValue(0);
            getMaxBchErrorsSpinner().getValueFactory().setValue(DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
            getCmaAcquisitionMuSpinner().getValueFactory().setValue(0.0);
            getCmaTrackingMuSpinner().getValueFactory().setValue(0.0);
            getCmaGearShiftMsSpinner().getValueFactory().setValue(0);
            getPipelineDiagnosticsSwitch().setSelected(false);
            getJmbeAGCButton().setDisable(true);
            getJmbeNoiseGainSlider().setDisable(true);
            getJmbeToneGainSlider().setDisable(true);
            getJmbeAGCButton().setSelected(false);
            getJmbeNoiseGainSlider().setValue(0);
            getJmbeToneGainSlider().setValue(0);
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigP25Phase1 config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigP25Phase1 p1)
        {
            config = p1;
        }
        else
        {
            config = new DecodeConfigP25Phase1();
        }

        config.setIgnoreDataCalls(getIgnoreDataCallsButton().isSelected());
        config.setTrafficChannelPoolSize(getTrafficChannelPoolSizeSpinner().getValue());
        config.setConfiguredNAC(getNACSpinner().getValue());
        config.setPipelineDiagnostics(getPipelineDiagnosticsSwitch().isSelected());

        // LSM v2 options
        config.setIgnoreEncryptionState(getIgnoreEncryptionSwitch().isSelected());
        config.setMaxImbeErrors(getMaxImbeErrorsSpinner().getValue() != null ? getMaxImbeErrorsSpinner().getValue() : 0);
        config.setMaxBchErrors(getMaxBchErrorsSpinner().getValue() != null ? getMaxBchErrorsSpinner().getValue() : DecodeConfigP25Phase1.MAX_BCH_ERRORS_DEFAULT);
        config.setCmaAcquisitionMu(getCmaAcquisitionMuSpinner().getValue() != null ? getCmaAcquisitionMuSpinner().getValue().floatValue() : 0.0f);
        config.setCmaTrackingMu(getCmaTrackingMuSpinner().getValue() != null ? getCmaTrackingMuSpinner().getValue().floatValue() : 0.0f);
        config.setCmaGearShiftMs(getCmaGearShiftMsSpinner().getValue() != null ? getCmaGearShiftMsSpinner().getValue() : 0);

        if(getC4FMToggleButton().isSelected())
        {
            config.setModulation(Modulation.C4FM);
        }
        else if(getC4FMv2ToggleButton().isSelected())
        {
            config.setModulation(Modulation.C4FM_V2);
        }
        else if(getLSMv2ToggleButton().isSelected())
        {
            config.setModulation(Modulation.CQPSK_V2);
        }
        else
        {
            config.setModulation(Modulation.CQPSK);
        }

        config.setAGC(getJmbeAGCButton().selectedProperty().getValue());
        config.setNoiseGain((float)getJmbeNoiseGainSlider().getValue());
        config.setToneGain((float)getJmbeToneGainSlider().getValue());
        getItem().setDecodeConfiguration(config);
    }

    @Override
    protected void setEventLogConfiguration(EventLogConfiguration config)
    {
        getEventLogConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveEventLogConfiguration()
    {
        getEventLogConfigurationEditor().save();

        if(getEventLogConfigurationEditor().getItem().getLoggers().isEmpty())
        {
            getItem().setEventLogConfiguration(null);
        }
        else
        {
            getItem().setEventLogConfiguration(getEventLogConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setAuxDecoderConfiguration(AuxDecodeConfiguration config)
    {
        //no-op
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        //no-op
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        getRecordConfigurationEditor().setDisable(config == null);
        getRecordConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveRecordConfiguration()
    {
        getRecordConfigurationEditor().save();
        RecordConfiguration config = getRecordConfigurationEditor().getItem();
        getItem().setRecordConfiguration(config);
    }

    @Override
    protected void setSourceConfiguration(SourceConfiguration config)
    {
        getSourceConfigurationEditor().setSourceConfiguration(config);
    }

    @Override
    protected void saveSourceConfiguration()
    {
        getSourceConfigurationEditor().save();
        SourceConfiguration sourceConfiguration = getSourceConfigurationEditor().getSourceConfiguration();
        getItem().setSourceConfiguration(sourceConfiguration);
    }
}
