FROM rqlite/rqlite:8.36.2
EXPOSE 4001 4002

CMD ["-node-id", "1", "-http-addr", "0.0.0.0:4001", "-raft-addr", "0.0.0.0:4002"]
