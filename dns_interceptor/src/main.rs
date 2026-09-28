mod api;
mod dns;

use api::ApiClient;
use dns::{Header, Question, build_a_response};
use std::env;
use std::io::{self, ErrorKind};
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::thread;

fn main() -> std::io::Result<()> {
    let listen_addr = env::var("DNS_LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:5358".into());
    let api_base_url =
        env::var("DNS_API_BASE_URL").unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let api = Arc::new(
        ApiClient::new(api_base_url)
            .map_err(|error| io::Error::new(ErrorKind::InvalidInput, error))?,
    );

    let socket = Arc::new(UdpSocket::bind(&listen_addr)?);
    println!("Escuchando DNS por UDP en {}", socket.local_addr()?);

    let mut buf = [0u8; 65535];

    loop {
        let (bytes_received, source) = socket.recv_from(&mut buf)?;
        let packet = buf[..bytes_received].to_vec();
        let worker_socket = Arc::clone(&socket);
        let worker_api = Arc::clone(&api);

        thread::spawn(move || {
            handle_packet(&worker_socket, &worker_api, &packet, source);
        });
    }
}

fn handle_packet(socket: &UdpSocket, api: &ApiClient, packet: &[u8], source: SocketAddr) {
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
        Ok(true) => match api.single_record(&domain) {
            Ok((ipv4, ttl)) => {
                match build_a_response(packet, &header, question_end, ipv4.octets(), ttl) {
                    Ok(response) => send_packet(socket, &response, source, "respuesta local"),
                    Err(error) => {
                        eprintln!("No se pudo construir la respuesta para {source}: {error}")
                    }
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
