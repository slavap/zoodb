/*
 * Copyright 2009-2012 Tilmann Z�schke. All rights reserved.
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
package org.zoodb.jdo.internal.server;


import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.zoodb.jdo.internal.SerialInput;
import org.zoodb.jdo.internal.server.index.BitTools;

public class StorageReader implements SerialInput, StorageChannelInput {

	//private static final int S_BOOL = 1;
	private static final int S_BYTE = 1;
	private static final int S_CHAR = 2;
	private static final int S_DOUBLE = 8;
	private static final int S_FLOAT = 4;
	private static final int S_INT = 4;
	private static final int S_LONG = 8;
	private static final int S_SHORT = 2;
	
	private final ByteBuffer buf;
	private int currentPage = -1;
	
	//indicate whether to automatically allocate and move to next page when page end is reached.
	private final boolean isAutoPaging;
	//The header is only written in auto-paging mode
	private long pageHeader = -1;
	
	private final int MAX_POS;
	
	private final StorageChannel root;
	private final IntBuffer intBuffer;
	private final int[] intArray;

	/**
	 * Use for creating an additional view on a given file.
	 * @param fc
	 * @param pageSize
	 * @param fsm
	 */
	StorageReader(StorageChannel root, boolean autoPaging) {
		this.root = root; 
		this.MAX_POS = root.getPageSize() - 4;
		this.isAutoPaging = autoPaging;
		
		buf = ByteBuffer.allocateDirect(root.getPageSize());
		currentPage = -1;
		intBuffer = buf.asIntBuffer();
		intArray = new int[intBuffer.capacity()];
	}

	/**
	 * To be called after every commit, to ensure that pages are reset, in case they have been 
	 * rewritten.
	 */
	@Override
	public void reset() {
		currentPage = -1;
	}
	
	@Override
	public void seekPageForRead(int pageId) {
	    seekPage(pageId, 0);
	}
	
	
	@Override
	public void seekPosAP(long pageAndOffs) {
		int page = BitTools.getPage(pageAndOffs);
		int offs = BitTools.getOffs(pageAndOffs);
		seekPage(page, offs);
	}

	@Override
	public void seekPage(int pageId, int pageOffset) {
		//isAutoPaging = autoPaging;

		if (pageId != currentPage) {
			currentPage = pageId;
			buf.clear();
			root.readPage(buf, pageId);
		}

		if (isAutoPaging) {
			buf.clear();
			pageHeader = buf.getLong();
			if (pageOffset==0) {
				pageOffset = 8; //TODO this is dirty...
			}
		}
		buf.position(pageOffset);
	}

	@Override
	public String readString() {
		checkPosRead(4);
		int len = buf.getInt();

		//Align for 2-byte writing
		int p = buf.position();
		if ((p & 0x00000001) == 1) {
			buf.position(p+1);
		}

		char[] array = new char[len];
		CharBuffer cb = buf.asCharBuffer();
		int l = array.length;
        int posA = 0; //position in array
        while (l > 0) {
            checkPosRead(2);
            int getLen = MAX_POS - buf.position();
            getLen = getLen >> 1;
            if (getLen > l) {
                getLen = l;
            }
            cb = buf.asCharBuffer(); //create a buffer at the correct position
            cb.get(array, posA, getLen);
            buf.position(buf.position()+getLen*2);
            posA += getLen;
            l -= getLen;
        }
        return String.valueOf(array);
	}

	
	@Override
	public boolean readBoolean() {
        return readByte() != 0;
	}

	@Override
	public byte readByte() {
		checkPosRead(S_BYTE);
		return buf.get();
	}

	@Override
	public char readChar() {
		if (!checkPos(S_CHAR)) {
			return readByteBuffer(S_CHAR).getChar();
		}
		return buf.getChar();
	}

	@Override
	public double readDouble() {
		if (!checkPos(S_DOUBLE)) {
			return Double.longBitsToDouble(readLong());
		}
		return buf.getDouble();
	}

	@Override
	public float readFloat() {
		if (!checkPos(S_FLOAT)) {
			return Float.intBitsToFloat(readInt());
		}
		return buf.getFloat();
	}

	@Override
	public void readFully(byte[] array) {
        int l = array.length;
        int posA = 0; //position in array
        while (l > 0) {
            checkPosRead(1);
            int getLen = MAX_POS - buf.position();
            if (getLen > l) {
                getLen = l;
            }
            buf.get(array, posA, getLen);
            posA += getLen;
            l -= getLen;
        }
	}

	@Override
	public void noCheckRead(long[] array) {
		LongBuffer lb = buf.asLongBuffer();
		lb.get(array);
	    buf.position(buf.position() + S_LONG * array.length);
	}
	
	@Override
	public void noCheckRead(int[] array) {
		IntBuffer lb = buf.asIntBuffer();
		lb.get(array);
	    buf.position(buf.position() + S_INT * array.length);
	}
	
	@Override
	public void noCheckRead(byte[] array) {
		buf.get(array);
	}
	
	@Override
	public void noCheckReadAsInt(long[] array, int nElements) {
		int pos = buf.position();
		if ((pos >> 2) << 2 == pos) {
			intBuffer.position(pos >> 2);
		} else {
			intBuffer.position((pos >> 2)+1);
		}
		intBuffer.get(intArray, 0, nElements);
		for (int i = 0; i < nElements; i++) {
			array[i] = intArray[i];
		}
	    buf.position(intBuffer.position() * S_INT);

	  //Alternative implementation (faster according to PerfTest but slower when running JUnit suite
//		for (int i = 0; i < nElements; i++) {
//			array[i] = buf.getInt();
//		}
	}
	
	@Override
	public int readInt() {
		if (!checkPos(S_INT)) {
			return readByteBuffer(S_INT).getInt();
		}
        return buf.getInt();
	}

	@Override
	public long readLong() {
		if (!checkPos(S_LONG)) {
			return readByteBuffer(S_LONG).getLong();
		}
//		checkPosRead(S_LONG);
		return buf.getLong();
	}

	@Override
	public short readShort() {
		if (!checkPos(S_SHORT)) {
			return readByteBuffer(S_SHORT).getShort();
		}
		return buf.getShort();
	}

	private ByteBuffer readByteBuffer(int len) {
		byte[] ba = new byte[len];
		readFully(ba);
		return ByteBuffer.wrap(ba);
	}
	
    @Override
    public long readLongAtOffset(int offset) {
        return buf.getLong(0);
    }
	
	private boolean checkPos(int delta) {
		//TODO remove autopaging, the indices use anyway the noCheckMethods!!
		//TODO -> otherwise, make it final, as it should be known when a view is constructed.
		if (isAutoPaging) {
			return (buf.position() + delta - MAX_POS) <= 0;
		}
		return true;
	}

	private void checkPosRead(int delta) {
		final ByteBuffer buf = this.buf;
		if (isAutoPaging && buf.position() + delta > MAX_POS) {
			final int pageId = buf.getInt();
			currentPage = pageId;
			buf.clear();
			root.readPage(buf, pageId);
			buf.rewind();
			//read header
			pageHeader = buf.getLong();
		}
 	}

    @Override
    public int getOffset() {
        return buf.position();
    }

    @Override
    public int getPage() {
        return currentPage;
    }
	
	@Override
	public void skipRead(int nBytes) {
        int l = nBytes;
        while (l > 0) {
            checkPosRead(1);
            int bPos = buf.position();
            int putLen = MAX_POS - bPos;
            if (putLen > l) {
                putLen = l;
            }
            buf.position(bPos + putLen);
            l -= putLen;
        }
	}
}
