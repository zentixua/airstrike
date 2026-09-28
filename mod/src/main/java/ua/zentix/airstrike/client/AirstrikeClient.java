package ua.zentix.airstrike.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.aim.Designator;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.fx.BlastEffects;
import ua.zentix.airstrike.client.fx.CameraShake;
import ua.zentix.airstrike.client.fx.Effects;
import ua.zentix.airstrike.client.fx.Exhaust;
import ua.zentix.airstrike.client.fx.Flash;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.hud.Alerts;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.StrikesHud;
import ua.zentix.airstrike.client.nuclear.ClientNuclear;
import ua.zentix.airstrike.client.nuclear.Geiger;
import ua.zentix.airstrike.client.nuclear.NukeArming;
import ua.zentix.airstrike.client.nuclear.NukeFlash;
import ua.zentix.airstrike.client.nuclear.NukeHud;
import ua.zentix.airstrike.client.nuclear.NukeRenderer;
import ua.zentix.airstrike.client.nuclear.NukeSky;
import ua.zentix.airstrike.client.render.DebrisRenderer;
import ua.zentix.airstrike.client.render.LauncherRenderer;
import ua.zentix.airstrike.client.render.SpentBoosterRenderer;
import ua.zentix.airstrike.client.render.StrikeProjectileRenderer;
import ua.zentix.airstrike.client.render.WeaponModels;
import ua.zentix.airstrike.client.screen.RemoteScreen;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.client.sound.SoundFilters;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.ClientHooks;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModEntities;

/** Клиентская половина мода: модели, звук, эффекты, камера, HUD, пульт. На выделенном сервере не загружается. */
@Mod(value = Airstrike.MOD_ID, dist = Dist.CLIENT)
public final class AirstrikeClient {
    public AirstrikeClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(AirstrikeClient::renderers);
        modBus.addListener(WeaponModels::register);
        modBus.addListener(AirstrikeClient::keys);
        modBus.addListener(AirstrikeClient::layers);
        modBus.addListener(SoundFilters::onEngineLoad);
        modBus.addListener(Fx::registerProviders);

        NeoForge.EVENT_BUS.addListener(AirstrikeClient::tick);
        NeoForge.EVENT_BUS.addListener(CameraShake::apply);
        NeoForge.EVENT_BUS.addListener(Designator::fov);
        NeoForge.EVENT_BUS.addListener(Designator::turn);
        NeoForge.EVENT_BUS.addListener(Designator::renderWorld);
        NeoForge.EVENT_BUS.addListener(Designator::hideHand);
        NeoForge.EVENT_BUS.addListener(AirstrikeClient::scroll);
        NeoForge.EVENT_BUS.addListener(AirstrikeClient::hideCrosshair);
        NeoForge.EVENT_BUS.addListener(AirstrikeClient::logout);
        NeoForge.EVENT_BUS.addListener(ClientFlights::onJoin);
        NeoForge.EVENT_BUS.addListener(ClientFlights::onLeave);
        NeoForge.EVENT_BUS.addListener(NukeRenderer::render);
        NeoForge.EVENT_BUS.addListener(Fx::afterParticles);
        NeoForge.EVENT_BUS.addListener(NukeSky::fogColor);
        NeoForge.EVENT_BUS.addListener(NukeSky::fog);
        NeoForge.EVENT_BUS.addListener(SoundFilters::onSound);
        NeoForge.EVENT_BUS.addListener(SoundFilters::onStream);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::angles);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::fov);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::movement);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::hideHand);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::click);

        ClientHooks.set(new Hooks());
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.DRONE.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::drone));
        e.registerEntityRenderer(ModEntities.CRUISE_MISSILE.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::missile));
        e.registerEntityRenderer(ModEntities.ROCKET.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::rocket));
        e.registerEntityRenderer(ModEntities.LOITER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::loiter));
        e.registerEntityRenderer(ModEntities.BOMBER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::bomber));
        e.registerEntityRenderer(ModEntities.BUNKER_BUSTER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::bomb));
        e.registerEntityRenderer(ModEntities.ICBM.get(), ctx -> new StrikeProjectileRenderer<>(ctx, WeaponModels::icbm));
        e.registerEntityRenderer(ModEntities.DEBRIS.get(), DebrisRenderer::new);
        e.registerEntityRenderer(ModEntities.LAUNCHER.get(), LauncherRenderer::new);
        e.registerEntityRenderer(ModEntities.SPENT_BOOSTER.get(), SpentBoosterRenderer::new);
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.register(Keys.FIRE);
        e.register(Keys.MENU);
        e.register(Keys.CAMERA);
    }

    private static void layers(RegisterGuiLayersEvent e) {
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Airstrike.id("flash"), Flash::render);
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Airstrike.id("nuke_flash"), NukeFlash::render);
        e.registerAbove(VanillaGuiLayers.CROSSHAIR, Airstrike.id("scope"), Designator::renderHud);
        e.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, Airstrike.id("alerts"), Alerts::render);
        e.registerAbove(VanillaGuiLayers.SCOREBOARD_SIDEBAR, Airstrike.id("strikes"), StrikesHud::render);
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Airstrike.id("projectile_camera"), ProjectileCamera::render);
        e.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, Airstrike.id("nuke"), NukeHud::render);
        e.registerAbove(VanillaGuiLayers.HOTBAR, Airstrike.id("geiger"), Geiger::render);
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        while (Keys.MENU.consumeClick()) mc.setScreen(new RemoteScreen());
        while (Keys.FIRE.consumeClick()) Designator.fire();
        while (Keys.CAMERA.consumeClick()) ProjectileCamera.cycle();
        if (mc.isPaused()) return;
        Designator.tick();
        ClientFlights.tick();
        ProjectileCamera.tick();
        ClientSounds.tick();
        Effects.tick();
        CameraShake.tick();
        Flash.tick();
        Alerts.tick();
        NukeArming.tick();
        ClientNuclear.tick();
    }

    /** Колесо мыши в бинокле — выбор оружия, а не слота хотбара. */
    private static void scroll(InputEvent.MouseScrollingEvent e) {
        if (Designator.isScoping() && e.getScrollDeltaY() != 0) {
            Designator.scroll(e.getScrollDeltaY());
            e.setCanceled(true);
        }
    }

    /** В бинокле своё перекрестие. */
    private static void hideCrosshair(RenderGuiLayerEvent.Pre e) {
        if (e.getName().equals(VanillaGuiLayers.CROSSHAIR) && (Designator.isScoping() || ProjectileCamera.isActive())) e.setCanceled(true);
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientSounds.reset();
        Effects.clear();
        CameraShake.reset();
        Flash.reset();
        Alerts.reset();
        ProjectileCamera.reset();
        ClientFlights.reset();
        NukeArming.cancel();
        ClientNuclear.reset();
    }

    private static final class Hooks implements ClientHooks {
        @Override
        public void blast(S2C.Blast p) {
            BlastEffects.blast(p);
        }

        @Override
        public void bunkerImpact(S2C.BunkerImpact p) {
            BlastEffects.bunkerImpact(p);
        }

        @Override
        public void vent(S2C.Vent p) {
            BlastEffects.vent(p);
        }

        @Override
        public void collapse(S2C.Collapse p) {
            BlastEffects.collapse(p);
        }

        @Override
        public void quake(S2C.Quake p) {
            CameraShake.quake(p.ticks());
            if (p.rumble()) ua.zentix.airstrike.client.sound.BlastSounds.quake();
        }

        @Override
        public void siren(S2C.Siren p) {
            Alerts.siren(p);
        }

        @Override
        public void salvoStatus(S2C.SalvoStatus p) {
            Alerts.salvo(p);
        }

        @Override
        public void openRemote() {
            Minecraft.getInstance().setScreen(new RemoteScreen());
        }

        @Override
        public void cleared() {
            ClientSounds.reset();
            Alerts.reset();
            ProjectileCamera.reset();
        }

        @Override
        public void nukeWarning(S2C.NukeWarning p) {
            ClientNuclear.warning(p);
        }

        @Override
        public void nukeDetonation(S2C.NukeDetonation p) {
            ClientNuclear.detonation(p);
        }

        @Override
        public void nukeSync(S2C.NukeSync p) {
            ClientNuclear.sync(p);
        }

        @Override
        public void radiation(S2C.Radiation p) {
            ClientNuclear.radiation(p);
        }

        @Override
        public void projectileTick(StrikeProjectile e) {
            Exhaust.tick(e);
        }

        @Override
        public void debrisTick(DebrisEntity e) {
            Exhaust.debris(e);
        }

        @Override
        public void flights(S2C.Flights p) {
            ClientFlights.update(p);
        }
    }
}
