package io.sysmic.dedup.chunker;

import io.sysmic.dedup.rollinghash.RollingHash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A Basic Sliding Window (BSW) chunker splits files into chunks by using a rolling hash function and a divisor.
 */
public class BSWChunker extends Chunker {

    private final RollingHash rh;

    private final int d;

    private final int windowSize;

    private final int bufferSize;

    public BSWChunker(RollingHash rh, int d, int windowSize) {
        this(rh, d, windowSize, 8 * 1024);
    }

    public BSWChunker(RollingHash rh, int d, int windowSize, int bufferSize) {
        this.rh = rh;
        this.d = computeBitmask(d);
        this.windowSize = windowSize;
        this.bufferSize = bufferSize;
    }

    public Iterator<ByteBuffer> chunk(final ReadableByteChannel channel) {

        return new ChunkIterator() {

            private ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

            private ByteBuffer output = ByteBuffer.allocate(bufferSize);

            public boolean hasNext() {
                try {
                    channel.read(buffer);
                    buffer.flip();
                    return buffer.hasRemaining();
                } catch (IOException e) {
                    return false;
                } finally {
                    buffer.compact();
                }
            }

            public ByteBuffer next() {
                try {
                    // fill the buffer
                    int bytesRead = channel.read(buffer);

                    // switch to read mode
                    buffer.flip();

                    if (buffer.hasRemaining()) {
                        // reset the rolling hash function
                        rh.reset();

                        // initialize the breakpoint
                        int breakpoint = 0;

                        while (buffer.hasRemaining() && breakpoint == 0) {
                            byte b = buffer.get();

                            // initialize the rolling hash at tmin
                            if (buffer.position() == windowSize) {
                                buffer.position(buffer.position() - windowSize);
                                byte[] window = new byte[windowSize];
                                buffer.get(window);
                                rh.init(window);
                            }

                            // slide the window after tmin
                            if (buffer.position() > windowSize) {
                                rh.roll(b);
                            }

                            // look for breakpoints from tmin
                            if (buffer.position() >= windowSize) {
                                int hash = rh.getValue();
                                if ((hash & d) == 0) {
                                    breakpoint = buffer.position();
                                }
                            }

                            // fill the output buffer
                            output.put(b);
                        }

                        // compact the buffer for the next iteration
                        buffer.compact();

                        // switch the output to read mode
                        output.flip();

                        if (breakpoint == 0 && bytesRead != -1 && hasNext()) {
                            output = ByteBuffer.allocate(output.capacity() + bufferSize).put(output);
                            return next();
                        } else {
                            try {
                                return output.asReadOnlyBuffer();
                            } finally {
                                output = ByteBuffer.allocate(bufferSize);
                            }
                        }

                    } else {
                        throw new NoSuchElementException();
                    }
                } catch (IOException e) {
                    throw new NoSuchElementException();
                }
            }
        };
    }

}
