/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2020 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.gui.playlist.record;

import io.github.dsheirer.gui.control.AccessibleToggleSwitch;
import io.github.dsheirer.gui.playlist.Editor;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.controlsfx.control.ToggleSwitch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Editor for event logging configuration objects using a VBox to visually display a vertical list of boolean toggle
 * switches to turn on/off event logging for a custom set of event log types.
 */
public class RecordConfigurationEditor extends Editor<RecordConfiguration>
{
    public static final String ACTIVITY_MARGIN_TOOLTIP = "Activity-triggered recording captures a clip when the power " +
        "on this channel rises this many dB above the channel's noise floor.  Only the centre of the channel is " +
        "measured, so traffic on neighbouring channels doesn't trigger it.  5 dB catches even transmissions too weak " +
        "to decode; raise it to record only stronger signals.";

    private List<RecorderControl> mControls = new ArrayList<>();
    private ToggleSwitch mActivityToggle;
    private Spinner<Integer> mThresholdSpinner;
    private Label mThresholdLabel;

    public RecordConfigurationEditor(Collection<RecorderType> types)
    {
        for(RecorderType type: types)
        {
            RecorderControl control = new RecorderControl(type);
            mControls.add(control);
            getChildren().add(control);
        }

        // Activity-triggered recording section
        getChildren().add(new Separator());

        mActivityToggle = new ToggleSwitch();
        mActivityToggle.setDisable(true);
        mActivityToggle.selectedProperty().addListener((observable, oldValue, newValue) -> {
            modifiedProperty().set(true);
            updateThresholdVisibility(newValue);
        });

        GridPane activityPane = new GridPane();
        activityPane.setPadding(new Insets(5, 5, 5, 0));
        activityPane.setHgap(10);
        GridPane.setConstraints(mActivityToggle, 0, 0);
        activityPane.getChildren().add(mActivityToggle);
        Label activityLabel = new Label("Activity-Triggered Baseband Recording");
        GridPane.setHalignment(activityLabel, HPos.LEFT);
        GridPane.setConstraints(activityLabel, 1, 0);
        activityPane.getChildren().add(activityLabel);
        getChildren().add(activityPane);

        mThresholdLabel = new Label("Trigger Above Channel Noise (dB):");
        mThresholdSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            (int)RecordConfiguration.MIN_ACTIVITY_TRIGGER_MARGIN_DB, (int)RecordConfiguration.MAX_ACTIVITY_TRIGGER_MARGIN_DB,
            (int)RecordConfiguration.DEFAULT_ACTIVITY_TRIGGER_MARGIN_DB, 1));
        mThresholdSpinner.setEditable(true);
        mThresholdSpinner.setPrefWidth(80);
        mThresholdSpinner.setTooltip(new Tooltip(ACTIVITY_MARGIN_TOOLTIP));
        mThresholdSpinner.valueProperty().addListener((obs, oldVal, newVal) -> modifiedProperty().set(true));

        HBox thresholdBox = new HBox(10, mThresholdLabel, mThresholdSpinner);
        thresholdBox.setPadding(new Insets(0, 5, 5, 25));
        getChildren().add(thresholdBox);

        updateThresholdVisibility(false);
    }

    private void updateThresholdVisibility(boolean visible)
    {
        mThresholdLabel.setVisible(visible);
        mThresholdLabel.setManaged(visible);
        mThresholdSpinner.setVisible(visible);
        mThresholdSpinner.setManaged(visible);
    }

    @Override
    public void setItem(RecordConfiguration item)
    {
        if(item == null)
        {
            item = new RecordConfiguration();
        }

        super.setItem(item);

        for(RecorderControl control: mControls)
        {
            control.getToggleSwitch().setDisable(false);
            control.getToggleSwitch().setSelected(item.getRecorders().contains(control.getRecorderType()));
        }

        mActivityToggle.setDisable(false);
        mActivityToggle.setSelected(item.isActivityTriggeredRecording());
        mThresholdSpinner.getValueFactory().setValue(Math.round(item.getActivityTriggerMarginDb()));
        updateThresholdVisibility(item.isActivityTriggeredRecording());

        modifiedProperty().set(false);
    }

    @Override
    public void save()
    {
        RecordConfiguration config = getItem();

        if(config == null)
        {
            config = new RecordConfiguration();
        }

        config.clearRecorders();

        for(RecorderControl control: mControls)
        {
            if(control.getToggleSwitch().isSelected())
            {
                config.addRecorder(control.getRecorderType());
            }
        }

        config.setActivityTriggeredRecording(mActivityToggle.isSelected());

        if(mActivityToggle.isSelected())
        {
            config.setActivityTriggerMarginDb(mThresholdSpinner.getValue().floatValue());
        }

        setItem(config);
    }

    @Override
    public void dispose()
    {

    }

    public class RecorderControl extends GridPane
    {
        private RecorderType mRecorderType;
        private ToggleSwitch mToggleSwitch;

        public RecorderControl(RecorderType type)
        {
            mRecorderType = type;

            setPadding(new Insets(5,5,5,0));
            setHgap(10);

            GridPane.setConstraints(getToggleSwitch(), 0, 0);
            getChildren().add(getToggleSwitch());

            Label label = new Label(mRecorderType.getDisplayString());
            label.setLabelFor(getToggleSwitch());
            GridPane.setHalignment(label, HPos.LEFT);
            GridPane.setConstraints(label, 1, 0);
            getChildren().add(label);
        }

        private ToggleSwitch getToggleSwitch()
        {
            if(mToggleSwitch == null)
            {
                mToggleSwitch = new AccessibleToggleSwitch();
                mToggleSwitch.setDisable(true);
                mToggleSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
            }

            return mToggleSwitch;
        }

        public RecorderType getRecorderType()
        {
            return mRecorderType;
        }
    }
}
