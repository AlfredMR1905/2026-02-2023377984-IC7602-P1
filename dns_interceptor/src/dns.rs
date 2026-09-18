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
        ((self.flags >> 11) & 0x000f) as u8
    }

    pub fn is_standard_query(&self) -> bool {
        !self.is_response() && self.opcode() == 0
    }
}
