package se.bitcraze.crazyfliecontrol2;

import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;

final class FlightDiagnostics extends Thread {
    private static final String[][] GROUPS = {
            {"stabilizer.thrust", "controller.cmd_roll", "controller.cmd_pitch",
                    "controller.ctr_yaw", "controller.roll", "controller.pitch"},
            {"pwm.m1_pwm", "pwm.m2_pwm", "pwm.m3_pwm", "pwm.m4_pwm", "range.zrange"},
            {"posCtl.targetVX", "posCtl.targetVY", "posCtl.targetVZ",
                    "stateEstimate.vx", "stateEstimate.vy", "stateEstimate.vz"},
            {"stateEstimate.roll", "stateEstimate.pitch", "stateEstimate.yaw",
                    "motion.deltaX", "motion.deltaY", "motion.squal"},
            {"stateEstimate.x", "stateEstimate.y", "stateEstimate.z",
                    "ctrltarget.vx", "ctrltarget.vy"},
            {"kalman_pred.predNX", "kalman_pred.measNX", "kalman_pred.predNY",
                    "kalman_pred.measNY", "kalman_pred.flowUpdates", "motion.motion", "motion.shutter"},
            {"posHoldDiag.inputRoll", "posHoldDiag.inputPitch", "posHoldDiag.inputThrust",
                    "posHoldDiag.takeoffActive", "kalman_pred.flowDt", "kalman_pred.flowStd"}
    };

    private final EspUdpDriver driver;
    private final File file;
    private final Runnable onStatusChanged;
    private final ArrayBlockingQueue<byte[]> replies = new ArrayBlockingQueue<>(32);
    private final ArrayBlockingQueue<byte[]> samples = new ArrayBlockingQueue<>(512);
    private final AtomicInteger dropped = new AtomicInteger();
    private final Map<String, Field> fields = new HashMap<>();
    private final Map<Integer, List<Field>> blocks = new HashMap<>();
    volatile boolean recording = true;
    volatile String result = "正在读取飞控日志表";

    private static final class Field {
        final String name;
        final int id;
        final int type;

        Field(String name, int id, int type) {
            this.name = name;
            this.id = id;
            this.type = type;
        }

        long integer(ByteBuffer data) {
            switch (type) {
                case 1: return data.get() & 0xff;
                case 2: return data.getShort() & 0xffff;
                case 3: return data.getInt() & 0xffffffffL;
                case 5: return data.getShort();
                default: throw new IllegalArgumentException("Unsupported log type");
            }
        }
    }

    FlightDiagnostics(EspUdpDriver driver, File file, Runnable onStatusChanged) {
        super("flight-diagnostics");
        this.driver = driver;
        this.file = file;
        this.onStatusChanged = onStatusChanged;
    }

    void receive(byte[] packet) {
        if (!recording || packet.length < 2 || (packet[0] & 0xf0) != 0x50) return;
        if ((packet[0] & 3) == 2) {
            if (!samples.offer(packet)) dropped.incrementAndGet();
        } else {
            replies.offer(packet);
        }
    }

    void receiveFlightMode(byte[] packet) {
        if (recording && !samples.offer(packet)) dropped.incrementAndGet();
    }

    private void send(int channel, byte... payload) {
        driver.sendPacket(new CrtpPacket((byte) (0x5c | channel), payload));
    }

    private byte[] request(int channel, int command, int id, byte... payload)
            throws InterruptedException, IOException {
        for (int attempt = 0; attempt < 3 && recording && driver.isConnected(); attempt++) {
            send(channel, payload);
            long deadline = SystemClock.elapsedRealtime() + 350;
            while (recording && SystemClock.elapsedRealtime() < deadline) {
                byte[] reply = replies.poll(50, TimeUnit.MILLISECONDS);
                if (reply == null || (reply[0] & 3) != channel || (reply[1] & 255) != command) {
                    continue;
                }
                if (id < 0 || (reply.length >= 4 && (reply[2] & 255) +
                        (channel == 0 ? (reply[3] & 255) * 256 : 0) == id)) return reply;
            }
        }
        throw new IOException("CRTP 日志请求未响应，命令 " + command + "，编号 " + id);
    }

    private void readToc() throws IOException, InterruptedException {
        byte[] info = request(0, 3, -1, (byte) 3);
        if (info.length < 10) throw new IOException("日志表响应长度错误");
        int count = (info[2] & 255) | ((info[3] & 255) << 8);
        for (int id = 0; id < count && recording; id++) {
            byte[] item = request(0, 2, id, (byte) 2, (byte) id, (byte) (id >> 8));
            if (item.length < 7) continue;
            String[] names = new String(item, 5, item.length - 5, "UTF-8").split(String.valueOf((char) 0));
            int type = item[4] & 15;
            if (names.length == 2 && (type == 1 || type == 2 || type == 3 || type == 5 || type == 7)) {
                String name = names[0] + "." + names[1];
                fields.put(name, new Field(name, id, type));
            }
        }
    }

    private void startBlocks(BufferedWriter output) throws IOException, InterruptedException {
        for (int group = 0; group < GROUPS.length && recording; group++) {
            List<Field> selected = new ArrayList<>();
            int blockId = 120 + group;
            ByteBuffer command = ByteBuffer.allocate(2 + GROUPS[group].length * 3)
                    .order(ByteOrder.LITTLE_ENDIAN);
            command.put((byte) 6).put((byte) blockId);
            for (String name : GROUPS[group]) {
                Field field = fields.get(name);
                if (field == null) {
                    output.write(",,missing," + name + ",\n");
                } else {
                    selected.add(field);
                    command.put((byte) field.type).putShort((short) field.id);
                }
            }
            if (selected.isEmpty()) continue;
            byte[] payload = new byte[command.position()];
            command.flip();
            command.get(payload);
            request(1, 2, blockId, (byte) 2, (byte) blockId);
            blocks.put(blockId, selected);
            byte[] reply = request(1, 6, blockId, payload);
            if (reply[3] != 0) throw new IOException("飞控拒绝创建日志块 " + blockId);
            reply = request(1, 3, blockId, (byte) 3, (byte) blockId, (byte) 5);
            if (reply[3] != 0) throw new IOException("飞控拒绝启动日志块 " + blockId);
        }
        if (blocks.isEmpty()) throw new IOException("飞控没有可用的诊断字段");
    }

    private void writeSample(BufferedWriter output, byte[] packet) throws IOException {
        if ((packet[0] & 0xf3) == 0x81) {
            String prefix = SystemClock.elapsedRealtime() + ",,status,";
            float height = ByteBuffer.wrap(packet, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            output.write(prefix + "relativeHeight," + height + "\n");
            output.write(prefix + "flightMode," + (packet[10] & 255) + "\n");
            return;
        }
        if (packet.length < 5) return;
        List<Field> fields = blocks.get(packet[1] & 255);
        if (fields == null) return;
        int droneTime = (packet[2] & 255) | ((packet[3] & 255) << 8) | ((packet[4] & 255) << 16);
        ByteBuffer data = ByteBuffer.wrap(packet, 5, packet.length - 5).order(ByteOrder.LITTLE_ENDIAN);
        String prefix = SystemClock.elapsedRealtime() + "," + droneTime + "," + (packet[1] & 255) + ",";
        for (Field field : fields) {
            int size = field.type == 1 ? 1 : field.type == 2 || field.type == 5 ? 2 : 4;
            if (data.remaining() < size) return;
            String value = field.type == 7 ? Float.toString(data.getFloat()) : Long.toString(field.integer(data));
            output.write(prefix + field.name + "," + value + "\n");
        }
    }

    @Override
    public void run() {
        try (BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            output.write("phone_elapsed_ms,drone_timestamp_24bit,block,field,value\n");
            try {
                readToc();
                if (recording && (!fields.containsKey("posHoldDiag.inputPitch")
                        || !fields.containsKey("kalman_pred.flowUpdates"))) {
                    throw new IOException("请先刷入配套的 POS_HOLD 漂移诊断固件");
                }
                if (recording) startBlocks(output);
                Set<Integer> awaitingBlocks = new HashSet<>(blocks.keySet());
                long readyDeadline = SystemClock.elapsedRealtime() + 3000;
                long lastFlush = SystemClock.elapsedRealtime();
                long lastLogSample = lastFlush;
                while (recording && driver.isConnected()) {
                    byte[] packet = samples.poll(200, TimeUnit.MILLISECONDS);
                    if (packet != null) {
                        writeSample(output, packet);
                        if (packet.length >= 5 && (packet[0] & 0xf0) == 0x50) {
                            lastLogSample = SystemClock.elapsedRealtime();
                            if (awaitingBlocks.remove(packet[1] & 255) && awaitingBlocks.isEmpty()
                                    && recording) {
                                result = "诊断录制已就绪，可以开始测试";
                                output.write(lastLogSample + ",,event,capture_ready,1\n");
                                onStatusChanged.run();
                            }
                        }
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (!awaitingBlocks.isEmpty() && now >= readyDeadline) {
                        throw new IOException("部分诊断日志块未收到数据 " + awaitingBlocks);
                    }
                    if (now - lastLogSample > 3000) throw new IOException("未收到飞控日志数据");
                    if (now - lastFlush >= 1000) {
                        output.flush();
                        lastFlush = now;
                    }
                }
                byte[] packet;
                while ((packet = samples.poll()) != null) writeSample(output, packet);
                result = "诊断已停止，可以导出";
            } catch (IOException e) {
                result = recording ? e.getMessage() : "诊断已停止，可以导出";
                if (recording) {
                    onStatusChanged.run();
                    output.write(",,error,diagnostics,\n");
                }
            }
            output.write(",,summary,dropped_phone_packets," + dropped.get() + "\n");
        } catch (IOException | InterruptedException e) {
            result = "诊断文件保存中断";
        } finally {
            for (int id : blocks.keySet()) {
                send(1, (byte) 2, (byte) id);
            }
            recording = false;
            if (driver.diagnostics == this) driver.diagnostics = null;
        }
    }
}
