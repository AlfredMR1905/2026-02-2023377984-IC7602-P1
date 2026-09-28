package dnsapi;

import dnsapi.ApiModels.AddressConfig;
import dnsapi.ApiModels.DomainConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class DomainStore {

    private final RestClient supabase;

    public DomainStore(@Value("${supabase.url}") String url, @Value("${supabase.key}") String key) {
        this.supabase = RestClient.builder()
                .baseUrl(url.replaceAll("/+$", "") + "/rest/v1")
                .defaultHeader("apikey", key)
                .build();
    }

    public boolean exists(String domain) {
        IdRow[] rows = supabase.get()
                .uri(uri -> uri.path("/dns_records")
                        .queryParam("select", "id")
                        .queryParam("domain", "eq." + normalize(domain))
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .body(IdRow[].class);
        return rows != null && rows.length > 0;
    }

    public Optional<DomainConfig> find(String domain) {
        RecordRow[] rows = supabase.get()
                .uri(uri -> uri.path("/dns_records")
                        .queryParam("select", "id,policy,ttl")
                        .queryParam("domain", "eq." + normalize(domain))
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .body(RecordRow[].class);
        if (rows == null || rows.length == 0) {
            return Optional.empty();
        }
        RecordRow row = rows[0];
        return Optional.of(new DomainConfig(row.policy(), row.ttl(), addresses(row.id())));
    }

    private List<AddressConfig> addresses(long recordId) {
        AddressConfig[] rows = supabase.get()
                .uri(uri -> uri.path("/dns_addresses")
                        .queryParam("select", "address,weight,country")
                        .queryParam("record_id", "eq." + recordId)
                        .queryParam("order", "id.asc")
                        .build())
                .retrieve()
                .body(AddressConfig[].class);
        return rows == null ? List.of() : Arrays.asList(rows);
    }

    public Optional<String> countryForIp(String ip) {
        long address = ipv4Number(ip);
        if (address < 0) {
            return Optional.empty();
        }
        IpRange[] ranges = supabase.get()
                .uri(uri -> uri.path("/ip_country_networks")
                        .queryParam("select", "network,country")
                        .build())
                .retrieve()
                .body(IpRange[].class);
        if (ranges == null) {
            return Optional.empty();
        }

        String country = null;
        int longestPrefix = -1;
        for (IpRange range : ranges) {
            String[] cidr = range.network().split("/");
            int prefix = Integer.parseInt(cidr[1]);
            long mask = (0xffff_ffffL << (32 - prefix)) & 0xffff_ffffL;
            if (prefix > longestPrefix && (address & mask) == (ipv4Number(cidr[0]) & mask)) {
                country = range.country();
                longestPrefix = prefix;
            }
        }
        return Optional.ofNullable(country);
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

    private String normalize(String domain) {
        return domain.trim().toLowerCase();
    }

    private record RecordRow(long id, String policy, int ttl) {
    }

    private record IdRow(long id) {
    }

    private record IpRange(String network, String country) {
    }
}
