/* Copyright (C) 2026 TinyDrone contributors
 * Licensed under the GNU General Public License, version 2 or later. */
package se.bitcraze.crazyfliecontrol2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Receives the packetized JPEG stream produced by Tiny-Drone wifi_esp32.c. */
public final class UdpVideoReceiver extends Thread {
    public interface Listener {
        void onVideoFrame(Bitmap frame);
        void onVideoStatus(String status);
    }

    private static final String TAG = "UdpVideoReceiver";
    private static final int VIDEO_PORT = 5000;
    private static final int PACKET_SIZE = 1400;
    private static final int HEADER_SIZE = 14;
    private static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    private static final int MAX_PACKETS = (MAX_FRAME_SIZE + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE;
    private static final int RECEIVE_BUFFER_SIZE = 4 * 1024 * 1024;

    private static final class FrameBuffer {
        final byte[] data = new byte[MAX_FRAME_SIZE];
        int length;
    }

    private final Listener mListener;
    private final BlockingQueue<FrameBuffer> mFreeFrames = new ArrayBlockingQueue<>(2);
    private final BlockingQueue<FrameBuffer> mDecodeFrames = new ArrayBlockingQueue<>(1);
    private volatile DatagramSocket mSocket;
    private volatile Thread mDecoderThread;

    public UdpVideoReceiver(Listener listener) {
        super("TinyDrone-video-rx");
        mListener = listener;
        mFreeFrames.add(new FrameBuffer());
        mFreeFrames.add(new FrameBuffer());
    }

    public void shutdown() {
        interrupt();
        Thread decoder = mDecoderThread;
        if (decoder != null) decoder.interrupt();
        DatagramSocket socket = mSocket;
        if (socket != null) socket.close();
    }

    @Override public void run() {
        startDecoder();
        byte[] datagram = new byte[PACKET_SIZE];
        boolean[] received = new boolean[MAX_PACKETS];
        FrameBuffer activeFrame = null;
        long currentFrameId = -1;
        int frameSize = 0, expectedPackets = 0, receivedPackets = 0;
        boolean sawPacket = false;

        try {
            DatagramSocket socket = bindVideoSocket();
            if (socket == null) return;
            mListener.onVideoStatus("UDP 5000 listening - tap Connect");
            DatagramPacket packet = new DatagramPacket(datagram, datagram.length);

            while (!isInterrupted() && !socket.isClosed()) {
                packet.setLength(datagram.length);
                socket.receive(packet);
                if (!sawPacket) {
                    sawPacket = true;
                    mListener.onVideoStatus("Receiving video packets");
                }
                if (packet.getLength() == HEADER_SIZE) {
                    ByteBuffer failure = ByteBuffer.wrap(packet.getData(), 0, HEADER_SIZE)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    if ((failure.getInt() & 0xffffffffL) == 0xffffffffL)
                        mListener.onVideoStatus("Drone camera capture failed");
                    continue;
                }
                if (packet.getLength() < HEADER_SIZE) continue;

                ByteBuffer header = ByteBuffer.wrap(packet.getData(), packet.getOffset(), HEADER_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN);
                long frameId = header.getInt() & 0xffffffffL;
                long totalSizeLong = header.getInt() & 0xffffffffL;
                int totalPackets = header.getShort() & 0xffff;
                int sequence = header.getShort() & 0xffff;
                int dataLength = header.getShort() & 0xffff;
                int actualLength = packet.getLength() - HEADER_SIZE;

                if (totalSizeLong < 4 || totalSizeLong > MAX_FRAME_SIZE || totalPackets == 0 ||
                        totalPackets > MAX_PACKETS ||
                        totalPackets != (totalSizeLong + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE ||
                        sequence >= totalPackets || dataLength == 0 || dataLength > PAYLOAD_SIZE ||
                        dataLength != actualLength) {
                    mListener.onVideoStatus("Invalid video packet header");
                    continue;
                }

                if (frameId != currentFrameId) {
                    if (activeFrame != null) {
                        mFreeFrames.offer(activeFrame);
                        if (receivedPackets != expectedPackets)
                            mListener.onVideoStatus("Video packet loss - waiting for next frame");
                    }
                    activeFrame = mFreeFrames.poll();
                    currentFrameId = frameId;
                    frameSize = (int) totalSizeLong;
                    expectedPackets = totalPackets;
                    receivedPackets = 0;
                    for (int i = 0; i < expectedPackets; i++) received[i] = false;
                }
                if (activeFrame == null || frameSize != (int) totalSizeLong ||
                        expectedPackets != totalPackets) continue;

                int offset = sequence * PAYLOAD_SIZE;
                if (offset < 0 || offset >= frameSize) continue;
                int expectedLength = Math.min(PAYLOAD_SIZE, frameSize - offset);
                if (dataLength != expectedLength || offset + dataLength > frameSize || received[sequence]) continue;
                System.arraycopy(packet.getData(), packet.getOffset() + HEADER_SIZE,
                        activeFrame.data, offset, dataLength);
                received[sequence] = true;
                receivedPackets++;

                if (receivedPackets == expectedPackets) {
                    activeFrame.length = frameSize;
                    if ((activeFrame.data[0] & 0xff) == 0xff && (activeFrame.data[1] & 0xff) == 0xd8 &&
                            (activeFrame.data[frameSize - 2] & 0xff) == 0xff &&
                            (activeFrame.data[frameSize - 1] & 0xff) == 0xd9) {
                        FrameBuffer stale = mDecodeFrames.poll();
                        if (stale != null) mFreeFrames.offer(stale);
                        mDecodeFrames.offer(activeFrame);
                    } else {
                        mListener.onVideoStatus("Invalid JPEG frame");
                        mFreeFrames.offer(activeFrame);
                    }
                    activeFrame = null;
                    currentFrameId = -1;
                }
            }
        } catch (IOException e) {
            if (!isInterrupted()) {
                Log.w(TAG, "Video receive failed", e);
                mListener.onVideoStatus("Video receive error");
            }
        } finally {
            if (activeFrame != null) mFreeFrames.offer(activeFrame);
            DatagramSocket socket = mSocket;
            if (socket != null) socket.close();
            mSocket = null;
        }
    }

    /** Activity recreation can briefly leave the previous socket alive. */
    private DatagramSocket bindVideoSocket() {
        while (!isInterrupted()) {
            DatagramSocket candidate = null;
            try {
                candidate = new DatagramSocket(null);
                candidate.setReuseAddress(true);
                try {
                    candidate.setReceiveBufferSize(RECEIVE_BUFFER_SIZE);
                } catch (SocketException e) {
                    Log.w(TAG, "Large receive buffer unavailable; using system default", e);
                }
                candidate.bind(new InetSocketAddress(VIDEO_PORT));
                mSocket = candidate;
                return candidate;
            } catch (SocketException e) {
                Log.w(TAG, "UDP 5000 bind failed; retrying", e);
                if (candidate != null) candidate.close();
                mListener.onVideoStatus("UDP 5000 busy - retrying");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    interrupt();
                }
            }
        }
        return null;
    }

    private void startDecoder() {
        mDecoderThread = new Thread("TinyDrone-video-decode") {
            @Override public void run() {
                while (!isInterrupted()) {
                    FrameBuffer frame = null;
                    try {
                        frame = mDecodeFrames.take();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.length);
                        if (bitmap != null) mListener.onVideoFrame(bitmap);
                        else mListener.onVideoStatus("JPEG decode failed");
                    } catch (InterruptedException e) {
                        interrupt();
                    } finally {
                        if (frame != null) mFreeFrames.offer(frame);
                    }
                }
            }
        };
        mDecoderThread.start();
    }
}
