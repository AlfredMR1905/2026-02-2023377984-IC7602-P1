mod dns;

use dns::Header;
use std::env;
use std::net::UdpSocket;

fn main() -> std::io::Result<()> {
    let listen_addr = env::var("DNS_LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:5358".into());
    let socket = UdpSocket::bind(&listen_addr)?;
    println!("Escuchando DNS por UDP en {}", socket.local_addr()?);
    println!("Etapa actual: lectura de cabeceras; todavía no se envían respuestas DNS.");

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
            }
            Err(error) => eprintln!("Paquete descartado desde {origen}: {error}"),
        }
    }
}
