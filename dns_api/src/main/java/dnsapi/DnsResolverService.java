package dnsapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

@Service
public class DnsResolverService {

    private final String upstreamHost;
    private final int upstreamPort;
    private final int timeoutMilliseconds;

    public DnsResolverService(
            @Value("${dns.upstream.host}") String upstreamHost,
            @Value("${dns.upstream.port}") int upstreamPort,
            @Value("${dns.upstream.timeout-ms}") int timeoutMilliseconds
    ) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.timeoutMilliseconds = timeoutMilliseconds;
    }

    public byte[] resolve(byte[] query) throws IOException {
        if (query.length == 0 || query.length > 65_507) {
            throw new IllegalArgumentException("El paquete DNS no tiene un tamaño UDP válido");
        }

        InetAddress upstreamAddress = InetAddress.getByName(upstreamHost);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMilliseconds);

            DatagramPacket request = new DatagramPacket(
                    query,
                    query.length,
                    upstreamAddress,
                    upstreamPort
            );
            socket.send(request);

            byte[] responseBuffer = new byte[65_535];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);

            return Arrays.copyOf(response.getData(), response.getLength());
        }
    }
}
