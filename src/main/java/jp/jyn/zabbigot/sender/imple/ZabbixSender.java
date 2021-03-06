package jp.jyn.zabbigot.sender.imple;

import jp.jyn.zabbigot.sender.StatusSender;
import jp.jyn.zabbigot.sender.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public class ZabbixSender implements StatusSender {
    private final static byte[] HEADER = {'Z', 'B', 'X', 'D', '\1'};
    private final static int BIT_LENGTH = 8;
    private final static int BYTE64_LENGTH = 64 / BIT_LENGTH;

    private final String host;
    private final int port;
    private final static int timeout = 3 * 1000;

    public ZabbixSender(String host) {
        this(host, 10051);
    }

    public ZabbixSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public SendResult send(Collection<Status> data) {
        SendResult result = toJson(data);
        byte[] body = toBytes(result.response.getBytes());
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(host, port), timeout);
            try (InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream()) {
                out.write(body);
                out.flush();

                byte[] buffer = new byte[512];
                int read, count = 0;
                while (true) {
                    read = in.read(buffer, count, buffer.length - count);
                    if (read <= 0) {
                        break;
                    }
                    count += read;
                }

                result.response = count < 13
                    ? "[]"
                    : new String(buffer, HEADER.length + BYTE64_LENGTH, count - (HEADER.length + BYTE64_LENGTH), StandardCharsets.UTF_8);
                return result;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] toBytes(byte[] jsonBytes) {
        // https://www.zabbix.org/wiki/Docs/protocols/zabbix_sender/1.8/java_example
        // https://siguniang.wordpress.com/2012/10/13/notes-on-zabbix-1-4-protocol/
        // https://github.com/hengyunabc/zabbix-sender/
        byte[] result = new byte[HEADER.length + BYTE64_LENGTH + jsonBytes.length];

        // write header
        System.arraycopy(HEADER, 0, result, 0, HEADER.length);

        // write length(64bit little-endian)
        result[HEADER.length] = (byte) (jsonBytes.length & 0xFF);
        result[HEADER.length + 1] = (byte) ((jsonBytes.length >> 8) & 0x00FF);
        result[HEADER.length + 2] = (byte) ((jsonBytes.length >> 16) & 0x0000FF);
        result[HEADER.length + 3] = (byte) ((jsonBytes.length >> 24) & 0x000000FF);
        //result[HEADER.length + 4...7] = 0;

        // write body
        System.arraycopy(jsonBytes, 0, result, HEADER.length + BYTE64_LENGTH, jsonBytes.length);
        return result;
    }

    private SendResult toJson(Iterable<Status> data) {
        // https://www.zabbix.org/wiki/Docs/protocols/zabbix_sender/3.4
        // https://aoishi.hateblo.jp/entry/2017/12/03/014913

        SendResult result = new SendResult();
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"request\":\"sender data\",");
        builder.append("\"clock\":").append(System.currentTimeMillis() / 1000).append(',');

        builder.append("\"data\":[");
        boolean first = true;
        for (Status datum : data) {
            if (first) {
                first = false;
            } else {
                builder.append(',');
            }
            builder.append('{');

            String value = datum.value.get();
            long clock = datum.clock.getAsLong();
            // clock
            builder.append("\"clock\":");
            builder.append(clock).append(',');
            // host
            builder.append("\"host\":");
            Status.jsonStr(datum.host, builder).append(',');
            // key
            builder.append("\"key\":");
            Status.jsonStr(datum.key, builder).append(',');
            // value
            builder.append("\"value\":");
            Status.jsonStr(value, builder);
            result.data.put(datum.key, value);

            builder.append('}');
        }
        builder.append(']');

        builder.append('}');
        result.response = builder.toString();
        return result;
    }
}
