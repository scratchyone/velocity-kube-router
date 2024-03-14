package com.scratchyone.velocitykuberouter;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

@Plugin(
    id = "velocitykuberouter",
    name = "Velocity Kube Router",
    version = "0.1.0-SNAPSHOT",
    description = "Managing routing and autoscaling of Minecraft servers on K8S",
    authors = {"scratchyone"})
public class KubeRouterVelocityPlugin {

  private final ProxyServer server;
  private final Logger logger;

  private final HashMap<String, ServerPing> pingCache;
  private final LimboFactory factory;
  private Limbo limbo;

  private final KubernetesClient k8s;

  public static final PlainTextComponentSerializer SERIALIZER =
      PlainTextComponentSerializer.builder().flattener(ComponentFlattener.basic()).build();

  @Inject
  public KubeRouterVelocityPlugin(ProxyServer server, Logger logger) throws Exception {
    k8s = new KubernetesClientBuilder().build();

    this.server = server;
    this.logger = logger;
    this.pingCache = new HashMap<>();
    this.factory =
        (LimboFactory)
            this.server
                .getPluginManager()
                .getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow();
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    // Do some operation demanding access to the Velocity API here.
    // For instance, we could register an event:
    // server.getEventManager().register(this, new PluginListener());
    VirtualWorld world =
        this.factory.createVirtualWorld(Dimension.OVERWORLD, 0.0, 100.0, 0.0, 0.0F, 0.0F);
    this.limbo = this.factory.createLimbo(world);
  }

  private Optional<Service> getServiceFromVirtualHost(String virtualHost) {
    ServiceList matchingServices = k8s.services().list();

    for (Service service : matchingServices.getItems()) {
      if (service.getMetadata().getAnnotations() != null
          && virtualHost.equals(
              service.getMetadata().getAnnotations().get("mc-router.itzg.me/externalServerName"))) {
        logger.info("Matching service: {}", service);

        return Optional.ofNullable(service);
      }
    }
    return Optional.empty();
  }

  private Optional<StatefulSet> getStatefulSetFromService(Service service) {
    StatefulSetList matchingStatefulSets = k8s.apps().statefulSets().list();

    for (StatefulSet statefulSet : matchingStatefulSets.getItems()) {
      if (statefulSet.getMetadata().getName().equals(service.getMetadata().getName())) {
        return Optional.ofNullable(statefulSet);
      }
    }

    return Optional.empty();
  }

  private Optional<RegisteredServer> getRegisteredServerFromVirtualHost(String virtualHost) {

    Optional<Service> serviceOpt = getServiceFromVirtualHost(virtualHost);

    if (serviceOpt.isPresent()) {
      Service service = serviceOpt.get();

      String targetServerName = service.getMetadata().getName();
      InetSocketAddress targetAddress = new InetSocketAddress(targetServerName, 25565);

      return Optional.ofNullable(
          server
              .getServer(targetServerName)
              .orElseGet(
                  () -> {
                    // Register the server dynamically
                    RegisteredServer newServer =
                        server.registerServer(new ServerInfo(targetServerName, targetAddress));
                    logger.info(
                        "Registering new server {} at {}: {}",
                        targetServerName,
                        targetAddress,
                        newServer);
                    return newServer;
                  }));
    } else {
      return Optional.empty();
    }
  }

  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    String virtualHost =
        event.getPlayer().getVirtualHost().map((v) -> v.getHostString()).orElse("");
    logger.info("Connecting to server at {}", virtualHost);

    getRegisteredServerFromVirtualHost(virtualHost)
        .ifPresent(
            server -> {
              event.setResult(ServerPreConnectEvent.ServerResult.allowed(server));
            });
  }

  @Subscribe
  public void onProxyPing(ProxyPingEvent event) {
    logger.info("Ping event: {}", event);

    Optional<InetSocketAddress> virtualHostOpt = event.getConnection().getVirtualHost();
    if (virtualHostOpt.isPresent()) {
      String virtualHost = virtualHostOpt.get().getHostString();
      logger.info("Ping handler: Virtualhost is {}", virtualHost);

      Optional<RegisteredServer> registeredServerOpt =
          getRegisteredServerFromVirtualHost(virtualHost);
      if (registeredServerOpt.isPresent()) {
        RegisteredServer server = registeredServerOpt.get();
        logger.info("Ping handler: Found target server!");

        CompletableFuture<Void> pingFuture =
            server
                .ping(
                    PingOptions.builder()
                        .version(event.getConnection().getProtocolVersion())
                        .build(),
                    virtualHost)
                .thenAccept(
                    ping -> {
                      logger.info("Ping Result: {}", ping);
                      event.setPing(ping);
                      pingCache.put(virtualHost, ping);
                    })
                .exceptionally(
                    e -> {
                      logger.info("Ping to target server failed: {}", e.getMessage());
                      ServerPing cachedPing = pingCache.get(virtualHost);
                      if (cachedPing != null) {
                        event.setPing(
                            cachedPing.asBuilder().clearSamplePlayers().onlinePlayers(0).build());
                      }
                      return null;
                    });

        try {
          pingFuture.get(); // Wait for the ping future to complete
        } catch (InterruptedException | ExecutionException e) {
          logger.error("Error waiting for ping result", e);
        }
      } else {
        logger.info("Couldn't get server...");
      }
    } else {
      logger.info("No virtual host present.");
    }
  }

  @Subscribe(order = PostOrder.FIRST)
  public void onKick(KickedFromServerEvent event) {
    logger.info("kicked: {}", event.kickedDuringServerConnect());
    // Component kickReason =
    //     event.getServerKickReason().isPresent()
    //         ? event.getServerKickReason().get()
    //         : Component.empty();
    // String kickMessage = Objects.requireNonNullElse(SERIALIZER.serialize(kickReason), "unknown");
    //
    // logger.info("Kick reason: {}", kickReason);
    // logger.info("Kick message: {}", kickMessage.isEmpty());
    //
    // if (kickMessage.isEmpty()) {
    //   logger.info("Sending player to limbo...");
    //   this.limbo.spawnPlayer(event.getPlayer(), new SessionHandler(event.getServer()));
    //   KickedFromServerEvent.Notify notify =
    //       KickedFromServerEvent.Notify.create(Component.text("Waiting for server to start..."));
    //   event.setResult(notify);
    // }
  }

  @Subscribe(order = PostOrder.LAST)
  public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
    logger.info("registering...");
    event.setOnKickCallback(
        kickEvent -> {
          Optional<String> virtualHost =
              event.getPlayer().getVirtualHost().map((v) -> v.getHostString());
          Component kickReason =
              kickEvent.getServerKickReason().isPresent()
                  ? kickEvent.getServerKickReason().get()
                  : Component.empty();
          String kickMessage =
              Objects.requireNonNullElse(SERIALIZER.serialize(kickReason), "unknown");

          logger.info("Kick reason: {}", kickReason);
          logger.info("Kick message: {}", kickMessage.isEmpty());

          if (kickMessage.isEmpty() && virtualHost.isPresent()) {
            logger.info("VirtualHost is present.");
            Optional<StatefulSet> statefulSetOpt =
                getServiceFromVirtualHost(virtualHost.get())
                    .flatMap((v) -> getStatefulSetFromService(v));

            if (statefulSetOpt.isPresent()) {
              StatefulSet statefulSet = statefulSetOpt.get();

              logger.info("Statefulset has {} replicas", statefulSet.getSpec().getReplicas());

              if (statefulSet.getSpec().getReplicas() == 0) {
                logger.info("Scaling up StatefulSet");
                k8s.resource(statefulSet).scale(1);
              }

              Optional<RegisteredServer> registeredServerOpt =
                  getRegisteredServerFromVirtualHost(virtualHost.get());
              if (registeredServerOpt.isPresent()) {
                RegisteredServer server = registeredServerOpt.get();
                logger.info("Sending player to limbo...");
                this.limbo.spawnPlayer(
                    kickEvent.getPlayer(), new SessionHandler(logger, this, server));
              }
              // KickedFromServerEvent.Notify notify =
              //     KickedFromServerEvent.Notify.create(
              //         Component.text("Waiting for server to start..."));
              // kickEvent.setResult(notify);
              return true;
            }
          }

          // if (CONFIG.debug) {
          // LimboReconnect.getLogger().info("Component: {}", kickReason);
          // LimboReconnect.getLogger().info("Kick message: {}", kickMessage);
          // LimboReconnect.getLogger()
          // .info("Match: {}", kickMessage.matches(CONFIG.triggerMessage));
          // }
          //

          return false;
        });
  }
}
