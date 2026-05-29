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

package io.github.dsheirer.audio;

import io.github.dsheirer.identifier.radio.RadioIdentifier;

/**
 * Associates a source (FROM) radio identifier with the time offset, in seconds, from the start of an audio segment
 * at which the unit became active.  Used to construct the per-unit activity timeline (e.g. the rdio-scanner 'units'
 * array) for a call.
 *
 * @param radio the source (FROM) radio identifier that became active
 * @param offsetSeconds offset in seconds from the start of the audio segment when the unit became active
 */
public record UnitOffset(RadioIdentifier radio, double offsetSeconds)
{
}
