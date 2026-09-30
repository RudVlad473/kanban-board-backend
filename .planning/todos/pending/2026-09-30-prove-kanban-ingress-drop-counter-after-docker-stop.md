# Prove KANBAN-INGRESS DROP counter after Docker stop

Phase 13 closed with this unproven. Chain and PREROUTING position-1 jump are present and survive reboot, NodePorts 30080/30104 are unreachable off-box, but the Netcup Cloud Firewall drops the probe before the DROP counter can move.

Proof needs: temporary console rule for 45.134.212.94/32 on TCP 30104, then `nc -z -w 5 <ip> 30104` and `iptables -t mangle -L KANBAN-INGRESS -n -v -x` before/after. Remove the rule afterwards.
