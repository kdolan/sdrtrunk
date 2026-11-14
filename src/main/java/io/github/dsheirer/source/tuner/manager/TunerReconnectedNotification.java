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
package io.github.dsheirer.source.tuner.manager;

/**
 * Global event bus notification that a tuner which was removed (ie USB dropout) has been found again and restarted.
 *
 * @param tunerId of the reconnected tuner
 * @param removedTimestamp when the tuner reported the removal, so that listeners can find the channels that the
 * removal stopped
 */
public record TunerReconnectedNotification(String tunerId, long removedTimestamp)
{
}
