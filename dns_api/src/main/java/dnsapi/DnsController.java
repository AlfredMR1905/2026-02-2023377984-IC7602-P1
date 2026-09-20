package dnsapi;

import dnsapi.ApiModels.DnsPacketBody;
import dnsapi.ApiModels.DomainConfig;
import dnsapi.ApiModels.ExistsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Base64;

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
