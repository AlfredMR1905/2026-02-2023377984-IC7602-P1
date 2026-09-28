package dnsapi;

import dnsapi.ApiModels.AddressConfig;
import dnsapi.ApiModels.DomainConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DomainStore {

    private final Map<String, DomainConfig> domains = Map.of(
            "ejemplo.test",
            new DomainConfig(
                    "single",
                    60,
                    List.of(new AddressConfig("10.0.0.25", 1))
            ),
            "multi.test",
            new DomainConfig(
                    "multi",
                    60,
                    List.of(
                            new AddressConfig("10.0.0.11", 1),
                            new AddressConfig("10.0.0.12", 1)
                    )
            ),
            "weight.test",
            new DomainConfig(
                    "weight",
                    60,
                    List.of(
                            new AddressConfig("10.0.0.21", 3),
                            new AddressConfig("10.0.0.22", 1)
                    )
            )
    );

    public boolean exists(String domain) {
        return domains.containsKey(normalize(domain));
    }

    public Optional<DomainConfig> find(String domain) {
        return Optional.ofNullable(domains.get(normalize(domain)));
    }

    private String normalize(String domain) {
        return domain.trim().toLowerCase();
    }
}
