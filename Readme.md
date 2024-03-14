# Velocity Kube Router
This plugin is a drop-in replacement for `mc-router`'s k8s support. It routes connections and pings to k8s services automatically. It handles automatically scaling up a StatefulSet from `0` to `1` replicas when an initial connection is received, and places players in a limbo server to be forwarded once the server starts; this allows players to connect to a stopped server with no extra effort. It even caches ping results so that the MOTD will display as if the server is online even when it is scaled down, allowing seamless scale-to-0 for minecraft servers.

However, this plugin does not support scaling StatefulSets back down to `0` after inactivity, which is left as an exercise to the reader.
