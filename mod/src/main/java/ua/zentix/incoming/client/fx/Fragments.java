package ua.zentix.incoming.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Куски блоков, которые сорвала и несёт ударная волна: кувыркающиеся кубики с текстурой своего блока (как частица
 * разрушения блока, только объёмные) летят, падают, бьются о землю и ложатся. Частицы ванильного движка на листе блоков:
 * свет мира, туман и шейдерпак — как у всего в мире, своё — только тело куба. Сколько их живёт сразу — не больше
 * {@link #CAP} (меньше по настройке «Частицы»): лишние не рождаются.
 */
public final class Fragments {
    /** Сколько кусков живёт сразу при «Частицы: все». */
    static final int CAP = 1200;
    /** Самая долгая жизнь куска и самое долгое ожидание вылета, тиков. */
    static final int LIFE_MAX = 110;
    public static final int DELAY_MAX = 40;
    /** Последние столько тиков жизни кусок уменьшается до нуля (ложится и крошится). */
    private static final int SHRINK = 15;
    /**
     * Сколько кусков родилось в каждый из последних тиков (по кругу): живых не больше их суммы. Свой счёт не по смерти
     * куска — движок выбрасывает частицы и без неё (переполнение очереди, смена мира).
     */
    private static final int[] BORN = new int[LIFE_MAX + DELAY_MAX + 1];
    private static int born, tick;

    private Fragments() {}

    /**
     * Кусок блока state (с места from: его цвет травы и листвы) с центром (x, y, z), скоростью (vx, vy, vz) блоков за тик
     * и ребром size блоков; вылетает через delay тиков (до тех пор не виден и стоит).
     *
     * @return false — не родился (воздух, блок без модели, мест нет)
     */
    public static boolean launch(ClientLevel level, BlockState state, BlockPos from, double x, double y, double z, double vx, double vy, double vz, float size,
                                 int delay) {
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || !state.shouldSpawnTerrainParticles()) return false;
        if (born >= cap()) return false;
        BORN[tick]++;
        born++;
        Minecraft.getInstance().particleEngine.add(new Fragment(level, state, from, x, y, z, vx, vy, vz, size, Mth.clamp(delay, 0, DELAY_MAX)));
        return true;
    }

    private static int cap() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return s == ParticleStatus.ALL ? CAP : s == ParticleStatus.DECREASED ? CAP / 2 : CAP / 4;
    }

    /** Тик клиента (не на паузе): тик, ушедший за самую долгую жизнь куска, — из счёта. */
    public static void tick() {
        tick = (tick + 1) % BORN.length;
        born -= BORN[tick];
        BORN[tick] = 0;
    }

    /** Выход из мира: движок сам убирает частицы. */
    public static void reset() {
        Arrays.fill(BORN, 0);
        born = 0;
    }

    /** Кусок: куб ребром size, повёрнутый вокруг своей оси, с текстурой блока на гранях. */
    private static final class Fragment extends Particle {
        /** Оси куба по граням: нормаль, первая и вторая оси грани (номер оси куба), знак нормали; a × b = нормаль. */
        private static final int[][] FACES = {{0, 1, 2, 1}, {0, 2, 1, -1}, {1, 2, 0, 1}, {1, 0, 2, -1}, {2, 0, 1, 1}, {2, 1, 0, -1}};
        private static final Quaternionf TURN = new Quaternionf();
        private static final Vector3f[] AXES = {new Vector3f(), new Vector3f(), new Vector3f()};

        private final float half, u0, v0, u1, v1;
        private final float ax, ay, az;
        private float angle, angleO, spin;
        private int delay;

        Fragment(ClientLevel level, BlockState state, BlockPos from, double x, double y, double z, double vx, double vy, double vz, float size, int delay) {
            // место частицы — низ её рамки
            super(level, x, y - size * 0.5, z);
            this.delay = delay;
            xd = vx;
            yd = vy;
            zd = vz;
            half = size * 0.5f;
            setSize(size, size);
            gravity = 1;
            // сопротивление воздуха: кусок, брошенный волной, быстро теряет скорость
            friction = 0.96f;
            lifetime = LIFE_MAX - 40 + random.nextInt(41);
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getTexture(state, level, from);
            // на грани — кусок текстуры размером с ребро (16 пикселей на блок)
            float span = Mth.clamp(size, 0.125f, 1);
            float ou = random.nextFloat() * (1 - span), ov = random.nextFloat() * (1 - span);
            u0 = sprite.getU(ou);
            u1 = sprite.getU(ou + span);
            v0 = sprite.getV(ov);
            v1 = sprite.getV(ov + span);
            if (IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, from)) {
                int c = Minecraft.getInstance().getBlockColors().getColor(state, level, from, 0);
                rCol = (c >> 16 & 0xFF) / 255f;
                gCol = (c >> 8 & 0xFF) / 255f;
                bCol = (c & 0xFF) / 255f;
            }
            Vector3f axis = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f);
            if (axis.lengthSquared() < 1e-4f) axis.set(0, 1, 0);
            axis.normalize();
            ax = axis.x;
            ay = axis.y;
            az = axis.z;
            angle = angleO = random.nextFloat() * Mth.TWO_PI;
            // кувыркается тем быстрее, чем быстрее летит
            spin = (float) Math.min(0.8, 0.1 + 0.25 * Math.sqrt(vx * vx + vy * vy + vz * vz)) * (random.nextBoolean() ? 1 : -1);
        }

        @Override
        public void tick() {
            if (delay > 0) {
                delay--;
                return;
            }
            super.tick();
            angleO = angle;
            angle += spin;
            if (onGround) spin *= 0.5f;
        }

        @Override
        public void render(VertexConsumer buffer, Camera camera, float partial) {
            if (delay > 0) return;
            float shrink = Mth.clamp((lifetime - age - partial) / SHRINK, 0, 1);
            if (shrink <= 0) return;
            Vec3 cam = camera.getPosition();
            float px = (float) (Mth.lerp(partial, xo, x) - cam.x), py = (float) (Mth.lerp(partial, yo, y) - cam.y + half),
                    pz = (float) (Mth.lerp(partial, zo, z) - cam.z);
            float h = half * shrink;
            TURN.identity().rotateAxis(Mth.lerp(partial, angleO, angle), ax, ay, az);
            for (int i = 0; i < 3; i++) TURN.transform(AXES[i].set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0));
            int light = getLightColor(partial);
            for (int[] f : FACES) {
                Vector3f n = AXES[f[0]], a = AXES[f[1]], b = AXES[f[2]];
                float s = f[3];
                float nx = n.x * s, ny = n.y * s, nz = n.z * s;
                // тень граней, как у блоков: верх светлее, низ темнее, бока между
                float shade = nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1 : 0.5f);
                float r = rCol * shade, g = gCol * shade, bl = bCol * shade;
                float cx = px + nx * h, cy = py + ny * h, cz = pz + nz * h;
                vertex(buffer, cx - (a.x + b.x) * h, cy - (a.y + b.y) * h, cz - (a.z + b.z) * h, u0, v1, r, g, bl, light);
                vertex(buffer, cx + (a.x - b.x) * h, cy + (a.y - b.y) * h, cz + (a.z - b.z) * h, u1, v1, r, g, bl, light);
                vertex(buffer, cx + (a.x + b.x) * h, cy + (a.y + b.y) * h, cz + (a.z + b.z) * h, u1, v0, r, g, bl, light);
                vertex(buffer, cx - (a.x - b.x) * h, cy - (a.y - b.y) * h, cz - (a.z - b.z) * h, u0, v0, r, g, bl, light);
            }
        }

        private static void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, float r, float g, float b, int light) {
            buffer.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, 1).setLight(light);
        }

        @Override
        protected int getLightColor(float partial) {
            BlockPos pos = BlockPos.containing(x, y + half, z);
            return level.isLoaded(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.TERRAIN_SHEET;
        }
    }
}
