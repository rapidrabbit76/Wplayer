//! Lists a server's shares with MS-SRVS `NetrShareEnum` over the `srvsvc` pipe.
//!
//! Hand-encoded DCE/RPC using the classic 32-bit NDR transfer syntax, which
//! every SMB server speaks. The `smb` crate only offers NDR64, and Samba
//! (so most home file servers) rejects that for `srvsvc`.

use crate::error::{CoreError, CoreResult};

pub const PIPE_NAME: &str = "srvsvc";
/// Largest fragment either side sends; one fragment holds about fifty shares.
pub const MAX_FRAGMENT: u16 = 4280;

const PTYPE_REQUEST: u8 = 0;
const PTYPE_RESPONSE: u8 = 2;
const PTYPE_FAULT: u8 = 3;
const PTYPE_BIND: u8 = 11;
const PTYPE_BIND_ACK: u8 = 12;
const FIRST_AND_LAST_FRAGMENT: u8 = 0x03;
const LAST_FRAGMENT: u8 = 0x02;
const HEADER_LEN: usize = 16;
const OPNUM_NETR_SHARE_ENUM: u16 = 15;

const STYPE_DISKTREE: u32 = 0;
const STYPE_KIND_MASK: u32 = 0xFF;
const STYPE_SPECIAL: u32 = 0x8000_0000;

#[derive(Debug, PartialEq, Eq)]
pub struct Share {
    pub name: String,
    pub share_type: u32,
}

impl Share {
    /// A disk share a user browses; not IPC$, printers or hidden admin shares such as C$.
    pub fn is_browsable(&self) -> bool {
        self.share_type & STYPE_KIND_MASK == STYPE_DISKTREE
            && self.share_type & STYPE_SPECIAL == 0
            && !self.name.ends_with('$')
    }
}

/// Binds the pipe to srvsvc v3.0 with NDR 2.0.
pub fn bind_request() -> Vec<u8> {
    let mut body = Vec::new();
    body.extend(MAX_FRAGMENT.to_le_bytes()); // max transmit fragment
    body.extend(MAX_FRAGMENT.to_le_bytes()); // max receive fragment
    body.extend(0u32.to_le_bytes()); // new association group
    body.extend([1, 0, 0, 0]); // one presentation context
    body.extend(0u16.to_le_bytes()); // context id
    body.extend([1, 0]); // one transfer syntax
    body.extend(syntax_id(
        0x4B32_4FC8,
        0x1670,
        0x01D3,
        [0x12, 0x78, 0x5A, 0x47, 0xBF, 0x6E, 0xE1, 0x88],
        3,
    ));
    body.extend(syntax_id(
        0x8A88_5D04,
        0x1CEB,
        0x11C9,
        [0x9F, 0xE8, 0x08, 0x00, 0x2B, 0x10, 0x48, 0x60],
        2,
    ));
    pdu(PTYPE_BIND, 1, &body)
}

/// Fails unless the server accepted the presentation context.
pub fn check_bind_ack(reply: &[u8]) -> CoreResult<()> {
    let mut r = Reader::new(reply);
    match r.header()? {
        PTYPE_BIND_ACK => {}
        other => return Err(protocol(format!("bind rejected (packet type {other})"))),
    }
    r.skip(8)?; // fragment sizes and association group
    let address_len = r.u16()? as usize;
    r.skip(address_len)?;
    r.align(4);
    let results = r.u8()?;
    r.skip(3)?;
    if results == 0 {
        return Err(protocol("bind acknowledged no context".into()));
    }
    match r.u16()? {
        0 => Ok(()),
        result => Err(protocol(format!("NDR transfer syntax refused (result {result})"))),
    }
}

/// `NetrShareEnum` at information level 1 (name, type, remark).
pub fn share_enum_request(server: &str) -> Vec<u8> {
    let mut stub = Vec::new();
    put_u32(&mut stub, 0x0002_0000); // ServerName: unique pointer
    put_string(&mut stub, &format!("\\\\{server}"));
    put_u32(&mut stub, 1); // InfoStruct.Level
    put_u32(&mut stub, 1); // union discriminant
    put_u32(&mut stub, 0x0002_0004); // pointer to SHARE_INFO_1_CONTAINER
    put_u32(&mut stub, 0); // EntriesRead
    put_u32(&mut stub, 0); // Buffer: none yet
    put_u32(&mut stub, u32::MAX); // PreferedMaximumLength: everything
    put_u32(&mut stub, 0x0002_0008); // ResumeHandle: unique pointer
    put_u32(&mut stub, 0);

    let mut body = Vec::new();
    body.extend((stub.len() as u32).to_le_bytes()); // allocation hint
    body.extend(0u16.to_le_bytes()); // context id
    body.extend(OPNUM_NETR_SHARE_ENUM.to_le_bytes());
    body.extend(stub);
    pdu(PTYPE_REQUEST, 2, &body)
}

pub fn parse_share_enum_response(reply: &[u8]) -> CoreResult<Vec<Share>> {
    let mut r = Reader::new(reply);
    let ptype = r.header()?;
    if ptype == PTYPE_FAULT {
        r.skip(8)?; // allocation hint, context id, cancel count, reserved
        return Err(protocol(format!("server fault 0x{:08x}", r.u32()?)));
    }
    if ptype != PTYPE_RESPONSE {
        return Err(protocol(format!("unexpected packet type {ptype}")));
    }
    if reply[3] & LAST_FRAGMENT == 0 {
        return Err(protocol("share list spans several RPC fragments".into()));
    }
    r.skip(8)?; // allocation hint, context id, cancel count, reserved
    // NDR alignment counts from the start of the stub.
    let mut stub = Reader::new(r.rest());

    let status = stub.trailing_u32()?;
    if status != 0 {
        return Err(protocol(format!("NetrShareEnum failed with error {status}")));
    }
    stub.skip(8)?; // level, union discriminant
    if stub.u32()? == 0 {
        return Ok(Vec::new()); // no container
    }
    let count = stub.u32()? as usize;
    if stub.u32()? == 0 || count == 0 {
        return Ok(Vec::new()); // no entries
    }
    if stub.u32()? as usize != count {
        return Err(protocol("share array size disagrees with its count".into()));
    }
    let mut headers = Vec::with_capacity(count);
    for _ in 0..count {
        let name_ptr = stub.u32()?;
        let share_type = stub.u32()?;
        let remark_ptr = stub.u32()?;
        headers.push((name_ptr, share_type, remark_ptr));
    }
    // The strings follow the array, in entry order: name, then remark.
    let mut shares = Vec::with_capacity(count);
    for (name_ptr, share_type, remark_ptr) in headers {
        let name = if name_ptr != 0 { stub.string()? } else { String::new() };
        if remark_ptr != 0 {
            stub.string()?;
        }
        if !name.is_empty() {
            shares.push(Share { name, share_type });
        }
    }
    Ok(shares)
}

fn pdu(ptype: u8, call_id: u32, body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN + body.len());
    out.extend([5, 0, ptype, FIRST_AND_LAST_FRAGMENT]);
    out.extend([0x10, 0, 0, 0]); // little-endian, ASCII, IEEE floats
    out.extend(((HEADER_LEN + body.len()) as u16).to_le_bytes());
    out.extend(0u16.to_le_bytes()); // no authentication
    out.extend(call_id.to_le_bytes());
    out.extend(body);
    out
}

fn syntax_id(data1: u32, data2: u16, data3: u16, data4: [u8; 8], major: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(20);
    out.extend(data1.to_le_bytes());
    out.extend(data2.to_le_bytes());
    out.extend(data3.to_le_bytes());
    out.extend(data4);
    out.extend(major.to_le_bytes());
    out.extend(0u16.to_le_bytes()); // minor version
    out
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend(value.to_le_bytes());
}

/// A conformant varying UTF-16 string with its terminator, padded to 4 bytes.
fn put_string(out: &mut Vec<u8>, value: &str) {
    let units: Vec<u16> = value.encode_utf16().chain(std::iter::once(0)).collect();
    put_u32(out, units.len() as u32); // maximum count
    put_u32(out, 0); // offset
    put_u32(out, units.len() as u32); // actual count
    for unit in units {
        out.extend(unit.to_le_bytes());
    }
    while !out.len().is_multiple_of(4) {
        out.push(0);
    }
}

fn protocol(detail: String) -> CoreError {
    CoreError::Connection {
        detail: format!("listing shares: {detail}"),
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    /// Checks the common header and returns the packet type; leaves the reader after it.
    fn header(&mut self) -> CoreResult<u8> {
        if self.data.len() < HEADER_LEN || self.data[0] != 5 {
            return Err(protocol("not a DCE/RPC reply".into()));
        }
        let ptype = self.data[2];
        self.pos = HEADER_LEN;
        Ok(ptype)
    }

    fn take(&mut self, len: usize) -> CoreResult<&'a [u8]> {
        let end = self.pos.checked_add(len).filter(|&end| end <= self.data.len());
        let end = end.ok_or_else(|| protocol("reply ended early".into()))?;
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn skip(&mut self, len: usize) -> CoreResult<()> {
        self.take(len).map(|_| ())
    }

    fn align(&mut self, to: usize) {
        self.pos = self.pos.next_multiple_of(to);
    }

    fn u8(&mut self) -> CoreResult<u8> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> CoreResult<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }

    fn u32(&mut self) -> CoreResult<u32> {
        self.align(4);
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    /// The last four bytes: a call's return value follows all of its out parameters.
    fn trailing_u32(&self) -> CoreResult<u32> {
        let start = self
            .data
            .len()
            .checked_sub(4)
            .ok_or_else(|| protocol("empty reply".into()))?;
        Ok(u32::from_le_bytes(self.data[start..].try_into().unwrap()))
    }

    fn rest(&self) -> &'a [u8] {
        &self.data[self.pos.min(self.data.len())..]
    }

    fn string(&mut self) -> CoreResult<String> {
        self.u32()?; // maximum count
        self.u32()?; // offset
        let count = self.u32()? as usize;
        let bytes = self.take(count.checked_mul(2).ok_or_else(|| protocol("string too long".into()))?)?;
        let units: Vec<u16> = bytes
            .as_chunks::<2>()
            .0
            .iter()
            .map(|&c| u16::from_le_bytes(c))
            .collect();
        let text = String::from_utf16_lossy(&units);
        Ok(text.trim_end_matches('\0').to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A response stub as Samba sends it, built with the request's own encoders.
    fn response(shares: &[(&str, u32, &str)], status: u32) -> Vec<u8> {
        let mut stub = Vec::new();
        put_u32(&mut stub, 1); // level
        put_u32(&mut stub, 1); // union discriminant
        put_u32(&mut stub, 0x0002_0000); // container pointer
        put_u32(&mut stub, shares.len() as u32);
        put_u32(&mut stub, 0x0002_0004); // buffer pointer
        put_u32(&mut stub, shares.len() as u32); // array size
        for (i, (_, share_type, _)) in shares.iter().enumerate() {
            put_u32(&mut stub, 0x0002_0008 + i as u32 * 8);
            put_u32(&mut stub, *share_type);
            put_u32(&mut stub, 0x0002_000C + i as u32 * 8);
        }
        for (name, _, remark) in shares {
            put_string(&mut stub, name);
            put_string(&mut stub, remark);
        }
        put_u32(&mut stub, shares.len() as u32); // total entries
        put_u32(&mut stub, 0); // resume handle: null
        put_u32(&mut stub, status);

        let mut body = Vec::new();
        body.extend((stub.len() as u32).to_le_bytes());
        body.extend([0, 0, 0, 0]); // context id, cancel count, reserved
        body.extend(stub);
        pdu(PTYPE_RESPONSE, 2, &body)
    }

    #[test]
    fn parses_shares_and_keeps_only_browsable_ones() {
        let reply = response(
            &[
                ("video", 0, "Movies and shows"),
                ("IPC$", 3 | STYPE_SPECIAL, "IPC Service"),
                ("C$", STYPE_SPECIAL, ""),
                ("사진", 0, ""),
            ],
            0,
        );
        let shares = parse_share_enum_response(&reply).unwrap();
        assert_eq!(shares.len(), 4);
        let browsable: Vec<&str> = shares
            .iter()
            .filter(|s| s.is_browsable())
            .map(|s| s.name.as_str())
            .collect();
        assert_eq!(browsable, ["video", "사진"]);
    }

    #[test]
    fn reports_a_failed_call() {
        assert!(parse_share_enum_response(&response(&[], 5)).is_err());
    }

    #[test]
    fn rejects_a_truncated_reply() {
        let reply = response(&[("video", 0, "")], 0);
        assert!(parse_share_enum_response(&reply[..reply.len() - 30]).is_err());
    }

    #[test]
    fn request_header_carries_its_length() {
        let request = share_enum_request("server");
        assert_eq!(&request[..4], &[5, 0, PTYPE_REQUEST, FIRST_AND_LAST_FRAGMENT]);
        assert_eq!(u16::from_le_bytes([request[8], request[9]]) as usize, request.len());
        assert_eq!(u16::from_le_bytes([request[22], request[23]]), OPNUM_NETR_SHARE_ENUM);
    }
}
