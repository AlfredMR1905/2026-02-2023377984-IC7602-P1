mod api;
mod dns;
mod policy;

use api::ApiClient;
use dns::{Header, Question, build_a_response};
use policy::PolicySelector;
use std::env;
use std::io::{self, ErrorKind};
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::thread;

fn main() -> std::io::Result<()> {
    let listen_addr = env::var("DNS_LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:5358".into());
    let api_base_url =
        env::var("DNS_API_BASE_URL").unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let api_ca_cert = env::var("DNS_API_CA_CERT").ok();
    let api = Arc::new(
        ApiClient::new(api_base_url, api_ca_cert.as_deref())
            .map_err(|error| io::Error::new(ErrorKind::InvalidInput, error))?,
    );
    let selector = Arc::new(PolicySelector::new());

    let socket = Arc::new(UdpSocket::bind(&listen_addr)?);
    println!("Escuchando DNS por UDP en {}", socket.local_addr()?);

    let mut buf = [0u8; 65535];

    loop {
        let (bytes_received, source) = socket.recv_from(&mut buf)?;
        let packet = buf[..bytes_received].to_vec();
        let worker_socket = Arc::clone(&socket);
        let worker_api = Arc::clone(&api);
        let worker_selector = Arc::clone(&selector);

        thread::spawn(move || {
            handle_packet(
                &worker_socket,
                &worker_api,
                &worker_selector,
                &packet,
                source,
            );
        });
    }
}

fn handle_packet(
    socket: &UdpSocket,
    api: &ApiClient,
    selector: &PolicySelector,
    packet: &[u8],
    source: SocketAddr,
) {
    let header = match Header::parse(packet) {
        Ok(header) => header,
        Err(error) => {
            eprintln!("Paquete descartado desde {source}: {error}");
            return;
        }
    };

    if !header.is_standard_query() || header.question_count != 1 {
        forward_packet(socket, api, packet, source);
        return;
    }

    let (question, question_end) = match Question::parse(packet, 12) {
        Ok(result) => result,
        Err(error) => {
            eprintln!("No se pudo interpretar la pregunta desde {source}: {error}");
            forward_packet(socket, api, packet, source);
            return;
        }
    };

    println!(
        "Desde {source}: dominio={}, QTYPE={}, QCLASS={}",
        question.name, question.qtype, question.qclass
    );

    if question.qtype != 1 || question.qclass != 1 {
        forward_packet(socket, api, packet, source);
        return;
    }

    let domain = question.name.to_ascii_lowercase();
    match api.domain_exists(&domain) {
        Ok(true) => match api.domain_config(&domain) {
            Ok(config) => {
                let country = if config.policy == "geo" {
                    match api.country_for_ip(&source.ip().to_string()) {
                        Ok(country) => country,
                        Err(error) => {
                            eprintln!("No se pudo identificar el país de {source}: {error}");
                            None
                        }
                    }
                } else {
                    None
                };

                match selector.select(&domain, &config, country.as_deref()) {
                    Ok(ipv4) => match build_a_response(
                        packet,
                        &header,
                        question_end,
                        ipv4.octets(),
                        config.ttl,
                    ) {
                        Ok(response) => send_packet(socket, &response, source, "respuesta local"),
                        Err(error) => {
                            eprintln!("No se pudo construir la respuesta para {source}: {error}")
                        }
                    },
                    Err(error) => eprintln!("No se pudo seleccionar la IP para {domain}: {error}"),
                }
            }
            Err(error) => eprintln!("No se pudo resolver {domain}: {error}"),
        },
        Ok(false) => forward_packet(socket, api, packet, source),
        Err(error) => eprintln!("No se pudo consultar {domain}: {error}"),
    }
}

fn forward_packet(socket: &UdpSocket, api: &ApiClient, packet: &[u8], source: SocketAddr) {
    match api.resolve_dns(packet) {
        Ok(response) => send_packet(socket, &response, source, "respuesta externa"),
        Err(error) => eprintln!("No se pudo reenviar la consulta de {source}: {error}"),
    }
}

fn send_packet(socket: &UdpSocket, packet: &[u8], destination: SocketAddr, description: &str) {
    match socket.send_to(packet, destination) {
        Ok(bytes_sent) => {
            println!("{description} enviada a {destination}: {bytes_sent} bytes")
        }
        Err(error) => eprintln!("No se pudo enviar a {destination}: {error}"),
    }
}
