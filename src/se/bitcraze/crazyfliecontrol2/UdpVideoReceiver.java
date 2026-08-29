/*
 * Copyright (C) 2026 TinyDrone contributors
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 2 or later.
 */
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

/** Reassembles the packetized JPEG stream used by Tiny-Drone-Controller. */
public final class UdpVideoReceiver extends Thread {
    public interface FrameListener {
        void onVideoFrame(Bitmap frame);
    }

    private static final String TAG = "UdpVideoReceiver";
    private static final int VIDEO_PORT = 5000;
    private static final int PACKET_SIZE = 1400;
    private static final int HEADER_SIZE = 14;
    private static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    private static final int MAX_PACKETS = (MAX_FRAME_SIZE + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE;
    private static final int RECEIVE_BUFFER_SIZE = 4 * 1024 * 1024;

    private final FrameListener mListener;
    private volatile DatagramSocket mSocket;

    public UdpVideoReceiver(FrameListener listener) {
        super("TinyDrone-video");
        mListener = listener;
    }

    public void shutdown() {
        interrupt();
        DatagramSocket socket = mSocket;
        if (socket != null) socket.close();
    }

    @Override
    public void run() {
        byte[] datagram = new byte[65507];
        byte[] frame = null;
        boolean[] received = null;
        long currentFrameId = -1;
        int frameSize = 0;
        int expectedPackets = 0;
        int receivedPackets = 0;

        try {
            DatagramSocket socket = new DatagramSocket(null);
            mSocket = socket;
            socket.setReuseAddress(true);
            socket.setReceiveBufferSize(RECEIVE_BUFFER_SIZE);
            socket.bind(new InetSocketAddress(VIDEO_PORT));

            DatagramPacket packet = new DatagramPacket(datagram, datagram.length);
            while (!isInterrupted() && !socket.isClosed()) {
                packet.setLength(datagram.length);
                socket.receive(packet);
                if (packet.getLength() <= HEADER_SIZE) continue;

                ByteBuffer header = ByteBuffer.wrap(packet.getData(), packet.getOffset(), HEADER_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN);
                long frameId = header.getInt() & 0xffffffffL;
                long totalSizeLong = header.getInt() & 0xffffffffL;
                int totalPackets = header.getShort() & 0xffff;
                int sequence = header.getShort() & 0xffff;
                int dataLength = header.getShort() & 0xffff;
                int actualLength = packet.getLength() - HEADER_SIZE;

                if (totalSizeLong == 0 || totalSizeLong > MAX_FRAME_SIZE ||
                        totalPackets == 0 || totalPackets > MAX_PACKETS ||
                        totalPackets != (totalSizeLong + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE ||
                        sequence >= totalPackets || dataLength == 0 ||
                        dataLength > PAYLOAD_SIZE || dataLength != actualLength) continue;

                if (frameId != currentFrameId) {
                    currentFrameId = frameId;
                    frameSize = (int) totalSizeLong;
                    expectedPackets = totalPackets;
                    receivedPackets = 0;
                    frame = new byte[frameSize];
                    received = new boolean[expectedPackets];
                }
                if (frameSize != (int) totalSizeLong || expectedPackets != totalPackets) continue;

                int offset = sequence * PAYLOAD_SIZE;
                int expectedLength = Math.min(PAYLOAD_SIZE, frameSize - offset);
                if (offset < 0 || offset >= frameSize || dataLength != expectedLength ||
                        offset + dataLength > frameSize || received[sequence]) continue;

                System.arraycopy(packet.getData(), packet.getOffset() + HEADER_SIZE,
                        frame, offset, dataLength);
                received[sequence] = true;
                receivedPackets++;

                if (receivedPackets == expectedPackets) {
                    if ((frame[0] & 0xff) == 0xff && (frame[1] & 0xff) == 0xd8 &&
                            (frame[frameSize - 2] & 0xff) == 0xff &&
                            (frame[frameSize - 1] & 0xff) == 0xd9) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frameSize);
                        if (bitmap != null) mListener.onVideoFrame(bitmap);
                    }
                    currentFrameId = -1;
                    frame = null;
                    received = null;
                }
            }
        } catch (SocketException e) {
            if (!isInterrupted()) Log.w(TAG, "Video socket stopped", e);
        } catch (IOException e) {
            if (!isInterrupted()) Log.w(TAG, "Video receive failed", e);
        } finally {
            DatagramSocket socket = mSocket;
            if (socket != null) socket.close();
            mSocket = null;
        }
    }
}
