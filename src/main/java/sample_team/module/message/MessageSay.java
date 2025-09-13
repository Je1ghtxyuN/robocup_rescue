package sample_team.module.message;

import adf.core.component.communication.util.BitOutputStream;
import adf.core.component.communication.util.BitStreamReader;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;

public class MessageSay extends StandardMessage {
    private static final int MAX_MESSAGE_LENGTH = 256;
    private String message;

    public MessageSay(boolean isRadio, String message) {
        this(isRadio, StandardMessagePriority.NORMAL, message);
    }

    public MessageSay(boolean isRadio, StandardMessagePriority priority, String message) {
        super(isRadio, priority);
        this.message = message.substring(0, Math.min(message.length(), MAX_MESSAGE_LENGTH));
    }

    // 移除了 @Nonnull 注解
    public MessageSay(boolean isRadio, int from, int ttl, BitStreamReader bitStreamReader) {
        super(isRadio, from, ttl, bitStreamReader);
        int length = bitStreamReader.getBits(8);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) bitStreamReader.getBits(8);
        }
        this.message = new String(bytes);
    }

    public String getMessage() {
        return this.message;
    }

    @Override
    public int getByteArraySize() {
        return toBitOutputStream().size();
    }

    @Override
    public byte[] toByteArray() {
        return this.toBitOutputStream().toByteArray();
    }

    @Override
    public BitOutputStream toBitOutputStream() {
        BitOutputStream bitOutputStream = new BitOutputStream();
        byte[] bytes = message.getBytes();
        bitOutputStream.writeBits(bytes.length, 8);
        for (byte b : bytes) {
            bitOutputStream.writeBits(b, 8);
        }
        return bitOutputStream;
    }

    @Override
    public String getCheckKey() {
        return getClass().getCanonicalName() + " > message:" + this.getMessage();
    }
}