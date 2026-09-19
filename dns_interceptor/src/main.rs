mod dns;

use dns::{Header, Question, build_a_response};
use std::env;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, UdpSocket};

const RESPONSE_TTL_SECONDS: u32 = 60;

fn main() -> std::io::Result<()> {
    let listen_addr = env::var("DNS_LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:5358".into());
    let response_ipv4 = env::var("DNS_RESPONSE_IPV4")
        .unwrap_or_else(|_| "127.0.0.1".into())
        .parse::<Ipv4Addr>()
        .map_err(|error| io::Error::new(ErrorKind::InvalidInput, error))?;

    let socket = UdpSocket::bind(&listen_addr)?;
    println!("Escuchando DNS por UDP en {}", socket.local_addr()?);
    println!("IPv4 temporal para respuestas A: {response_ipv4}");

    // Recibir el datagrama completo, en este caso al maximo de IPV4 osea 2^16 -1 o 65535 (Sin truncar)
    let mut buf = [0u8; 65535];

    loop {
        let (bytes_recibidos, origen) = socket.recv_from(&mut buf)?;
        let packet = &buf[..bytes_recibidos];
        match Header::parse(packet) {
            Ok(header) => {
                println!("Desde {origen}: {bytes_recibidos} bytes, {header:?}");
                println!(
                    "QR={}, OPCODE={}, consulta estandar={}",
                    u8::from(header.is_response()),
                    header.opcode(),
                    header.is_standard_query()
                );

                if header.is_standard_query() && header.question_count == 1 {
                    match Question::parse(packet, 12) {
                        Ok((question, next_offset)) => {
                            println!(
                                "Dominio={}, QTYPE={}, QCLASS={}, fin de pregunta={next_offset}",
                                question.name, question.qtype, question.qclass
                            );

                            if question.qtype != 1 || question.qclass != 1 {
                                println!(
                                    "Solo las consultas A/IN tienen respuesta"
                                );
                                continue;
                            }

                            match build_a_response(
                                packet,
                                &header,
                                next_offset,
                                response_ipv4.octets(),
                                RESPONSE_TTL_SECONDS,
                            ) {
                                Ok(response) => match socket.send_to(&response, origen) {
                                    Ok(bytes_sent) => println!(
                                        "Respuesta enviada a {origen}: {bytes_sent} bytes, IPv4={response_ipv4}"
                                    ),
                                    Err(error) => {
                                        eprintln!("No se pudo responder a {origen}: {error}")
                                    }
                                },
                                Err(error) => {
                                    eprintln!(
                                        "No se pudo construir la respuesta para {origen}: {error}"
                                    )
                                }
                            }
                        }
                        Err(error) => {
                            eprintln!("Pregunta descartada desde {origen}: {error}")
                        }
                    }
                } else {
                    println!("Lectura local limitada a consultas estabdar con una pregunta");
                }
            }
            Err(error) => eprintln!("Paquete descartado desde {origen}: {error}"),
        }
    }
}
