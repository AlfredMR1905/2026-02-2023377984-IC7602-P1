package dnsapi;

import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record ExistsResponse(boolean exists) {
    }

    public record AddressConfig(String address) {
    }

    public record DomainConfig(String policy, int ttl, List<AddressConfig> addresses) {
    }

    public record DnsPacketBody(String data) {
    }
}
