package com.donutsmp.rtpmapper;

import com.donutsmp.rtpmapper.automation.PositionObservation;
import com.donutsmp.rtpmapper.automation.AutomationCoordinator;
import com.donutsmp.rtpmapper.automation.AutomationMode;
import com.donutsmp.rtpmapper.automation.CoordinateStopGuard;
import com.donutsmp.rtpmapper.automation.RtpAttemptSettings;
import com.donutsmp.rtpmapper.automation.RtpClock;
import com.donutsmp.rtpmapper.automation.RtpController;
import com.donutsmp.rtpmapper.automation.RtpEnvironmentSnapshot;
import com.donutsmp.rtpmapper.automation.RtpSampleResult;
import com.donutsmp.rtpmapper.automation.RtpStartResult;
import com.donutsmp.rtpmapper.automation.RtpStopReason;
import com.donutsmp.rtpmapper.config.ConfigManager;
import com.donutsmp.rtpmapper.config.RtpMapperConfig;
import com.donutsmp.rtpmapper.data.DataStorage;
import com.donutsmp.rtpmapper.data.RtpDataset;
import com.donutsmp.rtpmapper.data.RtpDatasetSnapshot;
import com.donutsmp.rtpmapper.data.RtpSample;
import com.donutsmp.rtpmapper.data.SampleScope;
import com.donutsmp.rtpmapper.gui.ChartPointProvider;
import com.donutsmp.rtpmapper.gui.DataScope;
import com.donutsmp.rtpmapper.gui.MapperSettingsView;
import com.donutsmp.rtpmapper.gui.MapperStatisticsView;
import com.donutsmp.rtpmapper.gui.MapperStatusView;
import com.donutsmp.rtpmapper.gui.MiningSettingsView;
import com.donutsmp.rtpmapper.gui.MiningStatusView;
import com.donutsmp.rtpmapper.gui.RtpMapperUiModel;
import com.donutsmp.rtpmapper.gui.UiActionResult;
import com.donutsmp.rtpmapper.mining.BaritoneMiningBackend;
import com.donutsmp.rtpmapper.mining.MiningController;
import com.donutsmp.rtpmapper.mining.MiningEnvironment;
import com.donutsmp.rtpmapper.mining.MiningServerPolicy;
import com.donutsmp.rtpmapper.mining.MiningSettings;
import com.donutsmp.rtpmapper.mining.MiningStartResult;
import com.donutsmp.rtpmapper.mining.MiningStopReason;
import com.donutsmp.rtpmapper.region.RtpRegion;
import com.donutsmp.rtpmapper.region.RtpRegionCycle;
import com.donutsmp.rtpmapper.util.Quadrant;
import com.donutsmp.rtpmapper.util.QuadrantStatistics;
import com.donutsmp.rtpmapper.util.RadialBucket;
import com.donutsmp.rtpmapper.util.RtpStatistics;
import com.donutsmp.rtpmapper.util.StatisticsCalculator;
import com.donutsmp.rtpmapper.automation.InventoryHelper;
import com.donutsmp.rtpmapper.data.BaseDiscovery;
import com.donutsmp.rtpmapper.data.BaseLogManager;
import com.donutsmp.rtpmapper.gui.HunterSettingsView;
import com.donutsmp.rtpmapper.util.WebhookNotifier;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Client-thread composition root. Minecraft access stays on the client thread;
 * background persistence/export workers receive immutable snapshots only.
 */
public final class RtpMapperRuntime implements RtpMapperUiModel, AutoCloseable {
    private static final int TICKS_PER_SECOND = 20;
    private static final int REQUIRED_STABLE_TICKS = 5;
    private static final double STABILITY_TOLERANCE_BLOCKS = 0.35;
    private static final long SAVE_RETRY_DELAY_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long MAX_MIRROR_RETRY_DELAY_NANOS = Duration.ofMinutes(5).toNanos();
    private static final long IO_FLUSH_TIMEOUT_SECONDS = 10;
    private static final long IO_TERMINATION_TIMEOUT_SECONDS = 2;

    private final Minecraft client;
    private final Logger logger;
    private final ConfigManager configManager;
    private final DataStorage storage;
    private final RtpDataset dataset;
    private final StatisticsCalculator statisticsCalculator = new StatisticsCalculator();
    private final RtpClock clock = RtpClock.system();
    private final ExecutorService ioExecutor;
    private final ExecutorService exportExecutor;
    private final AtomicReference<RtpDatasetSnapshot> pendingSave = new AtomicReference<>();
    private final AtomicReference<RtpDatasetSnapshot> pendingMirrorRepair = new AtomicReference<>();
    private final AtomicBoolean saveWorkerScheduled = new AtomicBoolean();
    private final AtomicBoolean exportInProgress = new AtomicBoolean();
    private final DatasetPointProvider allTimePoints = new DatasetPointProvider(SampleScope.ALL_TIME);
    private final DatasetPointProvider sessionPoints = new DatasetPointProvider(SampleScope.SESSION);
    private final RtpRegionCycle regionCycle;
    private final RtpController controller;
    private final AutomationCoordinator automationCoordinator = new AutomationCoordinator();
    private final BaritoneMiningBackend miningBackend;
    private final MiningController miningController;

    private volatile boolean storageHealthy = true;
    private volatile boolean mirrorRepairPending;
    private volatile long nextStorageRetryAtNanos;
    private volatile long nextMirrorRetryAtNanos;
    private volatile int mirrorRetryFailures;
    private boolean datasetLoadSafe = true;
    private volatile String ioMessage = "";
    private volatile boolean closed;
    private RtpMapperConfig config;
    private MapperSettingsView cachedSettingsView;
    private MiningSettingsView cachedMiningSettingsView;
    private long cachedAllTimeStatisticsRevision = Long.MIN_VALUE;
    private long cachedSessionStatisticsRevision = Long.MIN_VALUE;
    private MapperStatisticsView cachedAllTimeStatistics = MapperStatisticsView.empty();
    private MapperStatisticsView cachedSessionStatistics = MapperStatisticsView.empty();
    private long accumulatedSessionDurationNanos;
    private long sessionActiveSinceNanos;
    private boolean sessionClockRunning;
    private long failureBaseline;
    private Object mappingConnectionIdentity;
    private AutomationCoordinator.Lease rtpLease;
    private Object lastStoppedConnectionIdentity;
    private Object joinedConnectionIdentity;
    private boolean resumeAfterReconnect;
    private boolean autoResumeScheduled;
    private Object autoResumeConnectionIdentity;
    private long autoResumeNotBeforeNanos;
    private long lastLoggedFailedAttempts;
    private MiningStopReason lastReportedMiningStopReason = MiningStopReason.NONE;
    private String miningMessage = "";
    private boolean baritoneStatusFailureLogged;
    private boolean huntModeActive;
    private final BaseLogManager baseLogManager = new BaseLogManager();
    private long lastBaseScanTick;
    private double lastWatchdogX, lastWatchdogY, lastWatchdogZ;
    private long lastWatchdogMovedTick;
    private final List<BlockPos> nearbyTargetBlockPositions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static RtpMapperRuntime create(Minecraft client, Logger logger) {
        ConfigManager configManager = new ConfigManager();
        RtpMapperConfig config;
        try {
            config = configManager.load();
        } catch (IOException exception) {
            logger.error("[RTP Mapper] Unable to load config; using validated defaults for this run", exception);
            config = RtpMapperConfig.defaults();
        }

        DataStorage storage = new DataStorage();
        RtpDataset dataset;
        boolean storageHealthy = true;
        boolean mirrorRepairNeeded = false;
        String initialMessage = "";
        try {
            dataset = storage.loadDataset();
            logger.info("[RTP Mapper] Loaded {} all-time samples", dataset.size());
            IOException mirrorFailure = storage.takeMirrorFailure();
            if (mirrorFailure != null) {
                mirrorRepairNeeded = true;
                logger.warn("[RTP Mapper] CSV mirror repair failed; canonical JSON remains usable", mirrorFailure);
            }
        } catch (IOException exception) {
            // Fail closed: never overwrite an unreadable dataset with an empty one.
            logger.error("[RTP Mapper] Unable to load dataset; mapping is disabled to protect existing data", exception);
            dataset = new RtpDataset();
            storageHealthy = false;
            initialMessage = "Dataset could not be loaded; see the log. Mapping is disabled to protect it.";
        }

        RtpMapperRuntime runtime = new RtpMapperRuntime(client, logger, configManager, storage, dataset, config);
        runtime.storageHealthy = storageHealthy;
        runtime.datasetLoadSafe = storageHealthy;
        runtime.ioMessage = initialMessage;
        if (mirrorRepairNeeded) {
            runtime.queueMirrorRepair(dataset.snapshot());
            runtime.ioMessage = "Loaded canonical samples; CSV mirror repair is pending.";
        }
        return runtime;
    }

    RtpMapperRuntime(
        Minecraft client,
        Logger logger,
        ConfigManager configManager,
        DataStorage storage,
        RtpDataset dataset,
        RtpMapperConfig config
    ) {
        this.client = client;
        this.logger = logger;
        this.configManager = configManager;
        this.storage = storage;
        this.dataset = dataset;
        this.config = config;
        this.regionCycle = new RtpRegionCycle(latestKnownRequestedRegion(dataset));
        this.cachedSettingsView = toSettingsView(config);
        this.cachedMiningSettingsView = toMiningSettingsView(config);
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RTP Mapper IO");
            thread.setDaemon(true);
            return thread;
        });
        this.exportExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RTP Mapper CSV Export");
            thread.setDaemon(true);
            return thread;
        });
        this.controller = new RtpController(
            clock,
            this::attemptSettings,
            this::sendRtpCommand,
            this::recordSample
        );
        this.miningBackend = new BaritoneMiningBackend(client);
        this.miningController = new MiningController(
            miningBackend,
            clock::nanoTime,
            automationCoordinator
        );
        refreshPointProviders();
    }

    /** Called exactly once per END_CLIENT_TICK. */
    public void tick() {
        if (closed) {
            return;
        }

        boolean miningWasRunning = miningController.isRunning();
        miningController.tick(currentMiningEnvironment());
        if (miningWasRunning && !miningController.isRunning()) {
            reportMiningStopped();
        }

        if (!storageHealthy && controller.isRunning()) {
            controller.stop();
            pauseSessionClock();
            lastStoppedConnectionIdentity = mappingConnectionIdentity;
            mappingConnectionIdentity = null;
            releaseRtpLease();
            cancelAutoResume();
            logger.error("[RTP Mapper] Mapper stopped because persistent storage is unavailable");
        }
        if (datasetLoadSafe && !saveWorkerScheduled.get()) {
            long now = clock.nanoTime();
            if (pendingSave.get() != null
                && (storageHealthy || deadlineReached(now, nextStorageRetryAtNanos))) {
                scheduleSaveWorker();
            } else if (storageHealthy
                && mirrorRepairPending
                && pendingMirrorRepair.get() != null
                && deadlineReached(now, nextMirrorRetryAtNanos)) {
                scheduleSaveWorker();
            }
        }

        RtpEnvironmentSnapshot environment = currentEnvironment();
        if (controller.isRunning() && baritoneMineProcessActive()) {
            Object stoppedConnectionIdentity = mappingConnectionIdentity;
            controller.stop();
            handleMappingStopped(stoppedConnectionIdentity, false, environment);
            ioMessage = "RTP mapping stopped because a Baritone mining process became active.";
        }
        if (resumeAfterReconnect) {
            tickAutoResume(environment);
        }

        boolean wasRunning = controller.isRunning();
        Object stoppedConnectionIdentity = mappingConnectionIdentity;
        long failuresBefore = controller.failedRtpAttempts();
        long deliveredBefore = controller.deliveredSamples();
        RtpAttemptSettings completedAttemptSettings = controller.pendingRequest()
            .map(request -> request.settings())
            .orElse(null);
        var stateBefore = controller.state();
        controller.tick(environment);
        RtpStopReason guardStopReason = RtpStopReason.NONE;
        if (completedAttemptSettings != null) {
            guardStopReason = CoordinateStopGuard.stopAfterNewDelivery(
                controller,
                deliveredBefore,
                completedAttemptSettings
            );
        }
        if (wasRunning && !controller.isRunning()) {
            boolean reconnectStop = controller.lastStopReason() == RtpStopReason.DISCONNECTED
                || controller.lastStopReason() == RtpStopReason.CONNECTION_CHANGED;
            handleMappingStopped(stoppedConnectionIdentity, reconnectStop, environment);
            if (guardStopReason != RtpStopReason.NONE) {
                ioMessage = guardStopMessage(guardStopReason);
                if (guardStopReason == RtpStopReason.CENTER_GUARD_REACHED) {
                    if (config.soundAlerts() && client.getSoundManager() != null) {
                        try {
                            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F));
                        } catch (Exception ignored) {}
                    }
                    if (client.player != null && !config.discordWebhookUrl().isBlank()) {
                        WebhookNotifier.sendCenterLandingAlert(
                                config.discordWebhookUrl(),
                                client.player.getX(),
                                client.player.getY(),
                                client.player.getZ(),
                                Math.hypot(client.player.getX(), client.player.getZ())
                        );
                    }
                    if (huntModeActive) {
                        UiActionResult miningResult = startMining();
                        if (miningResult.success()) {
                            ioMessage = "Center guard reached! Automatically started Baritone Base Hunt.";
                            if (client.player != null) {
                                client.player.displayClientMessage(
                                    Component.literal("[RTP Mapper] Landed inside 50k radius! Automatically started Baritone Base Hunt.")
                                        .withStyle(ChatFormatting.GREEN),
                                    false
                                );
                            }
                        } else {
                            if (client.player != null) {
                                client.player.displayClientMessage(
                                    Component.literal("[RTP Mapper] Landed inside 50k radius! RTP stopped. Base hunt notice: " + miningResult.message())
                                        .withStyle(ChatFormatting.YELLOW),
                                    false
                                );
                            }
                        }
                    } else {
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                Component.literal("[RTP Mapper] Landed inside center guard (50k). RTP Data Mapping completed.")
                                    .withStyle(ChatFormatting.AQUA),
                                false
                            );
                        }
                    }
                }
            }
        }
        if (client.player != null && client.level != null) {
            InventoryHelper.tick(
                    client,
                    config.autoTrash(),
                    config.autoEat(),
                    config.autoTotem(),
                    config.autoLoot(),
                    config.autoEmergencyEscape()
            );

            long currentTick = client.level.getGameTime();
            if (currentTick - lastBaseScanTick >= 20) {
                lastBaseScanTick = currentTick;
                scanNearbyBaseBlocks();
            }

            // Anti-Stuck Watchdog
            if (config.antiStuckWatchdog() && (huntModeActive || miningController.isRunning())) {
                double dist = Math.hypot(client.player.getX() - lastWatchdogX, client.player.getZ() - lastWatchdogZ);
                if (dist > 2.0) {
                    lastWatchdogMovedTick = currentTick;
                    lastWatchdogX = client.player.getX();
                    lastWatchdogY = client.player.getY();
                    lastWatchdogZ = client.player.getZ();
                } else if (lastWatchdogMovedTick > 0 && currentTick - lastWatchdogMovedTick >= config.antiStuckTimeoutSeconds() * 20L) {
                    lastWatchdogMovedTick = currentTick;
                    lastWatchdogX = client.player.getX();
                    lastWatchdogY = client.player.getY();
                    lastWatchdogZ = client.player.getZ();
                    miningController.stop(MiningStopReason.USER_REQUEST);
                    client.player.displayClientMessage(
                            Component.literal("[RTP Mapper] ⚠️ Anti-Stuck Watchdog: No movement for "
                                    + config.antiStuckTimeoutSeconds() + "s. Teleporting to new hunting location...")
                                    .withStyle(ChatFormatting.RED),
                            false
                    );
                    if (config.soundAlerts() && client.getSoundManager() != null) {
                        try {
                            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F));
                        } catch (Exception ignored) {}
                    }
                    startHunt();
                }
            } else {
                lastWatchdogMovedTick = currentTick;
                lastWatchdogX = client.player.getX();
                lastWatchdogY = client.player.getY();
                lastWatchdogZ = client.player.getZ();
            }
        }
        if (stateBefore != controller.state()) {
            logTransition(controller.state(), environment.position());
        }
        if (controller.failedRtpAttempts() != failuresBefore
            || controller.failedRtpAttempts() != lastLoggedFailedAttempts) {
            lastLoggedFailedAttempts = controller.failedRtpAttempts();
            logger.warn("[RTP Mapper] RTP request failed: {}", controller.lastFailureReason());
        }
    }

    public void onDisconnected(Object disconnectedConnectionIdentity) {
        if (disconnectedConnectionIdentity == null) {
            return;
        }
        if (miningController.onDisconnected(disconnectedConnectionIdentity)) {
            reportMiningStopped();
        }
        if (mappingConnectionIdentity != null) {
            if (disconnectedConnectionIdentity != mappingConnectionIdentity) {
                logger.debug("[RTP Mapper] Ignoring a stale disconnect callback");
                return;
            }
            boolean wasRunning = controller.isRunning();
            controller.onDisconnected();
            if (wasRunning) {
                handleMappingStopped(disconnectedConnectionIdentity, true, currentEnvironment());
            }
            return;
        }
        if (disconnectedConnectionIdentity == lastStoppedConnectionIdentity) {
            // Polling already performed the complete stop transition.
            return;
        }
        if (disconnectedConnectionIdentity != joinedConnectionIdentity) {
            logger.debug("[RTP Mapper] Ignoring a stale disconnect callback");
            return;
        }
        joinedConnectionIdentity = null;
        autoResumeScheduled = false;
        autoResumeConnectionIdentity = null;
    }

    public void onJoined(Object joinedConnectionIdentity) {
        if (joinedConnectionIdentity != client.getConnection()) {
            logger.debug("[RTP Mapper] Ignoring a stale join callback");
            return;
        }
        this.joinedConnectionIdentity = joinedConnectionIdentity;
        if (resumeAfterReconnect) {
            scheduleAutoResume(joinedConnectionIdentity);
        }
    }

    private void tickAutoResume(RtpEnvironmentSnapshot environment) {
        if (!config.autoResume()) {
            cancelAutoResume();
            return;
        }
        if (automationCoordinator.mode() == AutomationMode.BARITONE_MINING) {
            cancelAutoResume();
            ioMessage = "Auto-resume cancelled while Baritone mining is active.";
            return;
        }
        if (baritoneMineProcessActive()) {
            cancelAutoResume();
            ioMessage = "Auto-resume cancelled because a Baritone mining process is active.";
            return;
        }
        if (!autoResumeScheduled || !environment.connected()) {
            return;
        }
        if (!environment.serverAllowed()) {
            cancelAutoResume();
            ioMessage = "Auto-resume cancelled: this server is not allowed.";
            return;
        }
        if (environment.connectionIdentity() != autoResumeConnectionIdentity) {
            // A rapid B -> C reconnect must never inherit B's elapsed delay.
            // Polling may observe C before its JOIN callback is delivered, so
            // bind a fresh full interval to the currently active handler.
            joinedConnectionIdentity = environment.connectionIdentity();
            scheduleAutoResume(environment.connectionIdentity());
            return;
        }
        if (!environment.isReadyForMapping() || clock.nanoTime() - autoResumeNotBeforeNanos < 0) {
            return;
        }
        resumeAfterReconnect = false;
        autoResumeScheduled = false;
        autoResumeConnectionIdentity = null;
        Optional<AutomationCoordinator.Lease> acquired = automationCoordinator.tryAcquire(
            AutomationMode.RTP_MAPPING
        );
        if (acquired.isEmpty()) {
            ioMessage = "Auto-resume cancelled because another automation task is active.";
            return;
        }
        RtpStartResult result = controller.start(environment);
        if (result == RtpStartResult.STARTED) {
            rtpLease = acquired.orElseThrow();
            mappingConnectionIdentity = environment.connectionIdentity();
            lastStoppedConnectionIdentity = null;
            joinedConnectionIdentity = environment.connectionIdentity();
            resumeSessionClock();
            logger.info("[RTP Mapper] Mapper auto-resumed after reconnect");
            ioMessage = "Mapper auto-resumed.";
        } else {
            automationCoordinator.release(acquired.orElseThrow());
        }
    }

    private void handleMappingStopped(
        Object stoppedConnectionIdentity,
        boolean reconnectStop,
        RtpEnvironmentSnapshot environment
    ) {
        pauseSessionClock();
        mappingConnectionIdentity = null;
        releaseRtpLease();
        lastStoppedConnectionIdentity = stoppedConnectionIdentity;
        resumeAfterReconnect = reconnectStop && config.autoResume() && storageHealthy;
        autoResumeScheduled = false;
        autoResumeConnectionIdentity = null;

        if (environment.connected() && environment.connectionIdentity() != null) {
            joinedConnectionIdentity = environment.connectionIdentity();
            if (resumeAfterReconnect) {
                if (environment.connectionIdentity() == stoppedConnectionIdentity) {
                    // The disconnect callback can run before Minecraft clears
                    // its handler. Wait for a distinct, matching JOIN event.
                    autoResumeScheduled = false;
                } else if (environment.serverAllowed()) {
                    scheduleAutoResume(environment.connectionIdentity());
                } else {
                    cancelAutoResume();
                    ioMessage = "Auto-resume cancelled: this server is not allowed.";
                }
            }
        } else if (joinedConnectionIdentity == stoppedConnectionIdentity) {
            joinedConnectionIdentity = null;
        }

        requestSave();
        logger.info("[RTP Mapper] Mapper stopped: {}", controller.lastStopReason());
    }

    private void scheduleAutoResume(Object connectionIdentity) {
        if (!resumeAfterReconnect || !config.autoResume() || !storageHealthy) {
            cancelAutoResume();
            return;
        }
        if (connectionIdentity == null) {
            autoResumeScheduled = false;
            autoResumeConnectionIdentity = null;
            return;
        }
        autoResumeConnectionIdentity = connectionIdentity;
        autoResumeNotBeforeNanos = clock.nanoTime() + secondsToNanos(config.rtpIntervalSeconds());
        autoResumeScheduled = true;
        ioMessage = "Auto-resume armed; waiting before the next RTP.";
    }

    private void cancelAutoResume() {
        resumeAfterReconnect = false;
        autoResumeScheduled = false;
        autoResumeConnectionIdentity = null;
        autoResumeNotBeforeNanos = 0;
    }

    private RtpAttemptSettings attemptSettings() {
        RtpMapperConfig snapshot = config;
        int minimumTicks = Math.max(1, (int)Math.ceil(snapshot.stabilizationSeconds() * TICKS_PER_SECOND));
        long stabilizationNanos = secondsToNanos(snapshot.stabilizationSeconds());
        return new RtpAttemptSettings(
            secondsToNanos(snapshot.rtpIntervalSeconds()),
            snapshot.teleportDetectionThresholdBlocks(),
            secondsToNanos(snapshot.teleportTimeoutSeconds()),
            minimumTicks,
            Math.min(REQUIRED_STABLE_TICKS, minimumTicks),
            Math.max(Duration.ofSeconds(5).toNanos(), stabilizationNanos * 4L),
            STABILITY_TOLERANCE_BLOCKS,
            snapshot.storeYCoordinate(),
            regionCycle.next(snapshot.selectedRegions()),
            snapshot.stopNearCenter(),
            snapshot.centerStopRadiusBlocks(),
            snapshot.stopNearWorldBorder(),
            snapshot.worldBorderMarginBlocks()
        );
    }

    private void sendRtpCommand(long requestNumber, RtpRegion requestedRegion) {
        ClientPacketListener handler = client.getConnection();
        if (handler == null) {
            throw new IllegalStateException("No active play connection");
        }
        logger.info(
            "[RTP Mapper] Sending RTP request #{} to requested region {}",
            requestNumber,
            requestedRegion.displayName()
        );
        handler.sendCommand("rtp " + requestedRegion.commandArgument());
        logger.info("[RTP Mapper] Waiting for teleport...");
    }

    private void recordSample(RtpSampleResult result) {
        double y = result.storeYCoordinate() ? result.y() : RtpSample.MISSING_Y;
        RtpSample sample = dataset.addSample(
            result.x(),
            y,
            result.z(),
            result.dimension(),
            result.timestampMillis(),
            result.requestedRegion()
        );
        refreshPointProviders();
        requestSave();
        logger.info(
            "[RTP Mapper] Recorded sample #{}: X={} Y={} Z={} requestedRegion={}",
            sample.sampleNumber(),
            sample.x(),
            sample.hasY() ? sample.y() : "not stored",
            sample.z(),
            sample.requestedRegion().displayName()
        );
        logger.info("[RTP Mapper] Post-record cooldown started");
    }

    private RtpEnvironmentSnapshot currentEnvironment() {
        ClientPacketListener handler = client.getConnection();
        if (handler == null || client.player == null || client.level == null || client.isLocalServer()) {
            return RtpEnvironmentSnapshot.disconnected();
        }
        ServerData server = handler.getServerData();
        boolean allowed = server != null && !server.isLan() && config.isServerAllowed(server.ip);
        PositionObservation position;
        try {
            position = new PositionObservation(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                client.level.dimension().identifier().toString()
            );
        } catch (IllegalArgumentException exception) {
            return new RtpEnvironmentSnapshot(true, allowed, handler, null);
        }
        return new RtpEnvironmentSnapshot(true, allowed, handler, position);
    }

    private MiningEnvironment currentMiningEnvironment() {
        ClientPacketListener handler = client.getConnection();
        if (handler == null || client.player == null || client.level == null) {
            return MiningEnvironment.unavailable();
        }
        if (client.isLocalServer()) {
            return MiningEnvironment.ready(
                config.allowSingleplayerMining(),
                handler,
                "Single-player world"
            );
        }
        ServerData server = handler.getServerData();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return MiningEnvironment.ready(false, handler, "Unknown multiplayer server");
        }
        return MiningEnvironment.ready(
            MiningServerPolicy.isRemoteServerAllowed(server.ip, config.miningAllowedServers()),
            handler,
            server.ip
        );
    }

    @Override
    public UiActionResult startMapping() {
        huntModeActive = false;
        return internalStartMapping("Data mapping started.");
    }

    @Override
    public UiActionResult startHunt() {
        huntModeActive = true;
        return internalStartMapping("Base hunting started: RTPing until landing within 50k radius...");
    }

    private UiActionResult internalStartMapping(String startMessage) {
        if (!storageHealthy) {
            return UiActionResult.error(ioMessage.isEmpty() ? "Persistent storage is unavailable." : ioMessage);
        }
        if (controller.isRunning()) {
            return UiActionResult.ok("Mapper is already running.");
        }
        if (baritoneMineProcessActive()) {
            return UiActionResult.error(
                "Stop the active Baritone mining process before starting RTP mapping."
            );
        }
        cancelAutoResume();
        RtpEnvironmentSnapshot environment = currentEnvironment();
        Optional<AutomationCoordinator.Lease> acquired = automationCoordinator.tryAcquire(
            AutomationMode.RTP_MAPPING
        );
        if (acquired.isEmpty()) {
            return UiActionResult.error("Stop Baritone mining before starting RTP mapping.");
        }
        RtpStartResult result = controller.start(environment);
        if (result != RtpStartResult.STARTED) {
            automationCoordinator.release(acquired.orElseThrow());
        }
        return switch (result) {
            case STARTED -> {
                rtpLease = acquired.orElseThrow();
                dataset.startNewSession();
                refreshPointProviders();
                statisticsCalculator.invalidate();
                accumulatedSessionDurationNanos = 0;
                sessionActiveSinceNanos = clock.nanoTime();
                sessionClockRunning = true;
                failureBaseline = controller.failedRtpAttempts();
                mappingConnectionIdentity = environment.connectionIdentity();
                lastStoppedConnectionIdentity = null;
                joinedConnectionIdentity = environment.connectionIdentity();
                ioMessage = startMessage;
                logger.info("[RTP Mapper] {}", startMessage);
                yield UiActionResult.ok(ioMessage);
            }
            case ALREADY_RUNNING -> UiActionResult.ok("Mapper is already running.");
            case NOT_CONNECTED -> UiActionResult.error("Join a multiplayer server before starting the mapper.");
            case SERVER_NOT_ALLOWED -> UiActionResult.error("RTP Mapper is disabled on this server.");
            case POSITION_UNAVAILABLE -> UiActionResult.error("Player position is not available yet.");
        };
    }

    @Override
    public UiActionResult stopMapping() {
        cancelAutoResume();
        if (!controller.isRunning()) {
            releaseRtpLease();
            return UiActionResult.ok("Mapper is already stopped.");
        }
        controller.stop();
        pauseSessionClock();
        mappingConnectionIdentity = null;
        releaseRtpLease();
        requestSave();
        ioMessage = "Mapper stopped; data save queued.";
        logger.info("[RTP Mapper] Mapper stopped");
        return UiActionResult.ok(ioMessage);
    }

    @Override
    public UiActionResult clearAllData() {
        if (!storageHealthy) {
            return UiActionResult.error("Data cannot be cleared while persistent storage is unavailable.");
        }
        cancelAutoResume();
        controller.stop();
        pauseSessionClock();
        mappingConnectionIdentity = null;
        releaseRtpLease();
        dataset.clearAll();
        statisticsCalculator.invalidate();
        refreshPointProviders();
        accumulatedSessionDurationNanos = 0;
        sessionActiveSinceNanos = 0;
        sessionClockRunning = false;
        failureBaseline = controller.failedRtpAttempts();

        RtpDatasetSnapshot emptySnapshot = dataset.snapshot();
        pendingSave.set(null);
        clearMirrorRepair();
        UiActionResult persistenceResult = persistClearBarrier(emptySnapshot);
        if (!persistenceResult.success()) {
            return persistenceResult;
        }
        ioMessage = "All RTP samples cleared and saved.";
        logger.info("[RTP Mapper] All sample data cleared");
        return UiActionResult.ok(ioMessage);
    }

    @Override
    public UiActionResult exportCsv() {
        if (closed) {
            return UiActionResult.error("Mapper is shutting down.");
        }
        if (!exportInProgress.compareAndSet(false, true)) {
            return UiActionResult.error("A CSV export is already queued or running.");
        }
        RtpDatasetSnapshot snapshot = dataset.snapshot();
        if (snapshot.allTimeSamples().isEmpty()) {
            exportInProgress.set(false);
            return UiActionResult.error("There are no all-time samples to export.");
        }
        ioMessage = "CSV export queued.";
        try {
            exportExecutor.execute(() -> {
                try {
                    Path path = storage.export(snapshot, SampleScope.ALL_TIME);
                    ioMessage = "Exported " + snapshot.totalCount() + " samples to " + path.getFileName();
                    logger.info("[RTP Mapper] {}", ioMessage);
                } catch (IOException exception) {
                    ioMessage = "CSV export failed; see the log.";
                    logger.error("[RTP Mapper] Unable to export CSV", exception);
                } finally {
                    exportInProgress.set(false);
                }
            });
            return UiActionResult.ok(ioMessage);
        } catch (RejectedExecutionException exception) {
            exportInProgress.set(false);
            ioMessage = "Mapper IO worker is shutting down.";
            return UiActionResult.error("Mapper IO worker is shutting down.");
        }
    }

    @Override
    public UiActionResult applySettings(MapperSettingsView settings) {
        try {
            RtpMapperConfig updated = config.toBuilder()
                .rtpIntervalSeconds(settings.intervalSeconds())
                .teleportDetectionThresholdBlocks(settings.teleportThresholdBlocks())
                .teleportTimeoutSeconds(settings.teleportTimeoutSeconds())
                .stabilizationSeconds(settings.stabilizationSeconds())
                .showHud(settings.showHud())
                .autoResume(settings.autoResume())
                .storeYCoordinate(settings.storeY())
                .showGrid(settings.showGrid())
                .showDistanceRings(settings.showDistanceRings())
                .pointSize(settings.pointSize())
                .allowedServers(settings.allowedServers())
                .selectedRegions(settings.selectedRegions())
                .stopNearCenter(settings.stopNearCenter())
                .centerStopRadiusBlocks(settings.centerStopRadiusBlocks())
                .stopNearWorldBorder(settings.stopNearWorldBorder())
                .worldBorderMarginBlocks(settings.worldBorderMarginBlocks())
                .build();
            configManager.save(updated);
            config = updated;
            cachedSettingsView = toSettingsView(updated);
            if (!updated.autoResume()) {
                cancelAutoResume();
            } else if (resumeAfterReconnect && autoResumeScheduled) {
                scheduleAutoResume(autoResumeConnectionIdentity);
            }
            ioMessage = "Settings saved.";
            return UiActionResult.ok(ioMessage);
        } catch (IllegalArgumentException exception) {
            return UiActionResult.error(exception.getMessage());
        } catch (IOException exception) {
            logger.error("[RTP Mapper] Unable to save config", exception);
            return UiActionResult.error("Unable to save settings; see the log.");
        }
    }

    @Override
    public MapperSettingsView settings() {
        return cachedSettingsView;
    }

    @Override
    public MiningSettingsView miningSettings() {
        return cachedMiningSettingsView;
    }

    @Override
    public UiActionResult applyMiningSettings(MiningSettingsView settings) {
        try {
            RtpMapperConfig updated = config.toBuilder()
                .allowSingleplayerMining(settings.allowSingleplayer())
                .miningAllowedServers(settings.allowedServers())
                .miningBlockIds(settings.blockIds())
                .miningQuantity(settings.quantity())
                .miningTimeoutMinutes(settings.timeoutMinutes())
                .build();
            configManager.save(updated);
            config = updated;
            cachedMiningSettingsView = toMiningSettingsView(updated);
            boolean wasRunning = miningController.isRunning();
            miningController.tick(currentMiningEnvironment());
            if (wasRunning && !miningController.isRunning()) {
                reportMiningStopped();
            } else {
                miningMessage = "Mining settings saved.";
            }
            return UiActionResult.ok(miningMessage);
        } catch (IllegalArgumentException exception) {
            return UiActionResult.error(exception.getMessage());
        } catch (IOException exception) {
            logger.error("[RTP Mapper] Unable to save mining settings", exception);
            return UiActionResult.error("Unable to save mining settings; see the log.");
        }
    }

    @Override
    public UiActionResult startMining() {
        if (closed) {
            return UiActionResult.error("The client is shutting down.");
        }
        if (miningController.isRunning()) {
            return UiActionResult.ok("Baritone mining is already running.");
        }
        cancelAutoResume();
        MiningEnvironment environment = currentMiningEnvironment();
        MiningSettings settings;
        try {
            settings = new MiningSettings(
                config.miningBlockIds(),
                config.miningQuantity(),
                MiningSettings.minutesToNanos(config.miningTimeoutMinutes())
            );
        } catch (IllegalArgumentException exception) {
            return UiActionResult.error(exception.getMessage());
        }
        MiningStartResult result = miningController.start(settings, environment);
        return switch (result) {
            case STARTED -> {
                lastReportedMiningStopReason = MiningStopReason.NONE;
                miningMessage = "Baritone mining started. Press END for an emergency stop.";
                logger.info(
                    "[RTP Mapper] Baritone mining started on {} with {} target(s), quantity {}, timeout {} minutes",
                    environment.serverDescription(),
                    settings.blockIds().size(),
                    settings.quantity(),
                    settings.timeoutMinutes()
                );
                yield UiActionResult.ok(miningMessage);
            }
            case ALREADY_RUNNING -> UiActionResult.ok("Baritone mining is already running.");
            case AUTOMATION_BUSY -> UiActionResult.error(
                "Stop RTP mapping before starting Baritone mining."
            );
            case BARITONE_UNAVAILABLE -> UiActionResult.error(
                "Install the separate Baritone API Fabric 1.17.0 mod for Minecraft 1.21.11."
            );
            case ENVIRONMENT_UNAVAILABLE -> UiActionResult.error(
                "Open a ready single-player world or an explicitly allowed private server first."
            );
            case SERVER_NOT_ALLOWED -> UiActionResult.error(miningServerDeniedMessage(environment));
            case START_FAILED -> UiActionResult.error(
                miningController.lastErrorMessage()
                    .map(message -> "Baritone could not start: " + message)
                    .orElse("Baritone could not start; see the log.")
            );
        };
    }

    @Override
    public UiActionResult stopMining() {
        if (!miningController.stop(MiningStopReason.USER_REQUEST)) {
            return UiActionResult.ok("Baritone mining is already stopped.");
        }
        reportMiningStopped();
        return UiActionResult.ok(miningMessage);
    }

    @Override
    public UiActionResult emergencyStop() {
        cancelAutoResume();
        boolean mapperWasRunning = controller.isRunning();
        if (mapperWasRunning) {
            controller.stop();
            pauseSessionClock();
            mappingConnectionIdentity = null;
        }
        releaseRtpLease();
        boolean miningWasRunning = miningController.emergencyStop();
        if (miningWasRunning) {
            reportMiningStopped();
        }
        requestSave();
        ioMessage = "Emergency stop: all mapper and Baritone automation cancelled.";
        miningMessage = ioMessage;
        logger.warn("[RTP Mapper] Emergency stop activated");
        return UiActionResult.ok(ioMessage);
    }

    @Override
    public MiningStatusView miningStatus() {
        MiningEnvironment environment = currentMiningEnvironment();
        MiningSettings active = miningController.activeSettings().orElse(null);
        List<String> targets = active == null ? config.miningBlockIds() : active.blockIds();
        int quantity = active == null ? config.miningQuantity() : active.quantity();
        double secondsRemaining = miningController.nanosUntilDeadline().isPresent()
            ? miningController.nanosUntilDeadline().getAsLong() / 1_000_000_000.0
            : -1.0;
        boolean baritoneAvailable;
        try {
            baritoneAvailable = miningBackend.available();
        } catch (RuntimeException | LinkageError exception) {
            baritoneAvailable = false;
        }
        String detail = miningMessage;
        if (detail.isBlank()) {
            detail = miningController.lastErrorMessage().orElse("");
        }
        if (detail.isBlank() && environment.ready() && !environment.serverAllowed()) {
            detail = miningServerDeniedMessage(environment);
        }
        return new MiningStatusView(
            baritoneAvailable,
            miningController.isRunning(),
            environment.serverAllowed(),
            miningController.state().name(),
            detail,
            environment.serverDescription(),
            targets,
            quantity,
            secondsRemaining
        );
    }

    private static MapperSettingsView toSettingsView(RtpMapperConfig snapshot) {
        return new MapperSettingsView(
            snapshot.rtpIntervalSeconds(),
            snapshot.teleportDetectionThresholdBlocks(),
            snapshot.teleportTimeoutSeconds(),
            snapshot.stabilizationSeconds(),
            snapshot.showHud(),
            snapshot.autoResume(),
            snapshot.storeYCoordinate(),
            snapshot.showGrid(),
            snapshot.showDistanceRings(),
            snapshot.pointSize(),
            snapshot.allowedServers(),
            snapshot.selectedRegions(),
            snapshot.stopNearCenter(),
            snapshot.centerStopRadiusBlocks(),
            snapshot.stopNearWorldBorder(),
            snapshot.worldBorderMarginBlocks()
        );
    }

    private static MiningSettingsView toMiningSettingsView(RtpMapperConfig snapshot) {
        return new MiningSettingsView(
            snapshot.allowSingleplayerMining(),
            snapshot.miningAllowedServers(),
            snapshot.miningBlockIds(),
            snapshot.miningQuantity(),
            snapshot.miningTimeoutMinutes()
        );
    }

    @Override
    public MapperStatusView status() {
        RtpDatasetSnapshot snapshot = dataset.snapshot();
        Optional<RtpSample> last = snapshot.lastSample(SampleScope.ALL_TIME);
        RtpEnvironmentSnapshot environment = currentEnvironment();
        PositionObservation position = environment.position();
        double seconds = controller.nanosUntilNextSend().isPresent()
            ? controller.nanosUntilNextSend().getAsLong() / 1_000_000_000.0
            : -1.0;
        long sessionDuration = sessionDurationMillis();
        long sessionFailures = Math.max(0, controller.failedRtpAttempts() - failureBaseline);
        RtpSample lastSample = last.orElse(null);
        RtpRegion targetRegion = controller.pendingRequest()
            .map(request -> request.settings().requestedRegion())
            .orElseGet(() -> regionCycle.peek(config.selectedRegions()));
        String guardDetail = controller.isRunning()
            ? ""
            : guardStopMessage(controller.lastStopReason());
        String detail = !guardDetail.isEmpty()
            ? guardDetail
            : !ioMessage.isEmpty()
            ? ioMessage
            : controller.lastErrorMessage().orElse("");
        return new MapperStatusView(
            controller.isRunning(),
            environment.serverAllowed(),
            controller.state().name(),
            snapshot.sessionCount(),
            snapshot.totalCount(),
            position != null,
            position == null ? 0 : position.x(),
            position == null ? 0 : position.y(),
            position == null ? 0 : position.z(),
            lastSample != null,
            lastSample == null ? 0 : lastSample.sampleNumber(),
            lastSample == null ? 0 : lastSample.x(),
            lastSample == null ? 0 : lastSample.y(),
            lastSample == null ? 0 : lastSample.z(),
            lastSample == null ? "" : lastSample.dimension(),
            lastSample == null ? 0 : lastSample.timestamp(),
            seconds,
            sessionDuration,
            (int)Math.min(Integer.MAX_VALUE, sessionFailures),
            detail,
            targetRegion,
            lastSample == null ? RtpRegion.UNKNOWN : lastSample.requestedRegion(),
            config.selectedRegions().size()
        );
    }

    @Override
    public ChartPointProvider points(DataScope scope) {
        return scope == DataScope.SESSION ? sessionPoints : allTimePoints;
    }

    @Override
    public MapperStatisticsView statistics(DataScope scope) {
        SampleScope sampleScope = scope == DataScope.SESSION ? SampleScope.SESSION : SampleScope.ALL_TIME;
        RtpDatasetSnapshot datasetSnapshot = dataset.snapshot();
        long revision = datasetSnapshot.revision(sampleScope);
        if (sampleScope == SampleScope.SESSION && revision == cachedSessionStatisticsRevision) {
            return cachedSessionStatistics;
        }
        if (sampleScope == SampleScope.ALL_TIME && revision == cachedAllTimeStatisticsRevision) {
            return cachedAllTimeStatistics;
        }
        RtpStatistics stats = statisticsCalculator.calculate(datasetSnapshot, sampleScope);
        if (!stats.hasSamples()) {
            MapperStatisticsView empty = MapperStatisticsView.empty();
            cacheStatistics(sampleScope, revision, empty);
            return empty;
        }
        QuadrantStatistics ne = stats.quadrant(Quadrant.NORTH_EAST);
        QuadrantStatistics nw = stats.quadrant(Quadrant.NORTH_WEST);
        QuadrantStatistics se = stats.quadrant(Quadrant.SOUTH_EAST);
        QuadrantStatistics sw = stats.quadrant(Quadrant.SOUTH_WEST);
        List<MapperStatisticsView.RadialBucketView> buckets = stats.radialBuckets().stream()
            .map(RtpMapperRuntime::bucketView)
            .toList();
        List<MapperStatisticsView.RegionCountView> regionCounts = RtpRegion.displayValues().stream()
            .map(region -> new MapperStatisticsView.RegionCountView(
                region,
                (int)Math.min(Integer.MAX_VALUE, stats.regionCounts().getOrDefault(region, 0L))
            ))
            .toList();
        MapperStatisticsView view = new MapperStatisticsView(
            (int)Math.min(Integer.MAX_VALUE, stats.totalSamples()),
            stats.averageX(),
            stats.averageZ(),
            stats.minimumX(),
            stats.maximumX(),
            stats.minimumZ(),
            stats.maximumZ(),
            stats.averageDistance(),
            stats.minimumDistance(),
            stats.maximumDistance(),
            ne.percentage(),
            nw.percentage(),
            se.percentage(),
            sw.percentage(),
            buckets,
            regionCounts
        );
        cacheStatistics(sampleScope, revision, view);
        return view;
    }

    private void cacheStatistics(SampleScope scope, long revision, MapperStatisticsView view) {
        if (scope == SampleScope.SESSION) {
            cachedSessionStatisticsRevision = revision;
            cachedSessionStatistics = view;
        } else {
            cachedAllTimeStatisticsRevision = revision;
            cachedAllTimeStatistics = view;
        }
    }

    private static MapperStatisticsView.RadialBucketView bucketView(RadialBucket bucket) {
        return new MapperStatisticsView.RadialBucketView(
            bucket.minimumDistance(),
            bucket.maximumDistance(),
            (int)Math.min(Integer.MAX_VALUE, bucket.count())
        );
    }

    private void refreshPointProviders() {
        RtpDatasetSnapshot snapshot = dataset.snapshot();
        allTimePoints.update(snapshot);
        sessionPoints.update(snapshot);
    }

    private void requestSave() {
        if (!datasetLoadSafe || closed) {
            return;
        }
        pendingSave.accumulateAndGet(dataset.snapshot(), RtpMapperRuntime::newerSnapshot);
        if (storageHealthy) {
            scheduleSaveWorker();
        }
    }

    private void scheduleSaveWorker() {
        if (!saveWorkerScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            ioExecutor.execute(this::drainSaves);
        } catch (RejectedExecutionException exception) {
            saveWorkerScheduled.set(false);
            ioMessage = "Unable to queue dataset save: IO worker is shutting down.";
            if (pendingSave.get() != null) {
                nextStorageRetryAtNanos = clock.nanoTime() + SAVE_RETRY_DELAY_NANOS;
            } else if (pendingMirrorRepair.get() != null) {
                nextMirrorRetryAtNanos = clock.nanoTime() + mirrorRetryDelayNanos();
            }
        }
    }

    private void drainSaves() {
        RtpDatasetSnapshot attempted = pendingSave.getAndSet(null);
        boolean mirrorOnly = false;
        if (attempted == null && storageHealthy) {
            attempted = pendingMirrorRepair.getAndSet(null);
            mirrorOnly = attempted != null;
        }
        if (attempted == null) {
            saveWorkerScheduled.set(false);
            if (storageHealthy && pendingSave.get() != null && !closed) {
                scheduleSaveWorker();
            }
            return;
        }
        try {
            if (mirrorOnly) {
                storage.repairCsvMirror(attempted);
                if (pendingMirrorRepair.get() == null) {
                    clearMirrorRepair();
                }
                ioMessage = "Repaired the CSV mirror for " + attempted.totalCount() + " samples.";
            } else {
                boolean mirrorAttemptDue = !mirrorRepairPending
                    || deadlineReached(clock.nanoTime(), nextMirrorRetryAtNanos);
                if (mirrorAttemptDue) {
                    storage.save(attempted);
                } else {
                    storage.saveCanonical(attempted);
                }
                storageHealthy = true;
                nextStorageRetryAtNanos = 0;
                if (!mirrorAttemptDue) {
                    pendingMirrorRepair.accumulateAndGet(attempted, RtpMapperRuntime::newerSnapshot);
                    ioMessage = "Samples saved; CSV mirror repair remains backed off.";
                } else {
                    boolean mirrorFailed = reportMirrorFailureIfPresent();
                    if (mirrorFailed) {
                        queueMirrorRepair(attempted);
                        ioMessage = "Samples saved; the CSV mirror will be retried later.";
                    } else {
                        clearMirrorRepair();
                        ioMessage = "Saved " + attempted.totalCount() + " all-time samples.";
                    }
                }
            }
        } catch (IOException exception) {
            if (mirrorOnly) {
                storage.takeMirrorFailure();
                queueMirrorRepair(attempted);
                ioMessage = "CSV mirror repair failed; canonical samples remain safe.";
                logger.warn("[RTP Mapper] Unable to repair the CSV mirror; retry is backed off", exception);
            } else {
                storageHealthy = false;
                nextStorageRetryAtNanos = clock.nanoTime() + SAVE_RETRY_DELAY_NANOS;
                pendingSave.accumulateAndGet(attempted, RtpMapperRuntime::newerSnapshot);
                ioMessage = "Dataset save failed; mapping stopped to protect collected data.";
                logger.error("[RTP Mapper] Unable to save dataset", exception);
            }
        } finally {
            saveWorkerScheduled.set(false);
            if (storageHealthy && pendingSave.get() != null && !closed) {
                // One snapshot per task keeps coalescing bounded and lets a
                // newly arrived canonical generation supersede older work.
                scheduleSaveWorker();
            }
        }
    }

    private UiActionResult persistClearBarrier(RtpDatasetSnapshot emptySnapshot) {
        final Future<Boolean> clearSave;
        try {
            clearSave = ioExecutor.submit(() -> {
                storage.save(emptySnapshot);
                return reportMirrorFailureIfPresent();
            });
        } catch (RejectedExecutionException exception) {
            storageHealthy = false;
            pendingSave.accumulateAndGet(emptySnapshot, RtpMapperRuntime::newerSnapshot);
            ioMessage = "Unable to queue the durable clear operation.";
            return UiActionResult.error(ioMessage);
        }

        try {
            boolean mirrorFailed = clearSave.get(IO_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            storageHealthy = true;
            if (mirrorFailed) {
                queueMirrorRepair(emptySnapshot);
                ioMessage = "Samples were cleared safely; CSV mirror cleanup is pending.";
                return UiActionResult.error(ioMessage);
            }
            pendingSave.set(null);
            nextStorageRetryAtNanos = 0;
            clearMirrorRepair();
            return UiActionResult.ok("All RTP samples cleared and saved.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return clearPersistenceFailure(emptySnapshot, "Interrupted while saving cleared data.", exception);
        } catch (ExecutionException exception) {
            return clearPersistenceFailure(emptySnapshot, "Unable to save cleared data; mapping remains disabled.", exception.getCause());
        } catch (TimeoutException exception) {
            return clearPersistenceFailure(emptySnapshot, "Timed out saving cleared data; mapping remains disabled.", exception);
        }
    }

    private UiActionResult clearPersistenceFailure(
        RtpDatasetSnapshot emptySnapshot,
        String message,
        Throwable exception
    ) {
        storageHealthy = false;
        nextStorageRetryAtNanos = clock.nanoTime() + SAVE_RETRY_DELAY_NANOS;
        pendingSave.accumulateAndGet(emptySnapshot, RtpMapperRuntime::newerSnapshot);
        ioMessage = message;
        logger.error("[RTP Mapper] Durable clear operation failed", exception);
        return UiActionResult.error(message);
    }

    private void logTransition(Object after, PositionObservation position) {
        if (after.toString().equals("WAITING_FOR_STABILIZATION")) {
            double distance = controller.pendingRequest()
                .filter(ignored -> position != null)
                .map(request -> Math.hypot(
                    position.x() - request.baseline().x(),
                    position.z() - request.baseline().z()
                ))
                .orElse(Double.NaN);
            if (Double.isFinite(distance)) {
                logger.info(
                    "[RTP Mapper] Teleport detected: horizontal delta={} blocks; waiting for stabilization",
                    Math.round(distance)
                );
            } else {
                logger.info("[RTP Mapper] Teleport detected; waiting for position stabilization");
            }
        }
    }

    private void releaseRtpLease() {
        AutomationCoordinator.Lease owned = rtpLease;
        rtpLease = null;
        if (owned != null && !automationCoordinator.release(owned)) {
            logger.warn("[RTP Mapper] Ignored a stale RTP automation lease release");
        }
    }

    private boolean baritoneMineProcessActive() {
        try {
            return miningBackend.anyMineProcessActive();
        } catch (RuntimeException | LinkageError exception) {
            if (!baritoneStatusFailureLogged) {
                baritoneStatusFailureLogged = true;
                logger.warn(
                    "[RTP Mapper] Cannot verify Baritone mining state; RTP automation is blocked fail-closed",
                    exception
                );
            }
            return true;
        }
    }

    private void reportMiningStopped() {
        MiningStopReason reason = miningController.lastStopReason();
        if (reason == MiningStopReason.NONE || reason == lastReportedMiningStopReason) {
            return;
        }
        lastReportedMiningStopReason = reason;
        miningMessage = switch (reason) {
            case USER_REQUEST -> "Baritone mining stopped.";
            case EMERGENCY_STOP -> "Emergency stop cancelled Baritone mining.";
            case COMPLETED -> "The Baritone mining process ended.";
            case TIMEOUT -> "Baritone mining stopped at the configured timeout.";
            case DISCONNECTED -> "Baritone mining stopped on disconnect.";
            case CONNECTION_CHANGED -> "Baritone mining stopped because the connection changed.";
            case SERVER_NOT_ALLOWED -> "Baritone mining stopped because this server is not allowed.";
            case BACKEND_ERROR -> miningController.lastErrorMessage()
                .map(message -> "Baritone stopped with an error: " + message)
                .orElse("Baritone stopped with an error; see the log.");
            case CLIENT_SHUTDOWN -> "Baritone mining stopped during client shutdown.";
            case NONE -> "";
        };
        logger.info("[RTP Mapper] Baritone mining stopped: {}", reason);
    }

    private String miningServerDeniedMessage(MiningEnvironment environment) {
        if (MiningServerPolicy.isHardBlockedServer(environment.serverDescription())) {
            return "Baritone mining is disabled on "
                + MiningServerPolicy.HARD_BLOCKED_SERVER
                + " and its subdomains.";
        }
        if (client.isLocalServer()) {
            return "Enable single-player mining in Mining Settings before starting.";
        }
        return "This server is not in the separate Baritone mining allowlist.";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelAutoResume();
        if (miningController.stop(MiningStopReason.CLIENT_SHUTDOWN)) {
            reportMiningStopped();
        } else {
            miningController.emergencyStop();
        }
        controller.stop();
        pauseSessionClock();
        mappingConnectionIdentity = null;
        releaseRtpLease();
        joinedConnectionIdentity = null;
        exportExecutor.shutdownNow();
        exportInProgress.set(false);

        Future<?> finalFlush = null;
        RtpDatasetSnapshot shutdownSnapshot = null;
        if (datasetLoadSafe) {
            shutdownSnapshot = dataset.snapshot();
            pendingSave.accumulateAndGet(shutdownSnapshot, RtpMapperRuntime::newerSnapshot);
            RtpDatasetSnapshot guaranteedSnapshot = shutdownSnapshot;
            try {
                // This task is an ordered barrier behind every prior save.
                // It unconditionally writes the final generation, so
                // it cannot be lost in the coalescing worker's exit window.
                finalFlush = ioExecutor.submit(() -> {
                    RtpDatasetSnapshot snapshot = newerSnapshot(
                        guaranteedSnapshot,
                        pendingSave.getAndSet(null)
                    );
                    try {
                        storage.save(snapshot);
                        reportMirrorFailureIfPresent();
                        storageHealthy = true;
                    } catch (IOException exception) {
                        pendingSave.accumulateAndGet(snapshot, RtpMapperRuntime::newerSnapshot);
                        throw exception;
                    }
                    return null;
                });
            } catch (RejectedExecutionException exception) {
                logger.error("[RTP Mapper] Unable to queue final persistent-data flush", exception);
            }
        }
        ioExecutor.shutdown();

        boolean finalFlushNeedsFallback = datasetLoadSafe && finalFlush == null;
        boolean restoreInterrupt = false;
        try {
            if (finalFlush != null) {
                finalFlush.get(IO_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } else if (!ioExecutor.awaitTermination(IO_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
                logger.error("[RTP Mapper] Timed out while stopping the IO worker");
            }
        } catch (InterruptedException exception) {
            restoreInterrupt = true;
            Thread.interrupted();
            finalFlushNeedsFallback = datasetLoadSafe;
            pendingSave.accumulateAndGet(shutdownSnapshot, RtpMapperRuntime::newerSnapshot);
            if (finalFlush != null) {
                finalFlush.cancel(true);
            }
            ioExecutor.shutdownNow();
            logger.error("[RTP Mapper] Interrupted while flushing persistent data during shutdown", exception);
        } catch (ExecutionException exception) {
            finalFlushNeedsFallback = datasetLoadSafe;
            pendingSave.accumulateAndGet(shutdownSnapshot, RtpMapperRuntime::newerSnapshot);
            logger.error("[RTP Mapper] Final queued dataset save failed", exception.getCause());
        } catch (TimeoutException exception) {
            finalFlushNeedsFallback = datasetLoadSafe;
            pendingSave.accumulateAndGet(shutdownSnapshot, RtpMapperRuntime::newerSnapshot);
            finalFlush.cancel(true);
            ioExecutor.shutdownNow();
            logger.error("[RTP Mapper] Timed out while flushing persistent data during shutdown", exception);
        }

        if (finalFlushNeedsFallback) {
            ioExecutor.shutdownNow();
            boolean terminated = false;
            try {
                terminated = ioExecutor.awaitTermination(
                    IO_TERMINATION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                );
            } catch (InterruptedException exception) {
                restoreInterrupt = true;
                Thread.interrupted();
            }
            if (terminated) {
                attemptBoundedFinalSave(shutdownSnapshot);
            } else {
                logger.error(
                    "[RTP Mapper] IO worker did not terminate; a concurrent fallback would risk corrupting persistent data"
                );
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    private void attemptBoundedFinalSave(RtpDatasetSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        ExecutorService fallbackExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RTP Mapper Final Save");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> fallback = fallbackExecutor.submit(() -> {
            storage.save(snapshot);
            reportMirrorFailureIfPresent();
            return null;
        });
        fallbackExecutor.shutdown();
        try {
            fallback.get(IO_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pendingSave.set(null);
            storageHealthy = true;
            logger.info("[RTP Mapper] Flushed {} samples with the shutdown fallback", snapshot.totalCount());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fallback.cancel(true);
            fallbackExecutor.shutdownNow();
            logger.error("[RTP Mapper] Interrupted during the final-save fallback", exception);
        } catch (ExecutionException exception) {
            logger.error("[RTP Mapper] Final-save fallback failed", exception.getCause());
        } catch (TimeoutException exception) {
            fallback.cancel(true);
            fallbackExecutor.shutdownNow();
            logger.error("[RTP Mapper] Final-save fallback timed out", exception);
        }
    }

    private void resumeSessionClock() {
        if (!sessionClockRunning) {
            sessionActiveSinceNanos = clock.nanoTime();
            sessionClockRunning = true;
        }
    }

    private void pauseSessionClock() {
        if (!sessionClockRunning) {
            return;
        }
        long elapsed = Math.max(0, clock.nanoTime() - sessionActiveSinceNanos);
        accumulatedSessionDurationNanos = saturatingAdd(accumulatedSessionDurationNanos, elapsed);
        sessionClockRunning = false;
        sessionActiveSinceNanos = 0;
    }

    private long sessionDurationMillis() {
        long nanos = accumulatedSessionDurationNanos;
        if (sessionClockRunning) {
            nanos = saturatingAdd(nanos, Math.max(0, clock.nanoTime() - sessionActiveSinceNanos));
        }
        return nanos / 1_000_000L;
    }

    private static long secondsToNanos(double seconds) {
        return Math.max(1L, Math.round(seconds * 1_000_000_000.0));
    }

    private boolean reportMirrorFailureIfPresent() {
        IOException mirrorFailure = storage.takeMirrorFailure();
        if (mirrorFailure != null) {
            logger.warn("[RTP Mapper] CSV mirror update failed; canonical JSON was saved", mirrorFailure);
            return true;
        }
        return false;
    }

    private void queueMirrorRepair(RtpDatasetSnapshot snapshot) {
        pendingMirrorRepair.accumulateAndGet(snapshot, RtpMapperRuntime::newerSnapshot);
        mirrorRepairPending = true;
        mirrorRetryFailures = Math.min(30, mirrorRetryFailures + 1);
        nextMirrorRetryAtNanos = clock.nanoTime() + mirrorRetryDelayNanos();
    }

    private void clearMirrorRepair() {
        pendingMirrorRepair.set(null);
        mirrorRepairPending = false;
        mirrorRetryFailures = 0;
        nextMirrorRetryAtNanos = 0;
    }

    private long mirrorRetryDelayNanos() {
        int shift = Math.min(6, Math.max(0, mirrorRetryFailures - 1));
        return Math.min(MAX_MIRROR_RETRY_DELAY_NANOS, SAVE_RETRY_DELAY_NANOS << shift);
    }

    private static boolean deadlineReached(long now, long deadline) {
        return now - deadline >= 0;
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static RtpDatasetSnapshot newerSnapshot(RtpDatasetSnapshot first, RtpDatasetSnapshot second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.revision() >= second.revision() ? first : second;
    }

    private static String guardStopMessage(RtpStopReason reason) {
        return switch (reason) {
            case CENTER_GUARD_REACHED ->
                "Recorded the RTP sample, then stopped inside the configured center guard.";
            case WORLD_BORDER_GUARD_REACHED ->
                "Recorded the RTP sample, then stopped inside the configured world-border margin.";
            default -> "";
        };
    }

    static RtpRegion latestKnownRequestedRegion(RtpDataset dataset) {
        List<RtpSample> samples = dataset.snapshot().allTimeSamples();
        for (int index = samples.size() - 1; index >= 0; index--) {
            RtpRegion region = samples.get(index).requestedRegion();
            if (region.selectable()) {
                return region;
            }
        }
        return RtpRegion.UNKNOWN;
    }

    private static final class DatasetPointProvider implements ChartPointProvider {
        private final SampleScope scope;
        private List<RtpSample> samples = List.of();
        private long revision;

        private DatasetPointProvider(SampleScope scope) {
            this.scope = scope;
        }

        private void update(RtpDatasetSnapshot snapshot) {
            samples = snapshot.samples(scope);
            revision = snapshot.revision(scope);
        }

        @Override
        public int size() {
            return samples.size();
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public long sampleNumberAt(int index) {
            return samples.get(index).sampleNumber();
        }

        @Override
        public double xAt(int index) {
            return samples.get(index).x();
        }

        @Override
        public double yAt(int index) {
            return samples.get(index).y();
        }

        @Override
        public double zAt(int index) {
            return samples.get(index).z();
        }

        @Override
        public long timestampAt(int index) {
            return samples.get(index).timestamp();
        }

        @Override
        public String dimensionAt(int index) {
            return samples.get(index).dimension();
        }

        @Override
        public String categoryAt(int index) {
            return samples.get(index).category().name();
        }

        @Override
        public String requestedRegionAt(int index) {
            return samples.get(index).requestedRegion().id();
        }
    }

    @Override
    public HunterSettingsView hunterSettings() {
        return HunterSettingsView.fromConfig(config);
    }

    @Override
    public List<BaseDiscovery> discoveredBases() {
        return baseLogManager.allBases();
    }

    @Override
    public UiActionResult toggleBaseRaided(BaseDiscovery base) {
        if (base == null) {
            return UiActionResult.error("Invalid base selected.");
        }
        boolean nowRaided = baseLogManager.toggleRaided(base.x(), base.z(), base.dimension());
        String msg = nowRaided ? "Marked base at X: " + Math.round(base.x()) + " as RAIDED (ignored on future scans)."
                               : "Marked base at X: " + Math.round(base.x()) + " as FRESH.";
        return UiActionResult.ok(msg);
    }

    @Override
    public UiActionResult applyHunterSettings(HunterSettingsView settings) {
        try {
            RtpMapperConfig updated = config.toBuilder()
                    .autoTrash(settings.autoTrash())
                    .autoLoot(settings.autoLoot())
                    .logBases(settings.logBases())
                    .soundAlerts(settings.soundAlerts())
                    .discordWebhookUrl(settings.discordWebhookUrl())
                    .autoEat(settings.autoEat())
                    .autoTotem(settings.autoTotem())
                    .itemSaver(settings.itemSaver())
                    .itemSaverThreshold(settings.itemSaverThreshold())
                    .antiStuckWatchdog(settings.antiStuckWatchdog())
                    .antiStuckTimeoutSeconds(settings.antiStuckTimeoutSeconds())
                    .showHunterHud(settings.showHunterHud())
                    .skipRaidedBases(settings.skipRaidedBases())
                    .espTracers(settings.espTracers())
                    .autoEmergencyEscape(settings.autoEmergencyEscape())
                    .build();
            configManager.save(updated);
            config = updated;
            ioMessage = "Hunter settings saved.";
            return UiActionResult.ok(ioMessage);
        } catch (IOException exception) {
            ioMessage = "Failed to save hunter settings: " + exception.getMessage();
            return UiActionResult.error(ioMessage);
        }
    }

    public List<BlockPos> nearbyTargetBlockPositions() {
        return nearbyTargetBlockPositions;
    }

    public boolean isEspTracersEnabled() {
        return config.espTracers();
    }

    private void scanNearbyBaseBlocks() {
        if (client.player == null || client.level == null) {
            return;
        }
        BlockPos playerPos = client.player.blockPosition();
        List<String> foundBaseBlocks = new ArrayList<>();
        List<BlockPos> currentScanPositions = new ArrayList<>();

        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    BlockState state = client.level.getBlockState(pos);
                    Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (id != null) {
                        String name = id.toString();
                        if (name.equals("minecraft:spawner") || name.contains("chest")
                                || name.contains("shulker_box") || name.equals("minecraft:beacon")
                                || name.equals("minecraft:barrel")) {
                            if (!foundBaseBlocks.contains(name)) {
                                foundBaseBlocks.add(name);
                            }
                            currentScanPositions.add(pos);
                        }
                    }
                }
            }
        }
        nearbyTargetBlockPositions.clear();
        nearbyTargetBlockPositions.addAll(currentScanPositions);

        if (!foundBaseBlocks.isEmpty()) {
            String dim = client.level.dimension().identifier().toString();
            if (config.skipRaidedBases() && baseLogManager.isNearbyRaided(client.player.getX(), client.player.getZ(), dim)) {
                return;
            }
            boolean isNew = baseLogManager.recordDiscovery(
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ(),
                    dim,
                    foundBaseBlocks,
                    config.logBases()
            );
            if (isNew) {
                if (config.soundAlerts() && client.getSoundManager() != null) {
                    try {
                        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
                    } catch (Exception ignored) {}
                }
                if (!config.discordWebhookUrl().isBlank()) {
                    WebhookNotifier.sendBaseAlert(
                            config.discordWebhookUrl(),
                            client.player.getX(),
                            client.player.getY(),
                            client.player.getZ(),
                            dim,
                            foundBaseBlocks
                    );
                }
                client.player.displayClientMessage(
                        Component.literal("[RTP Mapper] 🎯 Base Structure Discovered at X: "
                                + Math.round(client.player.getX()) + " Y: " + Math.round(client.player.getY())
                                + " Z: " + Math.round(client.player.getZ()) + " (" + String.join(", ", foundBaseBlocks) + ")")
                                .withStyle(ChatFormatting.GOLD),
                        false
                );
            }
        }
    }
}
