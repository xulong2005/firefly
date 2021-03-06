package com.firefly.codec.http2.stream;

import com.firefly.codec.http2.encode.HttpGenerator;
import com.firefly.codec.http2.model.HttpHeader;
import com.firefly.codec.http2.model.MetaData;
import com.firefly.net.Session;
import com.firefly.utils.io.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;

abstract public class AbstractHTTP1OutputStream extends HTTPOutputStream {

    public AbstractHTTP1OutputStream(MetaData info, boolean clientMode) {
        super(info, clientMode);
    }

    @Override
    public synchronized void writeWithContentLength(ByteBuffer[] data) throws IOException {
        try {
            long contentLength = 0;
            for (ByteBuffer buf : data) {
                contentLength += buf.remaining();
            }
            info.getFields().put(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
            for (ByteBuffer buf : data) {
                write(buf);
            }
        } finally {
            close();
        }
    }

    @Override
    public synchronized void writeWithContentLength(ByteBuffer data) throws IOException {
        try {
            info.getFields().put(HttpHeader.CONTENT_LENGTH, String.valueOf(data.remaining()));
            write(data);
        } finally {
            close();
        }
    }

    @Override
    public void commit() throws IOException {
        commit(null);
    }

    protected synchronized void commit(ByteBuffer data) throws IOException {
        if (closed)
            return;

        if (commited)
            return;

        final HttpGenerator generator = getHttpGenerator();
        final Session tcpSession = getSession();
        HttpGenerator.Result generatorResult;
        ByteBuffer header = getHeaderByteBuffer();

        generatorResult = generate(info, header, null, data, false);
        if (generatorResult == HttpGenerator.Result.FLUSH && generator.getState() == HttpGenerator.State.COMMITTED) {
            tcpSession.encode(header);
            if (data != null) {
                tcpSession.encode(data);
            }
            commited = true;
        } else {
            generateHTTPMessageExceptionally(generatorResult, generator.getState());
        }
    }

    @Override
    public synchronized void write(ByteBuffer data) throws IOException {
        if (closed)
            return;

        if (!data.hasRemaining())
            return;

        final HttpGenerator generator = getHttpGenerator();
        final Session tcpSession = getSession();
        HttpGenerator.Result generatorResult;

        if (!commited) {
            commit(data);
        } else {
            if (generator.isChunking()) {
                ByteBuffer chunk = BufferUtils.allocate(HttpGenerator.CHUNK_SIZE);

                generatorResult = generate(null, null, chunk, data, false);
                if (generatorResult == HttpGenerator.Result.FLUSH && generator.getState() == HttpGenerator.State.COMMITTED) {
                    tcpSession.encode(chunk);
                    tcpSession.encode(data);
                } else {
                    generateHTTPMessageExceptionally(generatorResult, generator.getState());
                }
            } else {
                generatorResult = generate(null, null, null, data, false);
                if (generatorResult == HttpGenerator.Result.FLUSH && generator.getState() == HttpGenerator.State.COMMITTED) {
                    tcpSession.encode(data);
                } else {
                    generateHTTPMessageExceptionally(generatorResult, generator.getState());
                }
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed)
            return;

        try {
            log.debug("http1 output stream is closing");
            final HttpGenerator generator = getHttpGenerator();
            final Session tcpSession = getSession();
            HttpGenerator.Result generatorResult;

            if (!commited) {
                ByteBuffer header = getHeaderByteBuffer();
                generatorResult = generate(info, header, null, null, true);
                if (generatorResult == HttpGenerator.Result.FLUSH && generator.getState() == HttpGenerator.State.COMPLETING) {
                    tcpSession.encode(header);
                    generateLastData(generator);
                } else {
                    generateHTTPMessageExceptionally(generatorResult, generator.getState());
                }
                commited = true;
            } else {
                if (generator.isChunking()) {
                    log.debug("http1 output stream is generating chunk");
                    ByteBuffer chunk = BufferUtils.allocate(HttpGenerator.CHUNK_SIZE);
                    generatorResult = generate(null, null, chunk, null, true);
                    if (generatorResult == HttpGenerator.Result.CONTINUE && generator.getState() == HttpGenerator.State.COMPLETING) {
                        generatorResult = generate(null, null, chunk, null, true);
                        if (generatorResult == HttpGenerator.Result.FLUSH && generator.getState() == HttpGenerator.State.COMPLETING) {
                            tcpSession.encode(chunk);
                            generateLastData(generator);
                        } else {
                            generateHTTPMessageExceptionally(generatorResult, generator.getState());
                        }
                    } else {
                        generateHTTPMessageExceptionally(generatorResult, generator.getState());
                    }
                } else {
                    generatorResult = generate(null, null, null, null, true);
                    if (generatorResult == HttpGenerator.Result.CONTINUE && generator.getState() == HttpGenerator.State.COMPLETING) {
                        generateLastData(generator);
                    } else {
                        generateHTTPMessageExceptionally(generatorResult, generator.getState());
                    }
                }
            }
        } finally {
            closed = true;
        }
    }

    private void generateLastData(HttpGenerator generator) throws IOException {
        HttpGenerator.Result generatorResult;
        generatorResult = generate(null, null, null, null, true);
        if (generator.getState() == HttpGenerator.State.END) {
            if (generatorResult == HttpGenerator.Result.DONE) {
                generateHTTPMessageSuccessfully();
            } else if (generatorResult == HttpGenerator.Result.SHUTDOWN_OUT) {
                getSession().close();
            } else {
                generateHTTPMessageExceptionally(generatorResult, generator.getState());
            }
        } else {
            generateHTTPMessageExceptionally(generatorResult, generator.getState());
        }
    }

    protected HttpGenerator.Result generate(MetaData info, ByteBuffer header, ByteBuffer chunk, ByteBuffer content,
                                            boolean last) throws IOException {
        final HttpGenerator generator = getHttpGenerator();
        if (clientMode) {
            return generator.generateRequest((MetaData.Request) info, header, chunk, content, last);
        } else {
            return generator.generateResponse((MetaData.Response) info, false, header, chunk, content, last);
        }
    }

    abstract protected ByteBuffer getHeaderByteBuffer();

    abstract protected Session getSession();

    abstract protected HttpGenerator getHttpGenerator();

    abstract protected void generateHTTPMessageSuccessfully();

    abstract protected void generateHTTPMessageExceptionally(HttpGenerator.Result generatorResult, HttpGenerator.State generatorState);

}
