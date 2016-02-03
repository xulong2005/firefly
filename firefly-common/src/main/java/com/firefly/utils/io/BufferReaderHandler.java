package com.firefly.utils.io;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.firefly.utils.concurrent.CountingCallback;

public interface BufferReaderHandler {
	public void readBuffer(ByteBuffer buf, CountingCallback countingCallback, long count) throws IOException;
}
