## Local IP Network

| Gateway                   | 192.168.1.1   |
|---------------------------|---------------|
| Fedora Core OS Homeserver | 192.168.1.100 |
| Synology NAS              | 192.168.1.198 |
|                           |               |

## NAS NFS Mount

| Mountpoint (FCOS)         | /mnt/nas                      |
|---------------------------|-------------------------------|
| Config file               | services/nas.fstab            |
| Export examples           | 192.168.1.198:/volume1/share1, /share2, /share3 |

## Fedora Core OS Ports

| Home Assistant            | 8123    |
|                           |         |
