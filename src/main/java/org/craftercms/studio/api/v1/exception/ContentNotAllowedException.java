/*******************************************************************************
 * Crafter Studio Web-content authoring solution
 *     Copyright (C) 2007-2016 Crafter Software Corporation.
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.craftercms.studio.api.v1.exception;

/**
 * occurs when the content provided does not meet preconditions
 *  
 * @author hyanghee
 *
 */
public class ContentNotAllowedException extends ContentProcessException {

	/**
	 * 
	 */
	protected static final long serialVersionUID = -577471131674100243L;

	public ContentNotAllowedException() {}
	
	public ContentNotAllowedException(Exception e) {
		super(e);
	}
	
	public ContentNotAllowedException(String message) {
		super(message);
	}
	
	public ContentNotAllowedException(String message, Exception e) {
		super(message, e);
	}
}
