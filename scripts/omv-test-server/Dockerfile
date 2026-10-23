# An openmediavault-like file server for scripts/test-servers.sh: ProFTPD with
# mod_vroot and Samba, configured as openmediavault 7 generates them.
FROM debian:bookworm
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends proftpd-core proftpd-mod-vroot samba procps && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash tester && echo 'tester:secret' | chpasswd && (echo secret; echo secret) | smbpasswd -s -a tester
RUN mkdir -p /srv/ftp /srv/dev-disk-by-uuid-1234/Videos
COPY proftpd.conf /etc/proftpd/proftpd.conf
COPY smb.conf /etc/samba/smb.conf
COPY start.sh /start.sh
CMD ["sh", "/start.sh"]
