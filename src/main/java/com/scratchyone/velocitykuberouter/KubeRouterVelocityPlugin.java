package com.scratchyone.velocitykuberouter;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

@Plugin(id = "velocitykuberouter", name = "Velocity Kube Router", version = "0.1.0-SNAPSHOT", description = "Managing routing and autoscaling of Minecraft servers on K8S", authors = {
    "scratchyone" })
public class KubeRouterVelocityPlugin {

  private final ProxyServer server;
  private final Logger logger;

  private final HashMap<String, ServerPing> pingCache;
  private final LimboFactory factory;
  private Limbo limbo;

  public static final Config CONFIG = new Config();

  private final KubernetesClient k8s;

  private Path dataDirectory;

  private final Path configPath;

  public static final PlainTextComponentSerializer SERIALIZER = PlainTextComponentSerializer.builder()
      .flattener(ComponentFlattener.basic()).build();

  @Inject
  public KubeRouterVelocityPlugin(
      ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) throws Exception {
    k8s = new KubernetesClientBuilder().build();

    this.dataDirectory = dataDirectory;

    this.server = server;
    this.logger = logger;
    this.pingCache = new HashMap<>();
    this.factory = (LimboFactory) this.server
        .getPluginManager()
        .getPlugin("limboapi")
        .flatMap(PluginContainer::getInstance)
        .orElseThrow();

    this.configPath = dataDirectory.resolve("config.yml");
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    // Do some operation demanding access to the Velocity API here.
    // For instance, we could register an event:
    // server.getEventManager().register(this, new PluginListener());
    VirtualWorld world = this.factory.createVirtualWorld(Dimension.OVERWORLD, 0.0, 100.0, 0.0, 0.0F, 0.0F);
    this.limbo = this.factory.createLimbo(world);

    CONFIG.reload(this.configPath);

    refreshCachedPings();
  }

  private void refreshCachedPings() {
    ServiceList matchingServices = k8s.services().list();

    for (Service service : matchingServices.getItems()) {
      if (service.getMetadata().getAnnotations() != null
          && service.getMetadata().getAnnotations().get("mc-router.itzg.me/externalServerName") != null) {
        String virtualHost = service.getMetadata().getAnnotations().get("mc-router.itzg.me/externalServerName");
        ServerPing cachedPing = pingCache.get(virtualHost);
        Optional<RegisteredServer> registeredServer = getRegisteredServerFromVirtualHost(virtualHost);
        if (cachedPing == null && registeredServer.isPresent()) {
          // Hmm, we have a server registered with us but no ping cached for it. Let's
          // cache one
          logger.info(
              "{} is registered but doesn't have a cached ping, attempting to ping it to cache one"
                  + " now...",
              virtualHost);
          registeredServer
              .get()
              .ping(
                  PingOptions.builder().version(ProtocolVersion.MAXIMUM_VERSION).virtualHost(virtualHost).build())
              .thenAccept(
                  ping -> {
                    if (CONFIG.debug)
                      logger.info("Ping Result: {}", ping);
                    pingCache.put(virtualHost, ping);
                  })
              .exceptionally(
                  e -> {
                    if (CONFIG.debug)
                      logger.info("Ping to target server failed: {}", e.getMessage());
                    // The server didn't respond to our ping, lets try to start it up

                    Optional<StatefulSet> statefulSetOpt = getServiceFromVirtualHost(virtualHost)
                        .flatMap((v) -> getStatefulSetFromService(v));

                    if (statefulSetOpt.isPresent()) {
                      StatefulSet statefulSet = statefulSetOpt.get();

                      if (CONFIG.debug)
                        logger.info(
                            "Statefulset has {} replicas", statefulSet.getSpec().getReplicas());

                      if (statefulSet.getSpec().getReplicas() == 0) {
                        logger.info(
                            "Scaling up StatefulSet {}", statefulSet.getMetadata().getName());
                        k8s.resource(statefulSet).scale(1);
                      }
                    }
                    return null;
                  });
        }
      }
    }

    server
        .getScheduler()
        .buildTask(this, this::refreshCachedPings)
        .delay(30L, TimeUnit.SECONDS)
        .schedule();
  }

  private Optional<Service> getServiceFromVirtualHost(String virtualHost) {
    ServiceList matchingServices = k8s.services().list();

    for (Service service : matchingServices.getItems()) {
      if (service.getMetadata().getAnnotations() != null
          && virtualHost.equals(
              service.getMetadata().getAnnotations().get("mc-router.itzg.me/externalServerName"))) {
        if (CONFIG.debug)
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
                    RegisteredServer newServer = server.registerServer(new ServerInfo(targetServerName, targetAddress));
                    if (CONFIG.debug)
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
    String virtualHost = event.getPlayer().getVirtualHost().map((v) -> v.getHostString()).orElse("");

    if (CONFIG.debug)
      logger.info("Player connecting to server at {}", virtualHost);

    getRegisteredServerFromVirtualHost(virtualHost)
        .ifPresent(
            server -> {
              event.setResult(ServerPreConnectEvent.ServerResult.allowed(server));
            });
  }

  @Subscribe
  public void onProxyPing(ProxyPingEvent event) {
    if (CONFIG.debug)
      logger.info("Ping event: {}", event);

    Optional<InetSocketAddress> virtualHostOpt = event.getConnection().getVirtualHost();
    if (virtualHostOpt.isPresent()) {
      String virtualHost = virtualHostOpt.get().getHostString();

      if (CONFIG.debug)
        logger.info("Ping handler: Virtualhost is {}", virtualHost);

      Optional<RegisteredServer> registeredServerOpt = getRegisteredServerFromVirtualHost(virtualHost);
      if (registeredServerOpt.isPresent()) {
        RegisteredServer server = registeredServerOpt.get();

        if (CONFIG.debug)
          logger.info("Ping handler: Found target server!");

        CompletableFuture<Void> pingFuture = server
            .ping(
                PingOptions.builder().version(ProtocolVersion.MAXIMUM_VERSION).virtualHost(virtualHost).build())
            .thenAccept(
                ping -> {
                  if (CONFIG.debug)
                    logger.info("Ping Result: {}", ping);
                  event.setPing(ping);
                  pingCache.put(virtualHost, ping);
                })
            .exceptionally(
                e -> {
                  if (CONFIG.debug)
                    logger.info("Ping to target server failed: {}", e.getMessage());
                  ServerPing cachedPing = pingCache.get(virtualHost);
                  if (cachedPing != null) {
                    event.setPing(
                        cachedPing
                            .asBuilder()
                            .clearSamplePlayers()
                            .onlinePlayers(0)
                            .version(
                                new ServerPing.Version(
                                    event.getConnection().getProtocolVersion().getProtocol(),
                                    event
                                        .getConnection()
                                        .getProtocolVersion()
                                        .getMostRecentSupportedVersion()))
                            .build());
                  }
                  return null;
                });

        try {
          pingFuture.get(); // Wait for the ping future to complete
        } catch (InterruptedException | ExecutionException e) {
          if (CONFIG.debug)
            logger.error("Error waiting for ping result", e);
        }
      } else {
        if (CONFIG.debug)
          logger.info("Couldn't get server...");
      }
    } else {
      if (CONFIG.debug)
        logger.info("No virtual host present.");
    }
  }

  @Subscribe(order = PostOrder.LAST)
  public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
    logger.info("registering...");
    event.setOnKickCallback(
        kickEvent -> {
          Optional<String> virtualHost = event.getPlayer().getVirtualHost().map((v) -> v.getHostString());
          Component kickReason = kickEvent.getServerKickReason().isPresent()
              ? kickEvent.getServerKickReason().get()
              : Component.empty();
          String kickMessage = Objects.requireNonNullElse(SERIALIZER.serialize(kickReason), "unknown");

          if (CONFIG.debug)
            logger.info("Kick reason: {}", kickReason);
          if (CONFIG.debug)
            logger.info("Kick message: {}", kickMessage.isEmpty());

          if (kickMessage.isEmpty() && virtualHost.isPresent()) {
            if (CONFIG.debug)
              logger.info("VirtualHost is present.");

            Optional<StatefulSet> statefulSetOpt = getServiceFromVirtualHost(virtualHost.get())
                .flatMap((v) -> getStatefulSetFromService(v));

            if (statefulSetOpt.isPresent()) {
              StatefulSet statefulSet = statefulSetOpt.get();

              if (CONFIG.debug)
                logger.info("Statefulset has {} replicas", statefulSet.getSpec().getReplicas());

              if (statefulSet.getSpec().getReplicas() == 0) {
                logger.info("Scaling up StatefulSet {}", statefulSet.getMetadata().getName());
                k8s.resource(statefulSet).scale(1);
              }

              Optional<RegisteredServer> registeredServerOpt = getRegisteredServerFromVirtualHost(virtualHost.get());
              if (registeredServerOpt.isPresent()) {
                RegisteredServer server = registeredServerOpt.get();
                logger.info("Sending player to limbo...");
                this.limbo.spawnPlayer(
                    kickEvent.getPlayer(), new SessionHandler(logger, this, server));
              }
              // KickedFromServerEvent.Notify notify =
              // KickedFromServerEvent.Notify.create(
              // Component.text("Waiting for server to start..."));
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
