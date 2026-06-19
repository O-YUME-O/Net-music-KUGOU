package com.github.tartaricacid.netmusic.echo;

import com.github.tartaricacid.netmusic.echo.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.echo.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.echo.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.echo.client.gui.EchoLoginScreen;
import com.github.tartaricacid.netmusic.echo.config.AudioQuality;
import com.github.tartaricacid.netmusic.echo.config.ButtonEntry;
import com.github.tartaricacid.netmusic.echo.config.ClientConfig;
import com.github.tartaricacid.netmusic.echo.config.EchoConfig;
import com.github.tartaricacid.netmusic.echo.config.ProviderType;
import com.github.tartaricacid.netmusic.echo.init.InitBlocks;
import com.github.tartaricacid.netmusic.echo.init.InitContainer;
import com.github.tartaricacid.netmusic.echo.init.InitItems;
import com.github.tartaricacid.netmusic.echo.network.NetworkHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import com.github.tartaricacid.netmusic.echo.EchoLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(NetMusicEchoAddon.MOD_ID)
public class NetMusicEchoAddon {
    public static final String MOD_ID = "netmusic_echo_addon";

    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("NETMUSICCANNEEDKUGOU");
    private static final Path STATE_FILE = CONFIG_DIR.resolve("netmusic-echo-addon-state.json");

    /**
     * 旧版本（v1.x）保存登录状态的位置，落在游戏根目录。
     * 新版本迁移到 {@link #STATE_FILE}（config/）后，仍保留一次性的向下兼容读取。
     */
    private static final Path LEGACY_STATE_FILE = FMLPaths.GAMEDIR.get().resolve("netmusic-echo-addon-state.json");

    /**
     * 周期重试 VIP 领取的调度器。
     * <p>
     * 仅在客户端构造；daemon 线程保证不会阻塞游戏进程退出。
     * 调度器会在第一次自动领取失败后按 {@link ClientConfig#VIP_RETRY_INTERVAL_MINUTES} 的间隔持续重试，
     * 直到服务端返回 SUCCESS / ALREADY_CLAIMED，或日期跨日。
     */
    private final ScheduledExecutorService vipScheduler;

    /**
     * 周期扫描玩家物品栏、检查并自动续期失效 CD URL 的调度器。
     * <p>
     * 由 {@link #startUrlRefreshScheduler()} 在服务端起来时启动，
     * 任务抛到 MinecraftServer 主线程执行（修改 ItemStack NBT 必须在主线程）。
     * 调度器本身只负责"到点了发个信号"，主线程内仍串行处理所有玩家。
     * <p>
     * <b>非 final</b>：重进游戏存档时 {@code onServerStopped} 会 shutdown 旧实例，
     * 下一轮 {@code onServerStarted} 触发 {@link #startUrlRefreshScheduler()} 时若继续
     * 在已 terminated 的 executor 上 {@code scheduleAtFixedRate} 会抛
     * {@link java.util.concurrent.RejectedExecutionException}，导致集成服务端崩溃
     * （参见 crash-2026-06-19_20.09.24-server.txt）。本字段允许在启动时重建。
     */
    private ScheduledExecutorService urlRefreshScheduler;

    public NetMusicEchoAddon() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 初始化独立日志系统
        EchoLogger.init();

        // 确保配置目录存在
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            EchoLogger.error("Failed to create config dir: {}", e.getMessage());
        }

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "NETMUSICCANNEEDKUGOU/netmusic-echo-addon-client.toml");

        InitBlocks.init(modEventBus);
        InitItems.init(modEventBus);
        InitContainer.init(modEventBus);

        modEventBus.addListener(this::setup);

        MinecraftForge.EVENT_BUS.register(this);

        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("cloth_config")) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(this::createConfigScreen));
            EchoLogger.info("EchoConfig screen registered with ClothConfig!");
        }

        this.vipScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetMusicEchoAddon-VipRetry");
            t.setDaemon(true);
            return t;
        });
        this.urlRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetMusicEchoAddon-UrlRefresh");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 使用 ClothConfig Builder 创建配置界面
     */
    private Screen createConfigScreen(net.minecraft.client.Minecraft client, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Echo Music Config"))
                .setSavingRunnable(() -> {
                    // 保存 ForgeConfigSpec 配置（ClothConfig 已自动写入）
                    ClientConfig.SPEC.save();
                });

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // ========== 分类 1：酷狗登录 ==========
        ConfigCategory loginCat = builder.getOrCreateCategory(Component.literal("酷狗登录"));

        String loginStatusText = EchoConfig.isLoggedIn()
                ? "✔ 已登录 (UserID: " + EchoConfig.userid + ")"
                : "✘ 未登录";
        loginCat.addEntry(entryBuilder.startTextDescription(Component.literal(loginStatusText))
                .build());

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("扫码登录"), () -> {
            client.setScreen(new EchoLoginScreen(parent));
        }));

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("退出登录"), () -> {
            KuGouLoginApi.logout();
            client.setScreen(createConfigScreen(client, parent));
        }));

        // ========== 分类 2：音乐源 ==========
        ConfigCategory sourceCat = builder.getOrCreateCategory(Component.literal("音乐源"));

        sourceCat.addEntry(entryBuilder.startEnumSelector(
                        Component.literal("音乐源"),
                        ProviderType.class,
                        ClientConfig.getProvider())
                .setDefaultValue(ProviderType.NETEASE)
                .setTooltip(Component.literal("选择音乐数据来源"))
                .setSaveConsumer(ClientConfig::setProvider)
                .build());

        sourceCat.addEntry(entryBuilder.startStrField(Component.literal("VIP Cookie"), ClientConfig.getVipCookie())
                .setDefaultValue("")
                .setTooltip(Component.literal("VIP Cookie 用于解锁付费歌曲（扫码登录后自动填入）"))
                .setSaveConsumer(ClientConfig.VIP_COOKIE::set)
                .build());

        sourceCat.addEntry(entryBuilder.startEnumSelector(
                        Component.literal("播放音质"),
                        AudioQuality.class,
                        ClientConfig.getAudioQuality())
                .setDefaultValue(AudioQuality.HQ)
                .setTooltip(Component.literal("选择歌曲播放音质（128=标准, 320=HQ, flac=无损, super=DSD）"))
                .setSaveConsumer(ClientConfig::setAudioQuality)
                .build());

        // ========== 分类 3：VIP ==========
        ConfigCategory vipCat = builder.getOrCreateCategory(Component.literal("VIP"));

        vipCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("自动领取每日VIP"),
                        ClientConfig.AUTO_RECEIVE_VIP.get())
                .setDefaultValue(false)
                .setTooltip(Component.literal("启动时自动调用概念版接口领取每日VIP"))
                .setSaveConsumer(ClientConfig.AUTO_RECEIVE_VIP::set)
                .build());

        vipCat.addEntry(entryBuilder.startIntSlider(Component.literal("失败后重试间隔(分钟)"),
                        ClientConfig.VIP_RETRY_INTERVAL_MINUTES.get(), 1, 1440)
                .setDefaultValue(10)
                .setTooltip(Component.literal("首次自动领取失败后，每隔该分钟数自动重试一次。\n"
                        + "服务器返回 20002 (今日已领) 后会停止重试直到次日。"))
                .setSaveConsumer(ClientConfig.VIP_RETRY_INTERVAL_MINUTES::set)
                .build());

        // 状态描述
        StringBuilder statusBuilder = new StringBuilder();
        statusBuilder.append("上次状态: ").append(KuGouVipApi.lastClaimStatus);
        if (!KuGouVipApi.lastClaimDate.isEmpty()) {
            statusBuilder.append(" (").append(KuGouVipApi.lastClaimDate).append(")");
        }
        if (KuGouVipApi.lastVipResultMessage != null && !KuGouVipApi.lastVipResultMessage.isEmpty()) {
            statusBuilder.append("\n").append(KuGouVipApi.lastVipResultMessage);
        }
        vipCat.addEntry(entryBuilder.startTextDescription(Component.literal(statusBuilder.toString()))
                .build());

        vipCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("立即领取VIP"), () -> {
            if (!EchoConfig.isLoggedIn()) {
                EchoLogger.warn("Cannot manually claim VIP: not logged in");
                return;
            }
            EchoLogger.info("Manually triggered VIP claim by user");
            triggerAutoReceiveVip();
        }));

        // ========== 分类 4：歌词显示 ==========
        ConfigCategory lyricCat = builder.getOrCreateCategory(Component.literal("歌词显示"));

        // 顶部说明 + ASCII 例图
        lyricCat.addEntry(entryBuilder.startTextDescription(Component.literal(
                "控制歌词翻译 / 罗马音（音译）的显示。\n"
                        + "方块音响、女仆气泡共用这两个开关。\n"
                        + "示例（酷狗歌曲同时有 type=1 翻译 + type=0 罗马音时）：\n"
                        + "\n"
                        + "  原文：    君が代\n"
                        + "  翻译：    君王之治\n"
                        + "  罗马音：  kimi ga yo\n"
                        + "\n"
                        + "  -- 只开'翻译'时：原文 + 翻译 = 2 行\n"
                        + "  -- 只开'罗马音'时：原文 + 罗马音 = 2 行\n"
                        + "  -- 两个都开时：原文 + 翻译 + 罗马音 = 3 行"))
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("显示翻译"),
                        ClientConfig.LYRIC_SHOW_TRANSLATION.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("开启后，酷狗 type=1 的中文翻译会作为第二行显示。\n"
                        + "（仅当歌曲本身有翻译数据时才有效）"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_TRANSLATION::set)
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("显示罗马音 / 音译"),
                        ClientConfig.LYRIC_SHOW_ROMAJI.get())
                .setDefaultValue(false)
                .setTooltip(Component.literal("开启后，酷狗 type=0 的罗马音 / 音译会作为第三行显示。\n"
                        + "（仅当歌曲本身有罗马音数据时才有效）\n"
                        + "如果'翻译'和'罗马音'都开且歌曲两类数据都有，\n"
                        + "歌词区域会显示三行：原文 + 翻译 + 罗马音。"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_ROMAJI::set)
                .build());

        return builder.build();
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            NetworkHandler.init();

            // 从配置文件加载持久化的登录状态
            loadState();

            // 异步注册设备
            KuGouApiClient.ensureDeviceRegistered()
                    .thenAccept(ready -> {
                        EchoLogger.info("Device registration: {}", ready ? "success" : "failed");

                        // 注意：自动领取的"首次触发"已迁移到 onClientLoggingIn（玩家进世界时）。
                        // 这里只启动周期重试调度器，留作兜底（按配置间隔持续轮询）。
                        if (ready && ClientConfig.AUTO_RECEIVE_VIP.get() && EchoConfig.isLoggedIn()) {
                            startVipRetryScheduler();
                        }
                    });

            EchoLogger.info("NetMusic Echo Addon setup complete!");
        });
    }

    /**
     * 客户端进入世界（加入 singleplayer / 多人服）时立即触发一次 VIP 领取。
     * <p>
     * 触发时机：在 {@link net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn}
     * 之后立刻调一次领取 + 升级（与 EchoMusic 的"每次开客户端都问一次 server"思路一致）。
     * <p>
     * 配合 {@link KuGouVipApi#shouldRetryToday()} 避免对服务端刷请求：
     * - 当天已经 SUCCESS → 不调
     * - 当前正在 IN_PROGRESS → 不调
     * - 其他状态 → 调一次
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onClientLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        if (!ClientConfig.AUTO_RECEIVE_VIP.get()) {
            return;
        }
        if (!EchoConfig.isLoggedIn()) {
            return;
        }
        if (!KuGouVipApi.shouldRetryToday()) {
            return;  // 今天已经 SUCCESS 或正在 IN_PROGRESS，跳过
        }
        EchoLogger.info("[NetMusicEchoAddon] Player entered world, triggering VIP auto-claim");
        triggerAutoReceiveVip();
    }

    /**
     * 执行一次 VIP 领取（领取 + 升级）。
     * 首发和周期重试都走这里，避免逻辑分叉。
     * <p>
     * 注：之前想仿照 EchoMusic 调 getServerNow 拿 server 时区日期，但 /v1/server_now
     * 是 POST + AES 加密 body（用 GET 方式酷狗返回 20008 参数无效）。在用户本地时区
     * 已经是 Asia/Shanghai（与 server 端时区一致）的前提下，直接用本地时间即可，
     * 不会出现跨日错位。{@link KuGouVipApi#toBeijingDateString(long)} 仍提供
     * +8h 偏移格式化以保证 UTC 客户端也能用。
     */
    private void triggerAutoReceiveVip() {
        String today = KuGouVipApi.toBeijingDateString(-1L);
        EchoLogger.info("[NetMusicEchoAddon] Auto-receive VIP with date={}", today);
        // ⚠️ 关键：两个接口的 quota 独立！
        //   - receiveDailyVip 失败（含 20002）时仍要试 upgradeVipReward
        //   - upgrade 才是 EchoMusic 能"VIP 状态时续杯"的原因
        KuGouVipApi.receiveDailyVip(EchoConfig.userid, today)
                .thenAccept(receiveResult -> {
                    EchoLogger.info("[NetMusicEchoAddon] receiveDailyVip result: {}", receiveResult);
                    // 不论 receive 成功 / 失败 / 20002，都要调 upgrade（quota 独立）
                    KuGouVipApi.upgradeVipReward(EchoConfig.userid)
                            .thenAccept(upgraded ->
                                    EchoLogger.info("[NetMusicEchoAddon] VIP upgrade: {}",
                                            upgraded ? "success" : "skipped/failed"))
                            .exceptionally(e -> {
                                EchoLogger.error("[NetMusicEchoAddon] upgradeVipReward threw an exception", e);
                                return null;
                            });
                })
                .exceptionally(e -> {
                    EchoLogger.error("[NetMusicEchoAddon] receiveDailyVip threw an exception", e);
                    return null;
                });
    }

    /**
     * 启动周期重试调度器。
     * <p>
     * 行为：每隔 {@link ClientConfig#VIP_RETRY_INTERVAL_MINUTES} 分钟检查一次，
     * 只有当 {@link KuGouVipApi#shouldRetryToday()} 返回 true 时才真正发出请求。
     * 成功 / 服务端说今日已领 → 自动空转；网络抖动 / 其他失败 → 下个周期再试。
     */
    private void startVipRetryScheduler() {
        int minutes = ClientConfig.VIP_RETRY_INTERVAL_MINUTES.get();
        vipScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!FMLEnvironment.dist.isClient()) {
                    return;
                }
                if (!ClientConfig.AUTO_RECEIVE_VIP.get() || !EchoConfig.isLoggedIn()) {
                    return;
                }
                if (KuGouVipApi.lastClaimStatus == KuGouVipApi.ClaimStatus.IN_PROGRESS) {
                    return;
                }
                if (!KuGouVipApi.shouldRetryToday()) {
                    return;
                }
                EchoLogger.info("[NetMusicEchoAddon] Periodic VIP retry (status={}, date={})",
                        KuGouVipApi.lastClaimStatus, KuGouVipApi.lastClaimDate);
                triggerAutoReceiveVip();
            } catch (Throwable t) {
                EchoLogger.error("[NetMusicEchoAddon] Periodic VIP retry crashed", t);
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        // 服务端起来后再启动 URL 续期调度器。
        // 这样在 ServerLifecycleHooks.getCurrentServer() 里能拿到 server。
        startUrlRefreshScheduler();
    }

    /**
     * 玩家加入世界时立即给该玩家跑一次扫描。
     * <p>
     * 这样不用等 {@link ClientConfig#URL_REFRESH_INTERVAL_HOURS} 小时，第一次失败 CD 也能马上被修。
     * 单人档受益最大（联机时也是同等的便利，但专用服没凭证时静默跳过）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
            return;
        }
        // 没有登录态就不要浪费一次 HTTP（专用服 + 没有本地凭证的常见情况）
        if (!EchoConfig.isLoggedIn()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // 放到 server 主线程跑（修改 ItemStack NBT 必须在主线程）
        server.execute(() -> {
            try {
                com.github.tartaricacid.netmusic.echo.support.UrlRefresher refresher =
                        new com.github.tartaricacid.netmusic.echo.support.UrlRefresher();
                int refreshed = refresher.scanPlayer(player);
                if (refreshed > 0) {
                    EchoLogger.info("[UrlRefresh] On-login scan refreshed {} CD(s) for player {}",
                            refreshed, player.getName().getString());
                } else {
                    EchoLogger.info("[UrlRefresh] On-login scan: no expired CD for player {}",
                            player.getName().getString());
                }
            } catch (Throwable t) {
                EchoLogger.error("[UrlRefresh] On-login scan crashed for player {}",
                        player.getName().getString(), t);
            }
        });
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        saveState();
        shutdownVipScheduler();
        shutdownUrlRefreshScheduler();
        EchoLogger.info("NetMusic Echo Addon stopped!");
        EchoLogger.shutdown();
    }

    /**
     * 玩家右键方块事件：用于"放 CD 进唱片机"的瞬间同步检查 URL。
     * <p>
     * 触发时机：在唱片机真正把 CD 拿走之前。在事件内同步改 held CD 的 NBT 即可生效——
     * 唱片机随后把改完的 CD 拿走，播放用的就是新 URL。
     * <p>
     * 设计目标：用户最常听到的"403 播放失败"在放进去那一刻就被消化掉，零感知。
     * <p>
     * 注意：<b>不要</b>cancel 事件，唱片机的标准插入逻辑要继续走。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRightClickJukebox(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        // 只在服务端处理，客户端的右击是预测性的
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
            return;
        }
        if (!EchoConfig.isLoggedIn()) {
            return;
        }
        // 必须是唱片机
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock()
                instanceof net.minecraft.world.level.block.JukeboxBlock)) {
            return;
        }
        // 必须手拿音乐 CD
        ItemStack held = event.getItemStack();
        if (!com.github.tartaricacid.netmusic.echo.support.CdNbtHelper.isMusicCd(held)) {
            return;
        }
        // 必须有 fileHash 记录（老 CD 没有就跳过，不去尝试）
        if (com.github.tartaricacid.netmusic.echo.support.CdNbtHelper.readOriginalInfo(held).isEmpty()) {
            return;
        }
        // 唱片机里已经有唱片了：不替换，让用户自己处理
        // 用 BlockState 的 HAS_RECORD 属性来判断，比 JukeboxBlockEntity 的 getRecord() 在不同映射下更稳
        net.minecraft.world.level.block.state.BlockState jukeboxState =
                event.getLevel().getBlockState(event.getPos());
        if (jukeboxState.hasProperty(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                && jukeboxState.getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)) {
            return;
        }

        // 同步探测 + 续期（HEAD 通常 100-300ms，命中失效才走 getSongUrl，再 1-2s）
        try {
            com.github.tartaricacid.netmusic.echo.support.UrlRefresher refresher =
                    new com.github.tartaricacid.netmusic.echo.support.UrlRefresher();
            boolean refreshed = refresher.tryRefreshOne(held);
            if (refreshed) {
                EchoLogger.info("[UrlRefresh] Refreshed CD on jukebox insert at {} for player {}",
                        event.getPos(), event.getEntity().getName().getString());
            }
        } catch (Throwable t) {
            // 任何异常都吞掉：宁可让播放失败下次再修，也不要因为探测挂了影响唱片机插入
            EchoLogger.warn("[UrlRefresh] On-insert probe failed, letting insert proceed: {}", t.getMessage());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        saveState();
    }

    /**
     * 关闭周期重试调度器。重复调用安全。
     */
    private void shutdownVipScheduler() {
        if (vipScheduler != null && !vipScheduler.isShutdown()) {
            vipScheduler.shutdown();
            try {
                if (!vipScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    vipScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                vipScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 启动 URL 续期调度器。
     * <p>
     * 行为：每隔 {@link ClientConfig#URL_REFRESH_INTERVAL_HOURS} 小时，
     * 把"扫描所有玩家物品栏、检测失效 URL、续期"的任务抛到服务端主线程执行。
     * <p>
     * 之所以抛到主线程：{@code ItemStack} 的 NBT 修改必须在 server tick 内做；
     * 同时 {@code player.getInventory()} 等 API 也要求 server 线程。
     * HTTP 探测会阻塞主线程若干秒，但因为扫描频率低（默认 4 小时）且一般没有失效 CD，
     * 实际影响可以忽略。
     */
    private void startUrlRefreshScheduler() {
        // 修复重进游戏存档崩溃：上一轮 onServerStopped 已把 urlRefreshScheduler shutdown，
        // 这里的 executor 已 Terminated，直接 scheduleAtFixedRate 会抛
        // RejectedExecutionException 炸掉集成服务端。检测到 terminated 就重建。
        if (urlRefreshScheduler.isShutdown()) {
            EchoLogger.info("[NetMusicEchoAddon] UrlRefresh scheduler was terminated (likely world reload); re-creating");
            urlRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NetMusicEchoAddon-UrlRefresh");
                t.setDaemon(true);
                return t;
            });
        }
        int hours = ClientConfig.URL_REFRESH_INTERVAL_HOURS.get();
        urlRefreshScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
                    return;
                }
                net.minecraft.server.MinecraftServer server =
                        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server == null) {
                    return;
                }
                server.execute(() -> {
                    try {
                        com.github.tartaricacid.netmusic.echo.support.UrlRefresher refresher =
                                new com.github.tartaricacid.netmusic.echo.support.UrlRefresher();
                        refresher.scanAll();
                    } catch (Throwable t) {
                        EchoLogger.error("[UrlRefresh] Scan crashed", t);
                    }
                });
            } catch (Throwable t) {
                EchoLogger.error("[UrlRefresh] Scheduler tick crashed", t);
            }
        }, hours, hours, TimeUnit.HOURS);
    }

    /**
     * 关闭 URL 续期调度器。重复调用安全。
     */
    private void shutdownUrlRefreshScheduler() {
        if (urlRefreshScheduler != null && !urlRefreshScheduler.isShutdown()) {
            urlRefreshScheduler.shutdown();
            try {
                if (!urlRefreshScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    urlRefreshScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                urlRefreshScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 从 JSON 文件加载登录状态（运行时凭证）
     * <p>
     * 读取顺序：先尝试新路径 {@link #STATE_FILE}（config/），若不存在再尝试
     * 旧路径 {@link #LEGACY_STATE_FILE}（游戏根目录）。从旧路径读到的内容会
     * 立刻写入新路径并删除旧文件，做到一次性的透明迁移。
     */
    public static void loadState() {
        Path source = null;
        if (Files.exists(STATE_FILE)) {
            source = STATE_FILE;
        } else if (Files.exists(LEGACY_STATE_FILE)) {
            source = LEGACY_STATE_FILE;
            EchoLogger.info("[NetMusicEchoAddon] Legacy state file detected at {}; migrating to {}",
                    LEGACY_STATE_FILE, STATE_FILE);
        }

        if (source == null) {
            return;
        }

        try {
            String json = Files.readString(source);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root.has("token")) EchoConfig.token = root.get("token").getAsString();
            if (root.has("userid")) EchoConfig.userid = root.get("userid").getAsString();
            if (root.has("dfid")) EchoConfig.dfid = root.get("dfid").getAsString();
            if (root.has("mid")) EchoConfig.mid = root.get("mid").getAsString();
            if (root.has("guid")) EchoConfig.guid = root.get("guid").getAsString();
            if (root.has("vipType")) EchoConfig.vipType = root.get("vipType").getAsString();
            if (root.has("vipToken")) EchoConfig.vipToken = root.get("vipToken").getAsString();
            if (root.has("cookies")) {
                JsonObject cookiesObj = root.getAsJsonObject("cookies");
                for (String key : cookiesObj.keySet()) {
                    EchoConfig.addCookie(key, cookiesObj.get(key).getAsString());
                }
            }
            EchoLogger.info("Login state loaded. Logged in: {}", EchoConfig.isLoggedIn());

            // 如果是从旧路径加载的，立刻把内容搬到新路径并删掉旧文件
            if (source == LEGACY_STATE_FILE) {
                try {
                    Path parent = STATE_FILE.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(STATE_FILE, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.deleteIfExists(LEGACY_STATE_FILE);
                    EchoLogger.info("[NetMusicEchoAddon] Legacy state file migrated and removed");
                } catch (IOException migrateEx) {
                    EchoLogger.warn("[NetMusicEchoAddon] Failed to remove legacy state file, will retry next launch: {}",
                            migrateEx.getMessage());
                }
            }
        } catch (Exception e) {
            EchoLogger.error("Failed to load login state", e);
        }
    }

    /**
     * 将运行时登录凭证保存到 JSON 文件
     */
    public static void saveState() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject root = new JsonObject();
            root.addProperty("token", EchoConfig.token != null ? EchoConfig.token : "");
            root.addProperty("userid", EchoConfig.userid != null ? EchoConfig.userid : "");
            root.addProperty("dfid", EchoConfig.dfid != null ? EchoConfig.dfid : "");
            root.addProperty("mid", EchoConfig.mid != null ? EchoConfig.mid : "");
            root.addProperty("guid", EchoConfig.guid != null ? EchoConfig.guid : "");
            root.addProperty("vipType", EchoConfig.vipType != null ? EchoConfig.vipType : "");
            root.addProperty("vipToken", EchoConfig.vipToken != null ? EchoConfig.vipToken : "");

            JsonObject cookiesObj = new JsonObject();
            for (var entry : EchoConfig.cookies.entrySet()) {
                cookiesObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("cookies", cookiesObj);

            Files.writeString(STATE_FILE, GSON.toJson(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            EchoLogger.error("Failed to save login state", e);
        }
    }
}
