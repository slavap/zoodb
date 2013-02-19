/*
 * Copyright 2009-2011 Tilmann Z�schke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.jdo.internal.util;

import java.util.logging.Logger;

public class DatabaseLogger {

	private static final Logger _LOGGER = 
		Logger.getLogger(DatabaseLogger.class.getName());

    private static int _verbosityLevel = -1;
    private static boolean _verboseToLog = false;
	
	/**
	 * Prints a debug message if the level is below or equal the 
	 * <code>verbosity</code> setting. The output can be
	 * redirected to the logging mechanism by setting the following
	 * property: <tt>verboseOutput = log</tt>.
	 * @param level
	 * @param message Message to print.
	 */
	public static final void debugPrint(int level, String ... message) {
		if (level <= _verbosityLevel) {
			FormattedStringBuilder buf = 
				new FormattedStringBuilder().append("Debug: ").append(message);
			if (_verboseToLog) {
				_LOGGER.info(buf.toString());
			} else {
				System.out.print(buf.toString());
			}
		}
	}

	/**
	 * Prints a debug message if the level is below or equal the 
	 * <code>verbosity</code> setting. The output can be
	 * redirected to the logging mechanism by setting the following
	 * property: <tt>verboseOutput = log</tt>.
	 * @param level
	 * @param message Message to print.
	 */
	public static final void debugPrintln(int level, String ... message) {
		debugPrint(level, message);
		if (level <= _verbosityLevel && !_verboseToLog) {
			System.out.println();
		}
	}

    public static void severe(String string) {
        System.err.println("SEVERE: " + string);
    }

    public static void warning(String string) {
        System.err.println("WARNING: " + string);
    }

    public static void info(String string) {
        System.out.println("INFO: " + string);
    }
}
