package dnsapi;

import dnsapi.ApiModels.DnsPacketBody;
import dnsapi.ApiModels.CountryResponse;
import dnsapi.ApiModels.DomainConfig;
import dnsapi.ApiModels.DomainView;
import dnsapi.ApiModels.ExistsResponse;
import dnsapi.ApiModels.IpCountryNetwork;
import dnsapi.ApiModels.SaveDomainRequest;
import dnsapi.ApiModels.SaveIpCountryNetwork;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DnsController {

    private final DomainStore domainStore;
    private final DnsResolverService resolverService;

    public DnsController(DomainStore domainStore, DnsResolverService resolverService) {
        this.domainStore = domainStore;
        this.resolverService = resolverService;
    }

    @GetMapping("/exists")
    public ExistsResponse exists(@RequestParam String domain) {
        return new ExistsResponse(domainStore.exists(domain));
    }

    @GetMapping("/records")
    public DomainConfig records(@RequestParam String domain) {
        return domainStore.find(domain)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El dominio no está configurado"
                ));
    }

    @GetMapping("/country")
    public CountryResponse country(@RequestParam String ip) {
        return new CountryResponse(domainStore.countryForIp(ip).orElse(null));
    }

    @GetMapping("/domains")
    public List<DomainView> domains() {
        return domainStore.findAll();
    }

    @PostMapping("/domains")
    public ResponseEntity<Void> createDomain(@RequestBody SaveDomainRequest request) {
        long id = domainStore.create(request);
        return ResponseEntity.created(URI.create("/api/domains/" + id)).build();
    }

    @PutMapping("/domains/{id}")
    public ResponseEntity<Void> updateDomain(@PathVariable long id, @RequestBody SaveDomainRequest request) {
        return domainStore.update(id, request)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/domains/{id}")
    public ResponseEntity<Void> deleteDomain(@PathVariable long id) {
        return domainStore.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/ip-networks")
    public List<IpCountryNetwork> networks() {
        return domainStore.findAllNetworks();
    }

    @PostMapping("/ip-networks")
    public ResponseEntity<Void> createNetwork(@RequestBody SaveIpCountryNetwork request) {
        long id = domainStore.createNetwork(request);
        return ResponseEntity.created(URI.create("/api/ip-networks/" + id)).build();
    }

    @PutMapping("/ip-networks/{id}")
    public ResponseEntity<Void> updateNetwork(@PathVariable long id, @RequestBody SaveIpCountryNetwork request) {
        return domainStore.updateNetwork(id, request)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/ip-networks/{id}")
    public ResponseEntity<Void> deleteNetwork(@PathVariable long id) {
        return domainStore.deleteNetwork(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/dns_resolver")
    public DnsPacketBody resolve(@RequestBody DnsPacketBody body) {
        try {
            byte[] query = Base64.getDecoder().decode(body.data());
            byte[] response = resolverService.resolve(query);
            return new DnsPacketBody(Base64.getEncoder().encodeToString(response));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        } catch (SocketTimeoutException error) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "El DNS externo no respondió a tiempo",
                    error
            );
        } catch (IOException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar el DNS externo",
                    error
            );
        }
    }
}
