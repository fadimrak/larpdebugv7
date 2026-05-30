package com.larp.debug.modules;

import com.larp.debug.AddonTemplate;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

public class Finder extends Module {

    private final SettingGroup sgGeneral;
    private final Setting<Boolean> onlyOnBedrock;
    private final Setting<Boolean> antiEspMode;
    private final Setting<Boolean> playerBasedDetection;
    private final Setting<Boolean> serverSideBypass;
    private final Setting<Boolean> strictValidation;
    private final Setting<Boolean> requirePlayerPresence;
    private final Setting<Boolean> playerPacketDetection;
    private final Setting<Boolean> detectOnBedrock;
    private final Setting<Boolean> detectUnderDeepslate;
    private final Setting<Boolean> packetAntiEspBypass;
    private final Setting<Boolean> showPendingChunks;
    private final Setting<Integer> scanDelay;
    private final Setting<Integer> maxRetries;
    private final Setting<RenderMode> renderMode;
    private final Setting<Integer> flatRenderY;

    private final Set<ChunkPos> spawnerChunks;
    private final Set<ChunkPos> pendingChunks;
    private final Set<ChunkPos> playerChunks;
    private final Set<BlockPos> playerPositions;
    private final Set<Integer> flaggedEntities;
    private final Set<ChunkPos> entityChunks;
    private final Set<ChunkPos> redstoneChunks;
    private final Set<ChunkPos> activeBaseChunks;
    private final Set<ChunkPos> greenBaseChunks;
    private final Set<BlockPos> redstoneActivity;
    private final Set<ChunkPos> particleActivity;
    private final Set<ChunkPos> soundActivity;
    private final Set<ChunkPos> storageChunks;
    private final Map<ChunkPos, Long> lastActivityTime;

    public Finder() {
        super(AddonTemplate.CATEGORY, "Finder", "Markiert Chunks mit verdaechtigen Aktivitaeten (Spawner, Redstone, etc.).");
        this.sgGeneral = this.settings.getDefaultGroup();

        this.onlyOnBedrock = this.sgGeneral.add(new BoolSetting.Builder()
            .name("only-on-bedrock")
            .description("Nur Spawner auf Bedrock-Hoehe (Y=0) erkennen.")
            .defaultValue(true)
            .build());

        this.antiEspMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("anti-esp-mode")
            .description("Anti-ESP Fix: Verwendet mehrere Detection-Methoden.")
            .defaultValue(true)
            .build());

        this.playerBasedDetection = this.sgGeneral.add(new BoolSetting.Builder()
            .name("player-based-detection")
            .description("Player-basierte Detection (umgeht Anti-ESP).")
            .defaultValue(true)
            .build());

        this.serverSideBypass = this.sgGeneral.add(new BoolSetting.Builder()
            .name("server-side-bypass")
            .description("Server-seitige Anti-ESP Umgehung.")
            .defaultValue(false)
            .build());

        this.strictValidation = this.sgGeneral.add(new BoolSetting.Builder()
            .name("strict-validation")
            .description("Strikte Validierung um false positives zu vermeiden.")
            .defaultValue(false)
            .build());

        this.requirePlayerPresence = this.sgGeneral.add(new BoolSetting.Builder()
            .name("require-player-presence")
            .description("Benoetigt Player in der Naehe fuer Detection.")
            .defaultValue(false)
            .build());

        this.playerPacketDetection = this.sgGeneral.add(new BoolSetting.Builder()
            .name("player-packet-detection")
            .description("Player-Packet Detection fuer Bedrock/Deepslate.")
            .defaultValue(true)
            .build());

        this.detectOnBedrock = this.sgGeneral.add(new BoolSetting.Builder()
            .name("detect-on-bedrock")
            .description("Player auf Bedrock (Y <= -64) detektieren.")
            .defaultValue(true)
            .build());

        this.detectUnderDeepslate = this.sgGeneral.add(new BoolSetting.Builder()
            .name("detect-under-deepslate")
            .description("Player unter Deepslate (Y < 0) detektieren.")
            .defaultValue(true)
            .build());

        this.packetAntiEspBypass = this.sgGeneral.add(new BoolSetting.Builder()
            .name("packet-anti-esp-bypass")
            .description("Verwendet Packet-Detection fuer Anti-ESP Bypass.")
            .defaultValue(true)
            .build());

        this.showPendingChunks = this.sgGeneral.add(new BoolSetting.Builder()
            .name("show-pending-chunks")
            .description("Zeigt gelbe Chunks waehrend des Scannens an.")
            .defaultValue(true)
            .build());

        this.scanDelay = this.sgGeneral.add(new IntSetting.Builder()
            .name("scan-delay")
            .description("Verzoegerung zwischen Scans in ms (Anti-ESP).")
            .defaultValue(100)
            .min(50)
            .max(1000)
            .sliderMax(1000)
            .build());

        this.maxRetries = this.sgGeneral.add(new IntSetting.Builder()
            .name("max-retries")
            .defaultValue(3)
            .min(1)
            .max(10)
            .sliderMax(10)
            .build());

        this.renderMode = this.sgGeneral.add(new EnumSetting.Builder<RenderMode>()
            .name("render-mode")
            .description("Kies tussen pillar of flat chunk rendering.")
            .defaultValue(RenderMode.Pillar)
            .build());

        this.flatRenderY = this.sgGeneral.add(new IntSetting.Builder()
            .name("flat-render-y")
            .description("Y-level voor flat chunk render.")
            .defaultValue(64)
            .range(-64, 320)
            .visible(() -> this.renderMode.get() == RenderMode.Flat)
            .build());

        this.spawnerChunks   = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.pendingChunks   = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.playerChunks    = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.playerPositions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.flaggedEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.entityChunks    = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.redstoneChunks  = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.activeBaseChunks= Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.greenBaseChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.redstoneActivity= Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.particleActivity= Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.soundActivity   = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.storageChunks   = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.lastActivityTime= new ConcurrentHashMap<>();
    }

    @Override
    public void onActivate() {
        clearAllSets();
    }

    private void clearAllSets() {
        spawnerChunks.clear();
        pendingChunks.clear();
        playerChunks.clear();
        playerPositions.clear();
        flaggedEntities.clear();
        entityChunks.clear();
        redstoneChunks.clear();
        activeBaseChunks.clear();
        greenBaseChunks.clear();
        redstoneActivity.clear();
        particleActivity.clear();
        soundActivity.clear();
        storageChunks.clear();
        lastActivityTime.clear();
    }

    public void clearCache() {
        clearAllSets();
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    @EventHandler(priority = 200)
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        try {
            if (mc.world.getTime() % 20L == 0L) {
                scanForHiddenSpawners();
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Packet handler
    // -------------------------------------------------------------------------

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        try {
            if (playerPacketDetection.get() && mc.world != null && mc.player != null) {
                scanForPlayersInWorld();
                cleanupOldPlayerChunks();
            }

            if (event.packet instanceof BlockEntityUpdateS2CPacket p) {
                handleBlockEntityUpdate(p);
            }
            if (event.packet instanceof ChunkDataS2CPacket p) {
                handleChunkDataPacket(p);
            }
            if (packetAntiEspBypass.get()) {
                if (event.packet instanceof BlockUpdateS2CPacket p) {
                    handleRedstonePacket(p);
                }
                if (event.packet instanceof ParticleS2CPacket p) {
                    handleParticlePacket(p);
                }
                if (event.packet instanceof PlaySoundS2CPacket p) {
                    handleSoundPacket(p);
                }
                if (event.packet instanceof EntitySpawnS2CPacket p) {
                    handleEntitySpawn(p);
                    applyEntitySpawnAntiEsp(p);
                }
            }
            if (event.packet instanceof EntityStatusS2CPacket p) {
                handleEntityStatus(p);
            }
            if (event.packet instanceof EntitiesDestroyS2CPacket p) {
                handleEntityDestroy(p);
            }
            if (event.packet instanceof ScoreboardScoreUpdateS2CPacket p) {
                handleScoreboardUpdate(p);
            }

            if (antiEspMode.get() && mc.world != null) {
                scanForSpawnersDirect();
                cleanupOldSpawnerChunks();
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Chunk data event
    // -------------------------------------------------------------------------

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ChunkPos chunkPos = event.chunk().getPos();
        pendingChunks.add(chunkPos);

        if (antiEspMode.get()) {
            int retryCount = maxRetries.get();
            for (int i = 0; i < retryCount; i++) {
                final int retry = i;
                new Thread(() -> {
                    try {
                        Thread.sleep(scanDelay.get() * (long)(retry + 1));
                        if (playerBasedDetection.get()) {
                            scanChunkPlayerBased(event.chunk(), retry);
                        } else {
                            scanChunkStandard(event.chunk(), retry);
                        }
                        if (serverSideBypass.get() && retry == maxRetries.get() - 1) {
                            scanChunkServerBypass(event.chunk());
                        }
                    } catch (InterruptedException ignored) {}
                }).start();
            }
        } else {
            new Thread(() -> scanChunkStandard(event.chunk(), 0)).start();
        }
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        for (ChunkPos cp : spawnerChunks)    renderChunkBox(event, cp, new SettingColor(0, 255, 0, 75));
        for (ChunkPos cp : greenBaseChunks)  renderChunkBox(event, cp, new SettingColor(0, 255, 100, 100));
        for (ChunkPos cp : playerChunks)     renderChunkBox(event, cp, new SettingColor(0, 100, 255, 75));
        for (ChunkPos cp : entityChunks)     renderChunkBox(event, cp, new SettingColor(255, 165, 0, 100));
        for (ChunkPos cp : redstoneChunks)   renderChunkBox(event, cp, new SettingColor(255, 0, 0, 75));
        for (ChunkPos cp : activeBaseChunks) renderChunkBox(event, cp, new SettingColor(128, 0, 255, 100));
        for (ChunkPos cp : storageChunks)    renderChunkBox(event, cp, new SettingColor(255, 215, 0, 100));

        if (antiEspMode.get() && showPendingChunks.get()) {
            for (ChunkPos cp : pendingChunks) renderChunkBox(event, cp, new SettingColor(255, 255, 0, 50));
        }
    }

    private void renderChunkBox(Render3DEvent event, ChunkPos chunkPos, SettingColor color) {
        double x1 = chunkPos.getStartX();
        double z1 = chunkPos.getStartZ();
        double x2 = chunkPos.getEndX() + 1;
        double z2 = chunkPos.getEndZ() + 1;
        double y1, y2;

        if (renderMode.get() == RenderMode.Flat) {
            y1 = flatRenderY.get();
            y2 = y1 + 1.0;
        } else {
            y1 = mc.world.getBottomY();
            y2 = 320.0;
        }
        event.renderer.box(x1, y1, z1, x2, y2, z2, (Color) color, (Color) color, ShapeMode.Both, 0);
    }

    // -------------------------------------------------------------------------
    // Scanning helpers
    // -------------------------------------------------------------------------

    private void scanForHiddenSpawners() {
        if (mc.world == null || mc.player == null) return;
        try {
            ChunkPos playerChunk = mc.player.getChunkPos();
            int range = 5;
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    ChunkPos chunkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) continue;
                    WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;

                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (!(blockEntity instanceof MobSpawnerBlockEntity spawner)) continue;
                        int spawnerY = spawner.getPos().getY();
                        if (onlyOnBedrock.get() && spawnerY != 0 && (!packetAntiEspBypass.get() || spawnerY > 0)) continue;
                        spawnerChunks.add(chunkPos);
                        break;
                    }

                    for (PlayerEntity player : mc.world.getPlayers()) {
                        if (player == null || !player.getChunkPos().equals(chunkPos)) continue;
                        double playerY = player.getY();
                        boolean shouldDetect = false;
                        if (detectOnBedrock.get() && playerY <= -64.0) shouldDetect = true;
                        else if (detectUnderDeepslate.get() && playerY < 0.0) shouldDetect = true;
                        else if (packetAntiEspBypass.get() && playerY <= 0.0) shouldDetect = true;
                        if (shouldDetect) playerChunks.add(chunkPos);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void scanForPlayersInWorld() {
        if (mc.world == null || mc.player == null) return;
        try {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == null) continue;
                double x = player.getX(), y = player.getY(), z = player.getZ();
                boolean shouldDetect = false;
                if (detectOnBedrock.get() && y <= -64.0) shouldDetect = true;
                else if (detectUnderDeepslate.get() && y < 0.0) shouldDetect = true;
                else if (packetAntiEspBypass.get() && y <= 0.0) shouldDetect = true;
                if (!shouldDetect) continue;

                BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
                playerPositions.add(pos);
                ChunkPos chunkPos = new ChunkPos(pos);
                playerChunks.add(chunkPos);

                if (packetAntiEspBypass.get()) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(scanDelay.get());
                            validatePlayerPosition(pos, chunkPos);
                        } catch (InterruptedException ignored) {}
                    }).start();
                }
            }
        } catch (Exception ignored) {}
    }

    private void validatePlayerPosition(BlockPos pos, ChunkPos chunkPos) {
        if (mc.world == null) return;
        try {
            BlockState state = mc.world.getBlockState(pos);
            BlockState stateBelow = mc.world.getBlockState(pos.down());
            if (state.isOf(Blocks.AIR) || stateBelow.isOf(Blocks.AIR) ||
                state.isOf(Blocks.BEDROCK) || stateBelow.isOf(Blocks.BEDROCK)) {
                playerPositions.remove(pos);
                playerChunks.remove(chunkPos);
            }
        } catch (Exception e) {
            playerPositions.remove(pos);
            playerChunks.remove(chunkPos);
        }
    }

    private void cleanupOldPlayerChunks() {
        if (mc.world == null || mc.player == null) return;
        try {
            playerChunks.removeIf(chunkPos -> {
                for (PlayerEntity player : mc.world.getPlayers()) {
                    if (player == null || !player.getChunkPos().equals(chunkPos)) continue;
                    double y = player.getY();
                    if ((detectOnBedrock.get() && y <= -64.0) || (detectUnderDeepslate.get() && y < 0.0)) return false;
                }
                return true;
            });
            playerPositions.removeIf(pos -> {
                for (PlayerEntity player : mc.world.getPlayers()) {
                    if (player == null) continue;
                    BlockPos pp = new BlockPos((int) player.getX(), (int) player.getY(), (int) player.getZ());
                    if (!pos.equals(pp)) continue;
                    double y = player.getY();
                    return !((detectOnBedrock.get() && y <= -64.0) || (detectUnderDeepslate.get() && y < 0.0));
                }
                return true;
            });
        } catch (Exception e) {
            playerChunks.clear();
            playerPositions.clear();
        }
    }

    private void cleanupOldSpawnerChunks() {
        if (mc.player == null) return;
        try {
            ChunkPos playerChunk = mc.player.getChunkPos();
            int maxDistance = 2;
            spawnerChunks.removeIf(cp ->
                Math.max(Math.abs(cp.x - playerChunk.x), Math.abs(cp.z - playerChunk.z)) > maxDistance
            );
        } catch (Exception ignored) {}
    }

    private void scanForSpawnersDirect() {
        if (mc.world == null || mc.player == null) return;
        try {
            ChunkPos playerChunkPos = mc.player.getChunkPos();
            int range = 3;
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    ChunkPos chunkPos = new ChunkPos(playerChunkPos.x + dx, playerChunkPos.z + dz);
                    if (!mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) continue;
                    WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;
                    scanChunkForSpawners(chunk, chunkPos);
                }
            }
        } catch (Exception ignored) {}
    }

    private void scanChunkForSpawners(WorldChunk chunk, ChunkPos chunkPos) {
        if (chunk == null) return;
        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity instanceof MobSpawnerBlockEntity spawner)) continue;
                int spawnerY = spawner.getPos().getY();
                if (onlyOnBedrock.get() && spawnerY != 0 && (!packetAntiEspBypass.get() || spawnerY > 0)) continue;
                if (strictValidation.get() && !isValidSpawner(spawner)) continue;
                spawnerChunks.add(chunkPos);
                return;
            }
            if (packetAntiEspBypass.get() && hasSpawnerIndicators(chunk)) {
                spawnerChunks.add(chunkPos);
            }
            scanChunkForStorage(chunk, chunkPos);
        } catch (Exception ignored) {}
    }

    private void scanChunkStandard(WorldChunk chunk, int retry) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();
        try {
            if (requirePlayerPresence.get() && !isPlayerNearChunk(pos)) return;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity instanceof MobSpawnerBlockEntity spawner)) continue;
                if (onlyOnBedrock.get() && spawner.getPos().getY() != 0) continue;
                if (strictValidation.get() && !isValidSpawner(spawner)) continue;
                spawnerChunks.add(pos);
                pendingChunks.remove(pos);
                return;
            }
        } catch (Exception ignored) {}
        if (retry == maxRetries.get() - 1) {
            spawnerChunks.remove(pos);
            pendingChunks.remove(pos);
        }
    }

    private void scanChunkPlayerBased(WorldChunk chunk, int retry) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();
        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity instanceof MobSpawnerBlockEntity spawner)) continue;
                if (onlyOnBedrock.get() && spawner.getPos().getY() != 0) continue;
                if (strictValidation.get() && !isValidSpawner(spawner)) continue;
                spawnerChunks.add(pos);
                pendingChunks.remove(pos);
                return;
            }
        } catch (Exception ignored) {}
        if (retry == maxRetries.get() - 1) {
            pendingChunks.remove(pos);
        }
    }

    private void scanChunkServerBypass(WorldChunk chunk) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();
        try {
            int suspiciousBlocks = 0, totalBlocks = 0;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                totalBlocks++;
                if (blockEntity == null) continue;
                if (blockEntity.getCachedState().hasBlockEntity() && blockEntity.getCachedState().isSolidBlock(mc.world, blockEntity.getPos())) {
                    suspiciousBlocks++;
                }
                if (blockEntity instanceof MobSpawnerBlockEntity spawner) {
                    if (!onlyOnBedrock.get() || spawner.getPos().getY() == 0) {
                        spawnerChunks.add(pos);
                        pendingChunks.remove(pos);
                        return;
                    }
                }
            }
            if (totalBlocks > 0 && (double) suspiciousBlocks >= (double) totalBlocks * 0.3) {
                spawnerChunks.add(pos);
                pendingChunks.remove(pos);
                return;
            }
        } catch (Exception ignored) {}
        pendingChunks.remove(pos);
    }

    private void scanChunkForStorage(WorldChunk chunk, ChunkPos chunkPos) {
        if (chunk == null) return;
        try {
            int storageCount = 0, playerMadeCount = 0;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockState state = be.getCachedState();
                if (be instanceof ChestBlockEntity || state.isOf(Blocks.SHULKER_BOX)) {
                    playerMadeCount++;
                    storageCount++;
                    if (be.getPos().getY() < 0) greenBaseChunks.add(chunkPos);
                    continue;
                }
                if (state.isOf(Blocks.BARREL) || state.isOf(Blocks.DROPPER) || state.isOf(Blocks.DISPENSER)) {
                    storageCount++;
                    continue;
                }
                if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE)) {
                    playerMadeCount++;
                    if (be.getPos().getY() < 0) greenBaseChunks.add(chunkPos);
                }
            }
            if (playerMadeCount >= 1 || storageCount >= 5) {
                storageChunks.add(chunkPos);
                if (playerMadeCount >= 1 && storageCount >= 2) activeBaseChunks.add(chunkPos);
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Packet handlers
    // -------------------------------------------------------------------------

    private void handleBlockEntityUpdate(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            ChunkPos chunkPos = new ChunkPos(pos);
            if (mc.world == null) return;
            BlockEntity be = mc.world.getBlockEntity(pos);
            if (!(be instanceof MobSpawnerBlockEntity spawner)) return;
            if (onlyOnBedrock.get() && spawner.getPos().getY() != 0) return;
            if (strictValidation.get() && !isValidSpawner(spawner)) return;
            spawnerChunks.add(chunkPos);
        } catch (Exception ignored) {}
    }

    private void handleChunkDataPacket(ChunkDataS2CPacket packet) {
        try {
            if (mc.world == null || mc.player == null) return;
            ChunkPos playerChunk = mc.player.getChunkPos();
            int range = 3;
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    ChunkPos chunkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.getChunkManager().isChunkLoaded(chunkPos.x, chunkPos.z)) continue;
                    WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;
                    scanChunkForSpawners(chunk, chunkPos);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleRedstonePacket(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getState();
            if (isRedstoneActive(state)) {
                redstoneActivity.add(pos);
                ChunkPos chunkPos = new ChunkPos(pos);
                redstoneChunks.add(chunkPos);
                addBaseChunk(pos);
            }
        } catch (Exception ignored) {}
    }

    private void handleParticlePacket(ParticleS2CPacket packet) {
        try {
            double x = packet.getX(), z = packet.getZ();
            BlockPos pos = new BlockPos((int) x, (int) packet.getY(), (int) z);
            ChunkPos chunkPos = new ChunkPos(pos);
            if (isSuspiciousParticle(packet)) {
                particleActivity.add(chunkPos);
                addBaseChunk(pos);
            }
        } catch (Exception ignored) {}
    }

    private void handleSoundPacket(PlaySoundS2CPacket packet) {
        try {
            double x = packet.getX(), z = packet.getZ();
            BlockPos pos = new BlockPos((int) x, (int) packet.getY(), (int) z);
            ChunkPos chunkPos = new ChunkPos(pos);
            if (isSuspiciousSound(packet)) {
                soundActivity.add(chunkPos);
                addBaseChunk(pos);
            }
        } catch (Exception ignored) {}
    }

    private void handleEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            if (mc.world == null) return;
            for (Entity entity : mc.world.getEntities()) {
                if (entity == null || flaggedEntities.contains(entity.getId())) continue;
                checkEntity(entity);
            }
        } catch (Exception ignored) {}
    }

    private void handleEntityStatus(EntityStatusS2CPacket packet) {
        // intentionally minimal
    }

    private void handleEntityDestroy(EntitiesDestroyS2CPacket packet) {
        try {
            packet.getEntityIds().forEach(flaggedEntities::remove);
        } catch (Exception ignored) {}
    }

    private void handleScoreboardUpdate(ScoreboardScoreUpdateS2CPacket packet) {
        // intentionally minimal
    }

    private void applyEntitySpawnAntiEsp(EntitySpawnS2CPacket packet) {
        try {
            double x = packet.getX(), y = packet.getY(), z = packet.getZ();
            int entityId = packet.getEntityId();
            BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
            BlockState stateBelow = getBlockState(pos.down());
            if (stateBelow != null && (stateBelow.isOf(Blocks.BEDROCK) || stateBelow.isOf(Blocks.AIR))) {
                flagEntityIdAt(entityId, pos);
            }
        } catch (Exception ignored) {}
    }

    private void flagEntityIdAt(int entityId, BlockPos pos) {
        try {
            flaggedEntities.add(entityId);
            entityChunks.add(new ChunkPos(pos));
        } catch (Exception ignored) {}
    }

    private void checkEntity(Entity entity) {
        if (entity == null || mc.world == null) return;
        try {
            BlockPos pos = entity.getBlockPos();
            BlockPos belowPos = pos.down();
            BlockState below = mc.world.getBlockState(belowPos);
            if (below.isOf(Blocks.BEDROCK) || below.isOf(Blocks.AIR)) {
                flagEntity(entity);
            }
        } catch (Exception ignored) {}
    }

    private void flagEntity(Entity entity) {
        if (entity == null) return;
        try {
            if (!flaggedEntities.contains(entity.getId())) {
                flaggedEntities.add(entity.getId());
                entityChunks.add(new ChunkPos(entity.getBlockPos()));
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private void addBaseChunk(BlockPos pos) {
        try {
            BlockPos aboveBasePos = new BlockPos(pos.getX(), pos.getY() + 5, pos.getZ());
            ChunkPos aboveBaseChunk = new ChunkPos(aboveBasePos);
            greenBaseChunks.clear();
            greenBaseChunks.add(aboveBaseChunk);
            activeBaseChunks.clear();
            activeBaseChunks.add(aboveBaseChunk);
            updateActivityTime(aboveBaseChunk);
        } catch (Exception e) {
            ChunkPos normalChunk = new ChunkPos(pos);
            greenBaseChunks.clear();
            greenBaseChunks.add(normalChunk);
            activeBaseChunks.clear();
            activeBaseChunks.add(normalChunk);
        }
    }

    private void updateActivityTime(ChunkPos chunkPos) {
        lastActivityTime.put(chunkPos, System.currentTimeMillis());
    }

    private BlockState getBlockState(BlockPos pos) {
        if (mc.world != null) return mc.world.getBlockState(pos);
        return null;
    }

    private boolean isRedstoneActive(BlockState state) {
        try {
            return state.isOf(Blocks.REDSTONE_WIRE)
                || state.isOf(Blocks.REDSTONE_TORCH)
                || state.isOf(Blocks.REDSTONE_WALL_TORCH)
                || state.isOf(Blocks.REPEATER)
                || state.isOf(Blocks.COMPARATOR)
                || state.isOf(Blocks.OBSERVER)
                || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.HOPPER)
                || state.isOf(Blocks.DROPPER)
                || state.isOf(Blocks.DISPENSER);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSuspiciousParticle(ParticleS2CPacket packet) {
        try {
            String particleType = packet.getParameters().getType().toString().toLowerCase();
            if (particleType.contains("portal") || particleType.contains("end")
                || particleType.contains("chest") || particleType.contains("ender")
                || particleType.contains("shulker")) return false;
            return particleType.contains("redstone") || particleType.contains("dust")
                || particleType.contains("happy_villager") || particleType.contains("composter");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSuspiciousSound(PlaySoundS2CPacket packet) {
        try {
            String soundName = packet.getSound().value().id().toString().toLowerCase();
            return soundName.contains("hopper") || soundName.contains("piston")
                || soundName.contains("dispenser") || soundName.contains("dropper")
                || soundName.contains("furnace") || soundName.contains("redstone");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasSpawnerIndicators(WorldChunk chunk) {
        try {
            int spawnerLikeCount = 0, totalEntities = 0;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                totalEntities++;
                if (blockEntity == null) continue;
                if (blockEntity instanceof ChestBlockEntity || blockEntity.getCachedState().isOf(Blocks.SHULKER_BOX)) {
                    if (blockEntity.getPos().getY() < 0) {
                        greenBaseChunks.add(chunk.getPos());
                        storageChunks.add(chunk.getPos());
                    }
                } else {
                    BlockState s = blockEntity.getCachedState();
                    if ((s.isOf(Blocks.BARREL) || s.isOf(Blocks.DROPPER) || s.isOf(Blocks.DISPENSER))
                        && blockEntity.getPos().getY() < 0) {
                        storageChunks.add(chunk.getPos());
                    }
                }
                if (blockEntity instanceof MobSpawnerBlockEntity spawner && spawner.getPos().getY() < 0) {
                    return true;
                }
                spawnerLikeCount++;
            }
            return spawnerLikeCount >= 1 && totalEntities > 0;
        } catch (Exception e) {
            return !chunk.getBlockEntities().isEmpty();
        }
    }

    private boolean isPlayerNearChunk(ChunkPos pos) {
        if (mc.player == null) return false;
        double distance = Math.sqrt(
            Math.pow(mc.player.getX() - (pos.x * 16 + 8), 2) +
            Math.pow(mc.player.getZ() - (pos.z * 16 + 8), 2)
        );
        return distance < 96.0;
    }

    private boolean isValidSpawner(MobSpawnerBlockEntity spawner) {
        try {
            if (spawner == null || mc.world == null) return false;
            BlockPos pos = spawner.getPos();
            if (!mc.world.getBlockState(pos).isOf(Blocks.SPAWNER)) return false;
            return spawner.getLogic() != null;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public int getSpawnerChunkCount()    { return spawnerChunks.size(); }
    public int getPlayerChunkCount()     { return playerChunks.size(); }
    public int getEntityChunkCount()     { return entityChunks.size(); }
    public int getRedstoneChunkCount()   { return redstoneChunks.size(); }
    public int getActiveBaseChunkCount() { return activeBaseChunks.size(); }

    public boolean isSpawnerChunk(ChunkPos pos)    { return spawnerChunks.contains(pos); }
    public boolean isPlayerChunk(ChunkPos pos)     { return playerChunks.contains(pos); }
    public boolean isEntityChunk(ChunkPos pos)     { return entityChunks.contains(pos); }
    public boolean isRedstoneChunk(ChunkPos pos)   { return redstoneChunks.contains(pos); }
    public boolean isActiveBaseChunk(ChunkPos pos) { return activeBaseChunks.contains(pos); }

    public void findNearbySpawners(PlayerEntity player, int radius) {
        if (player == null || mc.world == null) return;
        BlockPos playerPos = player.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (!mc.world.getBlockState(pos).isOf(Blocks.SPAWNER)) continue;
                    BlockEntity be = mc.world.getBlockEntity(pos);
                    if (be instanceof MobSpawnerBlockEntity) {
                        player.sendMessage(Text.literal("Spawner gefunden bei: " + pos), false);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Enum
    // -------------------------------------------------------------------------

    private enum RenderMode {
        Pillar, Flat
    }
}
