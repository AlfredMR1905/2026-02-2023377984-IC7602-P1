package dnsapi;

import dnsapi.ApiModels.AddressConfig;
import dnsapi.ApiModels.DomainConfig;
import dnsapi.ApiModels.DomainView;
import dnsapi.ApiModels.IpCountryNetwork;
import dnsapi.ApiModels.SaveDomainRequest;
import dnsapi.ApiModels.SaveIpCountryNetwork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public List<DomainView> findAll() {
        DomainRow[] rows = supabase.get()
                .uri(uri -> uri.path("/dns_records")
                        .queryParam("select", "id,domain,policy,ttl")
                        .queryParam("order", "domain.asc")
                        .build())
                .retrieve()
                .body(DomainRow[].class);
        if (rows == null) {
            return List.of();
        }
        List<DomainView> domains = new ArrayList<>();
        for (DomainRow row : rows) {
            domains.add(new DomainView(row.id(), row.domain(), row.policy(), row.ttl(), addresses(row.id())));
        }
        return domains;
    }

    public long create(SaveDomainRequest request) {
        validate(request);
        IdRow[] rows = supabase.post()
                .uri("/dns_records")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Prefer", "return=representation")
                .body(Map.of("domain", normalize(request.domain()), "policy", request.policy(), "ttl", request.ttl()))
                .retrieve()
                .body(IdRow[].class);
        long id = rows[0].id();
        try {
            insertAddresses(id, request.addresses());
        } catch (RuntimeException error) {
            delete(id);
            throw error;
        }
        return id;
    }

    public boolean update(long id, SaveDomainRequest request) {
        validate(request);
        if (!recordExists(id)) {
            return false;
        }
        supabase.patch()
                .uri(uri -> uri.path("/dns_records").queryParam("id", "eq." + id).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domain", normalize(request.domain()), "policy", request.policy(), "ttl", request.ttl()))
                .retrieve()
                .toBodilessEntity();
        supabase.delete()
                .uri(uri -> uri.path("/dns_addresses").queryParam("record_id", "eq." + id).build())
                .retrieve()
                .toBodilessEntity();
        insertAddresses(id, request.addresses());
        return true;
    }

    public boolean delete(long id) {
        if (!recordExists(id)) {
            return false;
        }
        supabase.delete()
                .uri(uri -> uri.path("/dns_records").queryParam("id", "eq." + id).build())
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    private boolean recordExists(long id) {
        IdRow[] rows = supabase.get()
                .uri(uri -> uri.path("/dns_records")
                        .queryParam("select", "id")
                        .queryParam("id", "eq." + id)
                        .build())
                .retrieve()
                .body(IdRow[].class);
        return rows != null && rows.length > 0;
    }

    private void insertAddresses(long id, List<AddressConfig> addresses) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AddressConfig address : addresses) {
            Map<String, Object> row = new HashMap<>();
            row.put("record_id", id);
            row.put("address", address.address());
            row.put("weight", address.weight());
            row.put("country", address.country() == null ? null : address.country().toUpperCase());
            rows.add(row);
        }
        supabase.post()
                .uri("/dns_addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(rows)
                .retrieve()
                .toBodilessEntity();
    }

    private void validate(SaveDomainRequest request) {
        if (request == null || request.domain() == null || request.domain().isBlank()
                || request.policy() == null || !List.of("single", "multi", "weight", "geo").contains(request.policy())
                || request.ttl() <= 0 || request.addresses() == null || request.addresses().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dominio, política, TTL y direcciones válidas son obligatorios");
        }
        if (request.policy().equals("single") && request.addresses().size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "single requiere una dirección");
        }
        for (AddressConfig address : request.addresses()) {
            if (address == null || ipv4Number(address.address()) < 0 || address.weight() <= 0
                    || (address.country() != null && !address.country().matches("[A-Za-z]{2}"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dirección, peso o país inválido");
            }
        }
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

    public List<IpCountryNetwork> findAllNetworks() {
        NetworkRow[] rows = supabase.get()
                .uri(uri -> uri.path("/ip_country_networks")
                        .queryParam("select", "id,network,country")
                        .queryParam("order", "network.asc")
                        .build())
                .retrieve()
                .body(NetworkRow[].class);
        if (rows == null) {
            return List.of();
        }
        return Arrays.stream(rows)
                .map(row -> new IpCountryNetwork(row.id(), row.network(), row.country()))
                .toList();
    }

    public long createNetwork(SaveIpCountryNetwork request) {
        validateNetwork(request);
        IdRow[] rows = supabase.post()
                .uri("/ip_country_networks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Prefer", "return=representation")
                .body(Map.of("network", request.network(), "country", request.country().toUpperCase()))
                .retrieve()
                .body(IdRow[].class);
        return rows[0].id();
    }

    public boolean updateNetwork(long id, SaveIpCountryNetwork request) {
        validateNetwork(request);
        if (!networkExists(id)) {
            return false;
        }
        supabase.patch()
                .uri(uri -> uri.path("/ip_country_networks").queryParam("id", "eq." + id).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("network", request.network(), "country", request.country().toUpperCase()))
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    public boolean deleteNetwork(long id) {
        if (!networkExists(id)) {
            return false;
        }
        supabase.delete()
                .uri(uri -> uri.path("/ip_country_networks").queryParam("id", "eq." + id).build())
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    private boolean networkExists(long id) {
        IdRow[] rows = supabase.get()
                .uri(uri -> uri.path("/ip_country_networks")
                        .queryParam("select", "id")
                        .queryParam("id", "eq." + id)
                        .build())
                .retrieve()
                .body(IdRow[].class);
        return rows != null && rows.length > 0;
    }

    private void validateNetwork(SaveIpCountryNetwork request) {
        if (request == null || request.network() == null || !request.network().matches("[^/]+/(?:[0-9]|[12][0-9]|3[0-2])")
                || ipv4Number(request.network().split("/")[0]) < 0
                || request.country() == null || !request.country().matches("[A-Za-z]{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Red CIDR o país inválido");
        }
    }

    private long ipv4Number(String ip) {
        if (ip == null) {
            return -1;
        }
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

    private record DomainRow(long id, String domain, String policy, int ttl) {
    }

    private record NetworkRow(long id, String network, String country) {
    }
}
