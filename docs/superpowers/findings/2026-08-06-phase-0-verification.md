ubuntu@instance-20260709-0504:~$ curl -s ifconfig.me; echo
163.192.218.124
ubuntu@instance-20260709-0504:~$ systemctl list-timers | grep -i duck
ubuntu@instance-20260709-0504:~$ crontab -l | grep -i duck
-bash: crontab: command not found

for p in 22 23 80 443 111 8080; do
kdns.org/$p" 2>/>   (timeout 3 bash -c "</dev/tcp/suinevere.duckdns.org/$p" 2>/dev/null \
>      && echo "$p open") || echo "$p closed"
> done
22 open
23 open
80 open
443 open

111 closed
8080 closed

ubuntu@instance-20260709-0504:~$ sudo iptables -S INPUT
-P INPUT ACCEPT

sudo ss -ltnp
State           Recv-Q          Send-Q                   Local Address:Port                   Peer Address:Port         Process
LISTEN          0               4096                         127.0.0.1:2323                        0.0.0.0:*             users:(("docker-proxy",pid=267130,fd=7))
LISTEN          0               4096                     127.0.0.53%lo:53                          0.0.0.0:*             users:(("systemd-resolve",pid=672,fd=13))
LISTEN          0               511                            0.0.0.0:23                          0.0.0.0:*             users:(("nginx",pid=269000,fd=13),("nginx",pid=268999,fd=13),("nginx",pid=136582,fd=13))
LISTEN          0               128                            0.0.0.0:22                          0.0.0.0:*             users:(("sshd",pid=957,fd=3))
LISTEN          0               511                            0.0.0.0:80                          0.0.0.0:*             users:(("nginx",pid=269000,fd=6),("nginx",pid=268999,fd=6),("nginx",pid=136582,fd=6))
LISTEN          0               511                            0.0.0.0:443                         0.0.0.0:*             users:(("nginx",pid=269000,fd=12),("nginx",pid=268999,fd=12),("nginx",pid=136582,fd=12))
LISTEN          0               128                               [::]:22                             [::]:*             users:(("sshd",pid=957,fd=4))
LISTEN          0               511                               [::]:80                             [::]:*             users:(("nginx",pid=269000,fd=7),("nginx",pid=268999,fd=7),("nginx",pid=136582,fd=7))
LISTEN          0               511                               [::]:443                            [::]:*             users:(("nginx",pid=269000,fd=9),("nginx",pid=268999,fd=9),("nginx",pid=136582,fd=9))

sudo ufw status verbose
Status: inactive
ubuntu@instance-20260709-0504:~$ curl -s ifconfig.me; echo
163.192.218.124
ubuntu@instance-20260709-0504:~$ systemctl list-timers | grep -i duck

sudo nginx -t
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
ubuntu@instance-20260709-0504:~$ sudo nginx -T | grep -B2 -A8 'listen 23'
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful

server {
listen 23;
limit_conn mud 3;
# proxy_pass 127.0.0.1:2322;    # authproxy — re-enable once AUTH_SECRET is set
    proxy_pass 127.0.0.1:2323;      # multizorkd direct
    proxy_timeout 10m;
    proxy_connect_timeout 5s;
}

ubuntu@instance-20260709-0504:~$ dpkg -l | grep libnginx-mod-stream
ii  libnginx-mod-stream                    1.18.0-0ubuntu1.7                 amd64        Stream module for Nginx


pi@dreampi:~ $ systemctl status dreampi
● dreampi.service - DreamPi Service
Loaded: loaded (/home/pi/dreampi/etc/systemd/system/dreampi.service; enabled; vendor preset: enabled)
Active: active (running) since Tue 2026-07-28 21:30:31 UTC; 1 weeks 2 days ago
Main PID: 496 (python)
Tasks: 3 (limit: 2059)
CGroup: /system.slice/dreampi.service
└─496 python /usr/local/bin/dreampi --no-daemon

Jul 28 21:30:47 dreampi dreampi[496]: INFO Command: AT+VTX
Jul 28 21:30:47 dreampi dreampi[496]: INFO Response: CONNECT
Jul 28 21:30:47 dreampi dreampi[496]: INFO <LISTENING>
Jul 28 21:30:47 dreampi dreampi[496]: INFO config is up to date (v202607091400)
Jul 28 21:30:47 dreampi dreampi[496]: INFO Server ID codes loaded: [u'199408', u'199407', u'199406', u'199405', u'199404
Jul 28 21:30:47 dreampi dreampi[496]: INFO Serial configuration read
Jul 28 21:30:47 dreampi dreampi[496]: INFO dcnet.rpi up to date
Jul 28 21:30:47 dreampi dreampi[496]: INFO DCNet configuration read
Jul 28 21:30:47 dreampi dreampi[496]: INFO DCNet available: True
Jul 28 21:30:47 dreampi dreampi[496]: INFO Add *69 to outside dial prefix to activate DCNet

pi@dreampi:~ $ grep -A5 '199408' /path/to/netlink_config.ini
grep: /path/to/netlink_config.ini: No such file or directory
pi@dreampi:~ $ grep -A5 '199408' ./dreampi/netlink_config.ini
[server:199408]
name = MultiZork
host = suinevere.duckdns.org
port = 23
handler = transparent

pi@dreampi:~ $

sudo certbot certificates
Saving debug log to /var/log/letsencrypt/letsencrypt.log
Cannot extract OCSP URI from /etc/letsencrypt/live/suinevere.duckdns.org/cert.pem

- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
Found the following certs:
Certificate Name: suinevere.duckdns.org
Domains: suinevere.duckdns.org
Expiry Date: 2026-10-19 18:04:11+00:00 (VALID: 73 days)
Certificate Path: /etc/letsencrypt/live/suinevere.duckdns.org/fullchain.pem
Private Key Path: /etc/letsencrypt/live/suinevere.duckdns.org/privkey.pem
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
ubuntu@instance-20260709-0504:~$ sudo nginx -T | grep -A6 'listen 80'
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
listen 80 default_server;
listen [::]:80 default_server;

        server_name suinevere.duckdns.org;

        # Keep ACME challenge local for Certbot verification
        location /.well-known/acme-challenge/ {