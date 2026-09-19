use base64::{Engine as _, engine::general_purpose::STANDARD};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use std::net::Ipv4Addr;
use std::time::Duration;

pub struct ApiClient {
    base_url: String,
    client: Client,
}

#[derive(Deserialize)]
struct ExistsResponse {
    exists: bool,
}

#[derive(Deserialize)]
struct DomainConfig {
    policy: String,
    #[serde(default = "default_ttl")]
    ttl: u32,
    addresses: Vec<AddressConfig>,
}

#[derive(Deserialize)]
struct AddressConfig {
    address: Ipv4Addr,
}

#[derive(Serialize, Deserialize)]
struct DnsPacketBody {
    data: String,
}

fn default_ttl() -> u32 {
    60
}

impl ApiClient {
    pub fn new(base_url: String) -> Result<Self, String> {
        let client = Client::builder()
            .timeout(Duration::from_secs(5))
            .build()
            .map_err(|error| format!("No se pudo crear el cliente HTTP: {error}"))?;

        Ok(Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            client,
        })
    }

    pub fn domain_exists(&self, domain: &str) -> Result<bool, String> {
        let response = self
            .client
            .get(format!("{}/api/exists", self.base_url))
            .query(&[("domain", domain)])
            .send()
            .map_err(|error| format!("Falló GET /api/exists: {error}"))?
            .error_for_status()
            .map_err(|error| format!("GET /api/exists respondió con error: {error}"))?
            .json::<ExistsResponse>()
            .map_err(|error| format!("Respuesta inválida de GET /api/exists: {error}"))?;

        Ok(response.exists)
    }

    pub fn single_record(&self, domain: &str) -> Result<(Ipv4Addr, u32), String> {
        let config = self
            .client
            .get(format!("{}/api/records", self.base_url))
            .query(&[("domain", domain)])
            .send()
            .map_err(|error| format!("Falló GET /api/records: {error}"))?
            .error_for_status()
            .map_err(|error| format!("GET /api/records respondió con error: {error}"))?
            .json::<DomainConfig>()
            .map_err(|error| format!("Respuesta inválida de GET /api/records: {error}"))?;

        if config.policy != "single" {
            return Err(format!(
                "La política '{}' todavía no está implementada",
                config.policy
            ));
        }

        if config.addresses.len() != 1 {
            return Err("La política single requiere exactamente una dirección IPv4".to_string());
        }

        Ok((config.addresses[0].address, config.ttl))
    }

    pub fn resolve_dns(&self, packet: &[u8]) -> Result<Vec<u8>, String> {
        let body = DnsPacketBody {
            data: STANDARD.encode(packet),
        };

        let response = self
            .client
            .post(format!("{}/api/dns_resolver", self.base_url))
            .json(&body)
            .send()
            .map_err(|error| format!("Falló POST /api/dns_resolver: {error}"))?
            .error_for_status()
            .map_err(|error| format!("POST /api/dns_resolver respondió con error: {error}"))?
            .json::<DnsPacketBody>()
            .map_err(|error| format!("Respuesta inválida de POST /api/dns_resolver: {error}"))?;

        STANDARD
            .decode(response.data)
            .map_err(|error| format!("BASE64 inválido en /api/dns_resolver: {error}"))
    }
}
