package org.katacr.kaproxy.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** 定义 KaProxy 后端通讯信封、协议版本及基础二进制编解码。 */
public final class KaProxyProtocol {
    public static final String CHANNEL = "kaproxy:main";
    public static final String LEGACY_GUILDS_CHANNEL = "kaguilds:chat";
    public static final int MAGIC = 0x4B415058;
    public static final short VERSION = 1;
    public static final int MAX_PACKET_BYTES = 1_048_576;

    private KaProxyProtocol() {
    }

    /** 编码一个带模块和动作名称的数据包。 */
    public static byte[] encode(String module, String action, PacketWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeUTF(module);
            output.writeUTF(action);
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    /** 校验信封并返回定位到业务负载的输入流。 */
    public static Packet decode(byte[] data) throws IOException {
        if (data.length > MAX_PACKET_BYTES) {
            throw new IOException("数据包超过大小限制");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        if (input.readInt() != MAGIC) {
            throw new IOException("无效 KaProxy 数据包标识");
        }
        short version = input.readShort();
        if (version != VERSION) {
            throw new IOException("不支持的 KaProxy 协议版本: " + version);
        }
        return new Packet(input.readUTF(), input.readUTF(), input);
    }

    /** 写入 UUID 的两个 long 部分。 */
    public static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    /** 读取 UUID 的两个 long 部分。 */
    public static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    /** 保存已解码的数据包路由信息和剩余负载。 */
    public record Packet(String module, String action, DataInputStream input) {
    }

    /** 为编码器提供可抛出 IO 异常的负载写入动作。 */
    @FunctionalInterface
    public interface PacketWriter {
        /** 把业务负载写入数据包。 */
        void write(DataOutputStream output) throws IOException;
    }
}
