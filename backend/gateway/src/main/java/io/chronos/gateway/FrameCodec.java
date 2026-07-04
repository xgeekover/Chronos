package io.chronos.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import io.chronos.proto.ClientMessage;
import io.chronos.proto.ServerMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Wire framing for the gateway protocol (§9): a protobuf message, optionally gzip-compressed,
 * prefixed by a 1-byte flag ({@code 0}=raw, {@code 1}=gzip). The flag lets a peer decode without
 * prior negotiation; the server's compression is configurable.
 */
public final class FrameCodec {

    private static final byte RAW = 0;
    private static final byte GZIP = 1;

    private final boolean compress;

    public FrameCodec(boolean compress) {
        this.compress = compress;
    }

    public byte[] encode(ServerMessage message) {
        byte[] payload = message.toByteArray();
        try {
            if (compress) {
                return frame(GZIP, gzip(payload));
            }
            return frame(RAW, payload);
        } catch (IOException e) {
            throw new IllegalStateException("frame encode failed", e);
        }
    }

    public ClientMessage decode(byte[] frame) throws InvalidProtocolBufferException {
        if (frame.length == 0) {
            return ClientMessage.getDefaultInstance();
        }
        byte flag = frame[0];
        byte[] body = new byte[frame.length - 1];
        System.arraycopy(frame, 1, body, 0, body.length);
        try {
            byte[] payload = flag == GZIP ? gunzip(body) : body;
            return ClientMessage.parseFrom(payload);
        } catch (IOException e) {
            throw new IllegalStateException("frame decode failed", e);
        }
    }

    private static byte[] frame(byte flag, byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = flag;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }
}
