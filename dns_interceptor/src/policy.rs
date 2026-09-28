use crate::api::DomainConfig;
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::Mutex;

pub struct PolicySelector {
    positions: Mutex<HashMap<String, usize>>,
}

impl PolicySelector {
    pub fn new() -> Self {
        Self {
            positions: Mutex::new(HashMap::new()),
        }
    }

    pub fn select(&self, domain: &str, config: &DomainConfig) -> Result<Ipv4Addr, String> {
        match config.policy.as_str() {
            "single" => {
                if config.addresses.len() != 1 {
                    return Err("single requiere exactamente una dirección IPv4".into());
                }
                Ok(config.addresses[0].address)
            }
            "multi" => {
                if config.addresses.is_empty() {
                    return Err("multi requiere al menos una dirección IPv4".into());
                }
                let position = self.next_position(domain, config.addresses.len())?;
                Ok(config.addresses[position].address)
            }
            "weight" => {
                let total = config.addresses.iter().try_fold(0usize, |sum, address| {
                    sum.checked_add(address.weight as usize)
                        .ok_or("La suma de pesos excede el límite permitido")
                })?;
                if total == 0 {
                    return Err("weight requiere al menos un peso positivo".into());
                }

                let mut position = self.next_position(domain, total)?;
                for address in &config.addresses {
                    let weight = address.weight as usize;
                    if position < weight {
                        return Ok(address.address);
                    }
                    position -= weight;
                }
                Err("No se pudo seleccionar una dirección para weight".into())
            }
            policy => Err(format!("Política DNS desconocida: {policy}")),
        }
    }

    fn next_position(&self, domain: &str, total: usize) -> Result<usize, String> {
        let mut positions = self
            .positions
            .lock()
            .map_err(|_| "No se pudo acceder a los turnos DNS")?;
        let next = positions.entry(domain.to_string()).or_insert(0);
        let position = *next % total;
        *next = (position + 1) % total;
        Ok(position)
    }
}
