/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright 2015 Chiori-chan. All Right Reserved.
 */
package com.chiorichan.packet;

import java.nio.ByteBuffer;

/**
 * @author Chiori Greene
 * @email chiorigreene@gmail.com
 */
public class PacketException extends Exception
{
	private static final long serialVersionUID = 4715655487010741146L;
	
	private ByteBuffer buffer = null;
	
	public PacketException( String reason, ByteBuffer buffer )
	{
		super( reason );
		this.buffer = buffer;
	}
	
	public PacketException( String reason )
	{
		super( reason );
	}
	
	public void putBuffer( ByteBuffer data )
	{
		buffer = data;
	}
	
	public String hexDump()
	{
		return PacketUtils.hexDump( buffer, buffer.position() );
	}
	
	public boolean hasBuffer()
	{
		return buffer != null;
	}
}
