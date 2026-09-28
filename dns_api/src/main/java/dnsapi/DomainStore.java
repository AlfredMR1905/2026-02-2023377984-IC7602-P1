package dnsapi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import dnsapi.ApiModels.AddressConfig;
import dnsapi.ApiModels.DomainConfig;

@Component
public class DomainStore {

    // Rangos de demostracion
    private final List<IpRange> ranges = List.of(
            new IpRange("127.0.0.0", 8, "CR"),
            new IpRange("10.0.0.0", 8, "CR"),
            new IpRange("192.0.2.0", 24, "US")
    );

    private final Map<String, DomainConfig> domains = Map.of(
            "ejemplo.test",
            new DomainConfig(
                    "single",
                    60,
                    List.of(new AddressConfig("10.0.0.25", 1, null))
            ),
            "multi.test",
            new DomainConfig(
                    "multi",
                    60,
                    List.of(
                            new AddressConfig("10.0.0.11", 1, null),
                            new AddressConfig("10.0.0.12", 1, null)
                    )
            ),
            "weight.test",
            new DomainConfig(
                    "weight",
                    60,
                    List.of(
                            new AddressConfig("10.0.0.21", 3, null),
                            new AddressConfig("10.0.0.22", 1, null)
                    )
            ),
            "geo.test",
            new DomainConfig(
                    "geo",
                    60,
                    List.of(
                            new AddressConfig("10.0.0.31", 1, "CR"),
                            new AddressConfig("10.0.0.32", 1, "US")
                    )
            )
    );

    public boolean exists(String domain) {
        return domains.containsKey(normalize(domain));
    }

    public Optional<DomainConfig> find(String domain) {
        return Optional.ofNullable(domains.get(normalize(domain)));
    }

    public Optional<String> countryForIp(String ip) {
        long address = ipv4Number(ip);
        if (address < 0) {
            return Optional.empty();
        }
        for (IpRange range : ranges) {
            long mask = (0xffff_ffffL << (32 - range.prefixBits())) & 0xffff_ffffL;
            if ((address & mask) == (ipv4Number(range.network()) & mask)) {
                return Optional.of(range.country());
            }
        }
        return Optional.empty();
    }

    private long ipv4Number(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            return -1;
        }
        long value = 0;
        try {
            for (String octet : octets) {
                int part = Integer.parseInt(octet);
                if (part < 0 || part > 255) {
                    return -1;
                }
                value = (value << 8) | part;
            }
        } catch (NumberFormatException error) {
            return -1;
        }
        return value;
    }

    private record IpRange(String network, int prefixBits, String country) {
    }

    private String normalize(String domain) {
        return domain.trim().toLowerCase();
    }
}
