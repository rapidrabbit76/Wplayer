mkdir -p /var/log/proftpd /run/samba
smbd -D
proftpd -n &
sleep infinity
