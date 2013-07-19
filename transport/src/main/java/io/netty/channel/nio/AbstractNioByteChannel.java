/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    private final class NioByteUnsafe extends AbstractNioUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final SelectionKey key = selectionKey();
            final ChannelConfig config = config();
            if (!config.isAutoRead()) {
                int interestOps = key.interestOps();
                if ((interestOps & readInterestOp) != 0) {
                    // only remove readInterestOp if needed
                    key.interestOps(interestOps & ~readInterestOp);
                }
            }

            final ChannelPipeline pipeline = pipeline();

            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }

            final ByteBufAllocator allocator = config.getAllocator();
            final int maxMessagesPerRead = config.getMaxMessagesPerRead();

            boolean closed = false;
            Throwable exception = null;
            ByteBuf byteBuf = null;
            int messages = 0;
            try {
                for (;;) {
                    byteBuf = allocHandle.allocate(allocator);
                    int localReadAmount = doReadBytes(byteBuf);
                    if (localReadAmount == 0) {
                        byteBuf.release();
                        byteBuf = null;
                        break;
                    }
                    if (localReadAmount < 0) {
                        closed = true;
                        byteBuf.release();
                        byteBuf = null;
                        break;
                    }

                    pipeline.fireChannelRead(byteBuf);
                    allocHandle.record(localReadAmount);
                    byteBuf = null;
                    if (++ messages == maxMessagesPerRead) {
                        break;
                    }
                }
            } catch (Throwable t) {
                exception = t;
            } finally {
                if (byteBuf != null) {
                    if (byteBuf.isReadable()) {
                        pipeline.fireChannelRead(byteBuf);
                    } else {
                        byteBuf.release();
                    }
                }

                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    if (exception instanceof IOException) {
                        closed = true;
                    }

                    pipeline().fireExceptionCaught(exception);
                }

                if (closed) {
                    setInputShutdown();
                    if (isOpen()) {
                        if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                            key.interestOps(key.interestOps() & ~readInterestOp);
                            pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                        } else {
                            close(voidPromise());
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }

            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (!buf.isReadable()) {
                    in.remove();
                    continue;
                }

                boolean done = false;
                long flushedAmount = 0;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                    int localFlushedAmount = doWriteBytes(buf);
                    if (localFlushedAmount == 0) {
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (!buf.isReadable()) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write completely.
                    in.progress(flushedAmount);
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } else if (msg instanceof FileRegion) {
                FileRegion region = (FileRegion) msg;
                boolean done = false;
                long flushedAmount = 0;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                    long localFlushedAmount = doWriteFileRegion(region);
                    if (localFlushedAmount == 0) {
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (region.transfered() >= region.count()) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write completely.
                    in.progress(flushedAmount);
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } else {
                throw new UnsupportedOperationException("unsupported message type: " + StringUtil.simpleClassName(msg));
            }
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void updateOpWrite(long expectedWrittenBytes, long writtenBytes, boolean lastSpin) {
        if (writtenBytes >= expectedWrittenBytes) {
            final SelectionKey key = selectionKey();
            final int interestOps = key.interestOps();
            // Wrote the outbound buffer completely - clear OP_WRITE.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // 1) Wrote nothing: buffer is full obviously - set OP_WRITE
            // 2) Wrote partial data:
            //    a) lastSpin is false: no need to set OP_WRITE because the caller will try again immediately.
            //    b) lastSpin is true: set OP_WRITE because the caller will not try again.
            if (writtenBytes == 0 || lastSpin) {
                final SelectionKey key = selectionKey();
                final int interestOps = key.interestOps();
                if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                    key.interestOps(interestOps | SelectionKey.OP_WRITE);
                }
            }
        }
    }
}
