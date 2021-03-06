/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright 2015 Chiori-chan. All Right Reserved.
 */
package com.chiorichan;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufProcessor;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.internal.AppendableCharSequence;
import io.netty.util.internal.StringUtil;

import java.util.List;

public class Decoder extends ByteToMessageDecoder
{
	private static class HeaderParser implements ByteBufProcessor
	{
		private final int maxLength;
		private final AppendableCharSequence seq;
		private int size;
		
		HeaderParser( AppendableCharSequence seq, int maxLength )
		{
			this.seq = seq;
			this.maxLength = maxLength;
		}
		
		protected TooLongFrameException newException( int maxLength )
		{
			return new TooLongFrameException( "HTTP header is larger than " + maxLength + " bytes." );
		}
		
		public AppendableCharSequence parse( ByteBuf buffer )
		{
			seq.reset();
			int i = buffer.forEachByte( this );
			if ( i == -1 )
				return null;
			buffer.readerIndex( i + 1 );
			return seq;
		}
		
		@Override
		public boolean process( byte value ) throws Exception
		{
			char nextByte = ( char ) value;
			if ( nextByte == HttpConstants.CR )
				return true;
			if ( nextByte == HttpConstants.LF )
				return false;
			if ( size >= maxLength )
				// TODO: Respond with Bad Request and discard the traffic
				// or close the connection.
				// No need to notify the upstream handlers - just log.
				// If decoding a response, just throw an exception.
				throw newException( maxLength );
			size++;
			seq.append( nextByte );
			return true;
		}
		
		public void reset()
		{
			size = 0;
		}
	}
	
	private static final class LineParser extends HeaderParser
	{
		
		LineParser( AppendableCharSequence seq, int maxLength )
		{
			super( seq, maxLength );
		}
		
		@Override
		protected TooLongFrameException newException( int maxLength )
		{
			return new TooLongFrameException( "An HTTP line is larger than " + maxLength + " bytes." );
		}
		
		@Override
		public AppendableCharSequence parse( ByteBuf buffer )
		{
			reset();
			return super.parse( buffer );
		}
	}
	/**
	 * The internal state of {@link HttpObjectDecoder}. <em>Internal use only</em>.
	 */
	private enum State
	{
		BAD_MESSAGE, READ_HEADER, READ_INITIAL, READ_NEXT, SKIP_CONTROL_CHARS, UPGRADED
	}
	private static final String EMPTY_VALUE = "";
	private final boolean chunkedSupported;
	private long chunkSize;
	
	private long contentLength = Long.MIN_VALUE;
	private State currentState = State.SKIP_CONTROL_CHARS;
	private final HeaderParser headerParser;
	private final LineParser lineParser;
	
	private final int maxChunkSize;
	private HttpMessage message;
	
	private String method = null;
	
	// These will be updated by splitHeader(...)
	private CharSequence name;
	
	private volatile boolean resetRequested;
	
	private LastHttpContent trailer;
	
	private String uri = null;
	
	protected final boolean validateHeaders;
	
	private CharSequence value;
	private String version = null;
	/**
	 * Creates a new instance with the default {@code maxInitialLineLength (4096} , {@code maxHeaderSize (8192)}, and {@code maxChunkSize (8192)}.
	 */
	protected Decoder()
	{
		this( 4096, 8192, 8192, true );
	}
	
	/**
	 * Creates a new instance with the specified parameters.
	 */
	protected Decoder( int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean chunkedSupported )
	{
		this( maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, true );
	}
	
	/**
	 * Creates a new instance with the specified parameters.
	 */
	protected Decoder( int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean chunkedSupported, boolean validateHeaders )
	{
		
		if ( maxInitialLineLength <= 0 )
			throw new IllegalArgumentException( "maxInitialLineLength must be a positive integer: " + maxInitialLineLength );
		if ( maxHeaderSize <= 0 )
			throw new IllegalArgumentException( "maxHeaderSize must be a positive integer: " + maxHeaderSize );
		if ( maxChunkSize <= 0 )
			throw new IllegalArgumentException( "maxChunkSize must be a positive integer: " + maxChunkSize );
		this.maxChunkSize = maxChunkSize;
		this.chunkedSupported = chunkedSupported;
		this.validateHeaders = validateHeaders;
		AppendableCharSequence seq = new AppendableCharSequence( 128 );
		lineParser = new LineParser( seq, maxInitialLineLength );
		headerParser = new HeaderParser( seq, maxHeaderSize );
	}
	
	private static int findEndOfString( CharSequence sb )
	{
		int result;
		for ( result = sb.length(); result > 0; result-- )
			if ( !Character.isWhitespace( sb.charAt( result - 1 ) ) )
				break;
		return result;
	}
	
	private static int findNonWhitespace( CharSequence sb, int offset )
	{
		int result;
		for ( result = offset; result < sb.length(); result++ )
			if ( !Character.isWhitespace( sb.charAt( result ) ) )
				break;
		return result;
	}
	
	private static int findWhitespace( CharSequence sb, int offset )
	{
		int result;
		for ( result = offset; result < sb.length(); result++ )
			if ( Character.isWhitespace( sb.charAt( result ) ) )
				break;
		return result;
	}
	
	private static int getChunkSize( String hex )
	{
		hex = hex.trim();
		for ( int i = 0; i < hex.length(); i++ )
		{
			char c = hex.charAt( i );
			if ( c == ';' || Character.isWhitespace( c ) || Character.isISOControl( c ) )
			{
				hex = hex.substring( 0, i );
				break;
			}
		}
		
		return Integer.parseInt( hex, 16 );
	}
	
	private static boolean skipControlCharacters( ByteBuf buffer )
	{
		boolean skiped = false;
		final int wIdx = buffer.writerIndex();
		int rIdx = buffer.readerIndex();
		while ( wIdx > rIdx )
		{
			int c = buffer.getUnsignedByte( rIdx++ );
			if ( !Character.isISOControl( c ) && !Character.isWhitespace( c ) )
			{
				rIdx--;
				skiped = true;
				break;
			}
		}
		buffer.readerIndex( rIdx );
		return skiped;
	}
	
	private long contentLength()
	{
		if ( contentLength == Long.MIN_VALUE )
			contentLength = HttpHeaderUtil.getContentLength( message, -1 );
		return contentLength;
	}
	
	protected HttpMessage createInvalidMessage()
	{
		return new DefaultFullHttpRequest( HttpVersion.HTTP_1_0, HttpMethod.GET, "/bad-request", validateHeaders );
	}
	
	protected HttpMessage createMessage( String[] initialLine ) throws Exception
	{
		return new DefaultHttpRequest( HttpVersion.valueOf( initialLine[2] ), HttpMethod.valueOf( initialLine[0] ), initialLine[1], validateHeaders );
	}
	
	@Override
	protected void decode( ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out ) throws Exception
	{
		if ( resetRequested )
			resetNow();
		
		switch ( currentState )
		{
			case SKIP_CONTROL_CHARS:
			{
				if ( !skipControlCharacters( buffer ) )
					return;
				state( State.READ_INITIAL );
			}
			case READ_INITIAL:
				try
				{
					AppendableCharSequence line = lineParser.parse( buffer );
					if ( line == null )
						return;
					
					String[] initialLine = StringUtil.split( line.toString(), ' ' );
					if ( initialLine.length == 1 && initialLine[0].toUpperCase().startsWith( "HTTP" ) )
						version = initialLine[0];
					else if ( initialLine.length == 2 && initialLine[0].equalsIgnoreCase( "GET" ) )
					{
						method = initialLine[0];
						uri = initialLine[1];
					}
					else if ( initialLine.length == 3 )
					{
						method = initialLine[0];
						uri = initialLine[1];
						version = initialLine[2];
					}
					
					if ( uri != null && version != null )
					{
						message = new DefaultHttpRequest( HttpVersion.valueOf( version ), HttpMethod.valueOf( method ), uri, validateHeaders );
						state( State.READ_HEADER );
						// fall-through
					}
					else
					{
						state( State.SKIP_CONTROL_CHARS );
						return;
					}
				}
				catch ( Exception e )
				{
					out.add( invalidMessage( e ) );
					return;
				}
			case READ_HEADER:
				try
				{
					State nextState = readHeaders( buffer );
					if ( nextState == null )
						return;
					
					state( nextState );
					
					if ( nextState == State.SKIP_CONTROL_CHARS )
					{
						// fast-path
						// No content is expected.
						out.add( message );
						out.add( LastHttpContent.EMPTY_LAST_CONTENT );
						
						resetNow();
						return;
					}
					
					out.add( message );
					
					// chunkSize = contentLength;
					
					// We return here, this forces decode to be called again where we will decode the content
					return;
				}
				catch ( Exception e )
				{
					out.add( invalidMessage( e ) );
					return;
				}
			case READ_NEXT:
			{
				int toRead = actualReadableBytes();
				if ( toRead > 0 )
					out.add( new DefaultHttpContent( ByteBufUtil.readBytes( ctx.alloc(), buffer, toRead ) ) );
				else if ( !buffer.isReadable() )
					out.add( LastHttpContent.EMPTY_LAST_CONTENT );
				break;
			}
			case BAD_MESSAGE:
			{
				// Keep discarding until disconnection.
				buffer.skipBytes( buffer.readableBytes() );
				break;
			}
			case UPGRADED:
			{
				int readableBytes = buffer.readableBytes();
				if ( readableBytes > 0 )
					// Keep on consuming as otherwise we may trigger an DecoderException,
					// other handler will replace this codec with the upgraded protocol codec to
					// take the traffic over at some point then.
					// See https://github.com/netty/netty/issues/2173
					out.add( buffer.readBytes( readableBytes ) );
				break;
			}
		}
	}
	
	@Override
	protected void decodeLast( ChannelHandlerContext ctx, ByteBuf in, List<Object> out ) throws Exception
	{
		decode( ctx, in, out );
		
		// Handle the last unfinished message.
		if ( message != null )
		{
			boolean chunked = HttpHeaderUtil.isTransferEncodingChunked( message );
			if ( currentState == State.READ_NEXT && !in.isReadable() && !chunked )
			{
				// End of connection.
				out.add( LastHttpContent.EMPTY_LAST_CONTENT );
				reset();
				return;
			}
			// Check if the closure of the connection signifies the end of the content.
			boolean prematureClosure;
			if ( isDecodingRequest() || chunked )
				// The last request did not wait for a response.
				prematureClosure = true;
			else
				// Compare the length of the received content and the 'Content-Length' header.
				// If the 'Content-Length' header is absent, the length of the content is determined by the end of the
				// connection, so it is perfectly fine.
				prematureClosure = contentLength() > 0;
			resetNow();
			
			if ( !prematureClosure )
				out.add( LastHttpContent.EMPTY_LAST_CONTENT );
		}
	}
	
	private HttpMessage invalidMessage( Exception cause )
	{
		currentState = State.BAD_MESSAGE;
		if ( message != null )
			message.setDecoderResult( DecoderResult.failure( cause ) );
		else
		{
			message = createInvalidMessage();
			message.setDecoderResult( DecoderResult.failure( cause ) );
		}
		
		HttpMessage ret = message;
		message = null;
		return ret;
	}
	
	protected boolean isContentAlwaysEmpty( HttpMessage msg )
	{
		if ( msg instanceof HttpResponse )
		{
			HttpResponse res = ( HttpResponse ) msg;
			int code = res.status().code();
			
			// Correctly handle return codes of 1xx.
			//
			// See:
			// - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
			// - https://github.com/netty/netty/issues/222
			if ( code >= 100 && code < 200 )
				// One exception: Hixie 76 websocket handshake response
				return ! ( code == 101 && !res.headers().contains( HttpHeaderNames.SEC_WEBSOCKET_ACCEPT ) );
			
			switch ( code )
			{
				case 204:
				case 205:
				case 304:
					return true;
			}
		}
		return false;
	}
	
	protected boolean isDecodingRequest()
	{
		return true;
	}
	
	private State readHeaders( ByteBuf buffer )
	{
		final HttpMessage message = this.message;
		final HttpHeaders headers = message.headers();
		
		AppendableCharSequence line = headerParser.parse( buffer );
		if ( line == null )
			return null;
		if ( line.length() > 0 )
			do
			{
				char firstChar = line.charAt( 0 );
				if ( name != null && ( firstChar == ' ' || firstChar == '\t' ) )
				{
					StringBuilder buf = new StringBuilder( value.length() + line.length() + 1 );
					buf.append( value ).append( ' ' ).append( line.toString().trim() );
					value = buf.toString();
				}
				else
				{
					if ( name != null )
						headers.add( name, value );
					splitHeader( line );
				}
				
				line = headerParser.parse( buffer );
				if ( line == null )
					return null;
			}
			while ( line.length() > 0 );
		
		// Add the last header.
		if ( name != null )
			headers.add( name, value );
		// reset name and value fields
		name = null;
		value = null;
		
		State nextState;
		
		if ( isContentAlwaysEmpty( message ) )
		{
			HttpHeaderUtil.setTransferEncodingChunked( message, false );
			nextState = State.SKIP_CONTROL_CHARS;
		}
		else
			nextState = State.READ_NEXT;
		return nextState;
	}
	
	private LastHttpContent readTrailingHeaders( ByteBuf buffer )
	{
		AppendableCharSequence line = headerParser.parse( buffer );
		if ( line == null )
			return null;
		CharSequence lastHeader = null;
		if ( line.length() > 0 )
		{
			LastHttpContent trailer = this.trailer;
			if ( trailer == null )
				trailer = this.trailer = new DefaultLastHttpContent( Unpooled.EMPTY_BUFFER, validateHeaders );
			do
			{
				char firstChar = line.charAt( 0 );
				if ( lastHeader != null && ( firstChar == ' ' || firstChar == '\t' ) )
				{
					List<CharSequence> current = trailer.trailingHeaders().getAll( lastHeader );
					if ( !current.isEmpty() )
					{
						int lastPos = current.size() - 1;
						String lineTrimmed = line.toString().trim();
						CharSequence currentLastPos = current.get( lastPos );
						StringBuilder b = new StringBuilder( currentLastPos.length() + lineTrimmed.length() );
						b.append( currentLastPos ).append( lineTrimmed );
						current.set( lastPos, b.toString() );
					}
					else
					{
						// Content-Length, Transfer-Encoding, or Trailer
					}
				}
				else
				{
					splitHeader( line );
					CharSequence headerName = name;
					if ( !HttpHeaderNames.CONTENT_LENGTH.equalsIgnoreCase( headerName ) && !HttpHeaderNames.TRANSFER_ENCODING.equalsIgnoreCase( headerName ) && !HttpHeaderNames.TRAILER.equalsIgnoreCase( headerName ) )
						trailer.trailingHeaders().add( headerName, value );
					lastHeader = name;
					// reset name and value fields
					name = null;
					value = null;
				}
				
				line = headerParser.parse( buffer );
				if ( line == null )
					return null;
			}
			while ( line.length() > 0 );
			
			this.trailer = null;
			return trailer;
		}
		
		return LastHttpContent.EMPTY_LAST_CONTENT;
	}
	
	/**
	 * Resets the state of the decoder so that it is ready to decode a new message.
	 * This method is useful for handling a rejected request with {@code Expect: 100-continue} header.
	 */
	public void reset()
	{
		resetRequested = true;
	}
	
	private void resetNow()
	{
		// System.out.println( "State Reset --> " + Thread.currentThread().getStackTrace()[2] );
		
		HttpMessage message = this.message;
		this.message = null;
		name = null;
		value = null;
		contentLength = Long.MIN_VALUE;
		lineParser.reset();
		headerParser.reset();
		trailer = null;
		if ( !isDecodingRequest() )
		{
			HttpResponse res = ( HttpResponse ) message;
			if ( res != null && res.status().code() == 101 )
			{
				state( State.UPGRADED );
				return;
			}
		}
		
		state( State.SKIP_CONTROL_CHARS );
	}
	
	private void splitHeader( AppendableCharSequence sb )
	{
		final int length = sb.length();
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;
		
		nameStart = findNonWhitespace( sb, 0 );
		for ( nameEnd = nameStart; nameEnd < length; nameEnd++ )
		{
			char ch = sb.charAt( nameEnd );
			if ( ch == ':' || Character.isWhitespace( ch ) )
				break;
		}
		
		for ( colonEnd = nameEnd; colonEnd < length; colonEnd++ )
			if ( sb.charAt( colonEnd ) == ':' )
			{
				colonEnd++;
				break;
			}
		
		name = sb.substring( nameStart, nameEnd );
		valueStart = findNonWhitespace( sb, colonEnd );
		if ( valueStart == length )
			value = EMPTY_VALUE;
		else
		{
			valueEnd = findEndOfString( sb );
			value = sb.substring( valueStart, valueEnd );
		}
	}
	
	protected void state( State state )
	{
		currentState = state;
		// System.out.println( "The current Decoder State is " + state.name() + " --> " + Thread.currentThread().getStackTrace()[2] );
	}
}
