use std::io::{self, ErrorKind};

/// Los seis campos de 16 bits de la cabecera DNS.
#[derive(Debug, PartialEq, Eq)]
pub struct Header {
    pub id: u16,
    pub flags: u16,
    pub question_count: u16,
    pub answer_count: u16,
    pub authority_count: u16,
    pub additional_count: u16,
}

impl Header {
    pub fn parse(packet: &[u8]) -> io::Result<Self> {
        if packet.len() < 12 {
            return Err(io::Error::new(
                ErrorKind::InvalidData,
                "La cabecera DNS requiere al menos 12 bytes",
            ));
        }

        // DNS transmite primero el byte más significativo(big-endian)
        let read_u16 = |offset| u16::from_be_bytes([packet[offset], packet[offset + 1]]);

        Ok(Self {
            id: read_u16(0),
            flags: read_u16(2),
            question_count: read_u16(4),
            answer_count: read_u16(6),
            authority_count: read_u16(8),
            additional_count: read_u16(10),
        })
    }

    pub fn is_response(&self) -> bool {
        // QR es el bit 15 (0 consulta y 1 respuesta)
        self.flags & 0x8000 != 0
    }

    pub fn opcode(&self) -> u8 {
        // OPCODE ocupa los bits 14..11. Se desplazan y se conservan cuatro bits.
        // Operacion DNS que se realiza
        // 0 es QUERY
        ((self.flags >> 11) & 0x000f) as u8
    }

    pub fn is_standard_query(&self) -> bool {
        !self.is_response() && self.opcode() == 0
    }
}
#[derive(Debug, PartialEq, Eq)]
pub struct Question {
    pub name: String,
    pub qtype: u16,  // Informacion que se quiere del dominio
    pub qclass: u16, //Sistema DNS usado
}

impl Question {
    /// Devuelve la pregunta y la posicion donde continua el paquete
    pub fn parse(packet: &[u8], offset: usize) -> io::Result<(Self, usize)> {
        let (name, next_offset) = read_name(packet, offset)?;
        let fields = packet
            .get(next_offset..next_offset + 4)
            .ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "Faltan QTYPE o QCLASS"))?;

        let question = Self {
            name,
            qtype: u16::from_be_bytes([fields[0], fields[1]]),
            qclass: u16::from_be_bytes([fields[2], fields[3]]),
        };

        Ok((question, next_offset + 4))
    }
}

fn read_name(packet: &[u8], offset: usize) -> io::Result<(String, usize)> {
    let mut cursor = offset;
    let mut labels = Vec::new();
    let mut next_offset = None;
    let mut visited = vec![false; packet.len()];
    let mut expanded_name_length = 1; // Contamos el 0 del final

    loop {
        let length = *packet
            .get(cursor)
            .ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "Nombre DNS incompleto"))?;

        if visited[cursor] {
            return Err(io::Error::new(
                ErrorKind::InvalidData,
                "Ciclo en los punteros del nombre DNS",
            ));
        }
        visited[cursor] = true;

        match length & 0xc0 {
            0x00 => {
                cursor += 1;
                if length == 0 {
                    let name = if labels.is_empty() {
                        ".".to_string()
                    } else {
                        labels.join(".")
                    };
                    return Ok((name, next_offset.unwrap_or(cursor)));
                }

                let end = cursor + usize::from(length);
                let label = packet.get(cursor..end).ok_or_else(|| {
                    io::Error::new(ErrorKind::InvalidData, "Etiqueta DNS incompleta")
                })?;

                expanded_name_length += label.len() + 1;
                if expanded_name_length > 255 {
                    return Err(io::Error::new(
                        ErrorKind::InvalidData,
                        "El nombre DNS expandido excede 255 bytes",
                    ));
                }

                let mut text = String::new();
                for &byte in label {
                    if byte.is_ascii_graphic() && byte != b'.' && byte != b'\\' {
                        text.push(char::from(byte));
                    } else {
                        text.push_str(&format!("\\{byte:03}"));
                    }
                }
                labels.push(text);
                cursor = end;
            }
            0xc0 => {
                let second = *packet.get(cursor + 1).ok_or_else(|| {
                    io::Error::new(ErrorKind::InvalidData, "Puntero DNS incompleto")
                })?;
                // Los dos primeros bits indica que hablamos de un puntero y los otros
                // catorce contienen la posición de un nombre anterior
                let target = (usize::from(length & 0x3f) << 8) | usize::from(second);
                if target < 12 || target >= cursor {
                    return Err(io::Error::new(
                        ErrorKind::InvalidData,
                        "El puntero DNS no apunta a un nombre anterior válido",
                    ));
                }

                next_offset.get_or_insert(cursor + 2);
                cursor = target;
            }
            _ => {
                return Err(io::Error::new(
                    ErrorKind::InvalidData,
                    "Formato de etiqueta DNS no admitido",
                ));
            }
        }
    }
}
