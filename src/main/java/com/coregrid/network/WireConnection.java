package com.coregrid.network;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Length-framed binary protocol V2; every send is atomic even across callback threads. */
public final class WireConnection implements AutoCloseable {
    private static final int MAX_FRAME = 16 * 1024;
    private static final int VERSION = 2;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public WireConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }
    public synchronized void send(WireMessage message) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(buffer);
        data.writeByte(VERSION);
        data.writeUTF(message.type());
        data.writeUTF(message.jobId());
        data.writeInt(message.cpu());
        data.writeBoolean(message.gpu());
        data.writeInt(message.exitCode());
        data.writeUTF(message.detail());
        data.writeUTF(message.environment());
        data.writeInt(message.arguments().size());
        for (String arg : message.arguments()) data.writeUTF(arg);
        data.writeInt(message.memoryMb());
        data.writeInt(message.timeoutSeconds());
        data.flush();
        byte[] frame = buffer.toByteArray();
        if (frame.length > MAX_FRAME) throw new IOException("Frame too large");
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }
    public WireMessage receive() throws IOException {
        int len;
        try { len = in.readInt(); }
        catch (EOFException e) { return null; }
        if (len < 1 || len > MAX_FRAME) throw new IOException("Invalid frame length: " + len);
        byte[] frameBytes = in.readNBytes(len);
        if (frameBytes.length != len) throw new EOFException("Truncated TCP frame");
        DataInputStream frame = new DataInputStream(new ByteArrayInputStream(frameBytes));
        if (frame.readUnsignedByte() != VERSION) throw new IOException("Unsupported protocol version");
        String type = frame.readUTF();
        String jobId = frame.readUTF();
        int cpu = frame.readInt();
        boolean gpu = frame.readBoolean();
        int exitCode = frame.readInt();
        String detail = frame.readUTF();
        String environment = frame.readUTF();
        int n = frame.readInt();
        if (n < 0 || n > 16) throw new IOException("Invalid argument count");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < n; i++) args.add(frame.readUTF());
        int mem = frame.readInt();
        int timeout = frame.readInt();
        if (frame.available() != 0) throw new IOException("Trailing data in frame");
        return new WireMessage(type, jobId, cpu, gpu, exitCode, detail, environment, args, mem, timeout);
    }
    @Override public void close() throws IOException { socket.close(); }
}
