package dnsapi;

import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record ExistsResponse(boolean exists) {
    }

    public record AddressConfig(String address, int weight, String country) {
    }

    public record CountryResponse(String country) {
    }

    public record DomainConfig(String policy, int ttl, List<AddressConfig> addresses) {
    }

    public record DomainView(long id, String domain, String policy, int ttl, List<AddressConfig> addresses) {
    }

    public record SaveDomainRequest(String domain, String policy, int ttl, List<AddressConfig> addresses) {
    }

    public record IpCountryNetwork(long id, String network, String country) {
    }

    public record SaveIpCountryNetwork(String network, String country) {
    }

    public record DnsPacketBody(String data) {
    }
}
