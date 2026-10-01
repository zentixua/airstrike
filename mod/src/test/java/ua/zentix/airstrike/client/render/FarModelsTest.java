package ua.zentix.airstrike.client.render;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Модели вдали: плитка атласа по углу модели, модель целиком в шаре плитки, упрощённые копии деталей. */
class FarModelsTest {
    /** Детали на шарнирах сбоку от оси (крылья и воздухозаборник ракеты, створки B-2): поворот уводит их от центра. */
    private static final Set<WeaponModels.Mesh> HINGED = Set.of(WeaponModels.Mesh.MISSILE_WING_L, WeaponModels.Mesh.MISSILE_WING_R,
            WeaponModels.Mesh.MISSILE_INTAKE, WeaponModels.Mesh.BOMBER_DOOR_L_IN, WeaponModels.Mesh.BOMBER_DOOR_L_OUT,
            WeaponModels.Mesh.BOMBER_DOOR_R_IN, WeaponModels.Mesh.BOMBER_DOOR_R_OUT);

    private record Obj(List<double[]> vertices, int faces) {}

    private static Obj obj(String file) {
        try (InputStream in = FarModelsTest.class.getResourceAsStream("/assets/airstrike/models/weapon/" + file + ".obj")) {
            if (in == null) return null;
            List<double[]> v = new ArrayList<>();
            int faces = 0;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String[] s = line.trim().split("\\s+");
                if (s[0].equals("v")) v.add(new double[]{Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3])});
                else if (s[0].equals("f")) faces++;
            }
            return new Obj(v, faces);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Размер, которым меряется вид снаряда вдали ({@code FarLook.size}), — у снаряда, которому принадлежит деталь. */
    private static double size(WeaponModels.Mesh m) {
        String n = m.name();
        ClientWeaponSpec.ClientAirframe a;
        if (n.startsWith("DRONE_")) a = ClientWeaponSpec.of(WeaponType.DRONE).airframe(false);
        else if (n.startsWith("MISSILE_")) a = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false);
        else if (n.startsWith("BOMBER_")) a = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false);
        else if (n.startsWith("BOMB_")) a = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(true);
        else if (n.startsWith("ICBM_")) a = ClientWeaponSpec.of(WeaponType.NUKE).airframe(false);
        else if (n.startsWith("ROCKET_")) a = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false);
        else a = ClientWeaponSpec.of(WeaponType.LOITER).airframe(false);
        return a.far().size();
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Test
    void everyPoseFitsTheTileSphere() {
        for (WeaponModels.Mesh m : WeaponModels.Mesh.values()) {
            if (m == WeaponModels.Mesh.ROCKET_RACK) continue; // пакет РСЗО — пусковая, не снаряд вдали
            Obj o = obj(m.file);
            assertNotNull(o, m.file);
            double reach = 0;
            if (HINGED.contains(m)) {
                // шарнир — на краю детали: повёрнутая точка не дальше от центра, чем дальний угол её коробки плюс диагональ
                double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
                for (double[] v : o.vertices()) {
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], v[i]);
                        hi[i] = Math.max(hi[i], v[i]);
                    }
                }
                double corner = length(Math.max(-lo[0], hi[0]), Math.max(-lo[1], hi[1]), Math.max(-lo[2], hi[2]));
                reach = corner + length(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]);
            } else {
                // винты и диски вращаются вокруг оси Z через центр, крылья «Ланцета» только сжимаются: дальше точек сетки не уходят
                for (double[] v : o.vertices()) reach = Math.max(reach, length(v[0], v[1], v[2]));
            }
            double radius = FarModels.TILE_RADIUS * size(m);
            assertTrue(reach <= radius, m + ": " + reach + " за шаром плитки " + radius);
        }
    }

    @Test
    void coarseCopiesKeepPointsOfTheFullMeshAndAreCheaper() {
        int full = 0, coarse = 0;
        for (WeaponModels.Mesh m : WeaponModels.Mesh.values()) {
            Obj o = obj(m.file), c = obj(m.file + "_lod1");
            if (!m.lod) {
                assertNull(c, m.file + "_lod1 лежит, но деталь его не берёт");
                continue;
            }
            assertNotNull(c, m.file + "_lod1");
            assertNotNull(FarModelsTest.class.getResource("/assets/airstrike/models/weapon/" + m.file + "_lod1.json"), m.file + "_lod1.json");
            assertTrue(c.faces() <= o.faces(), m.file);
            // упрощение убирает ряды сетки, а не двигает точки: силуэт не толстеет и не худеет
            for (double[] p : c.vertices()) {
                boolean found = false;
                for (double[] q : o.vertices()) {
                    if (Math.abs(p[0] - q[0]) < 1e-4 && Math.abs(p[1] - q[1]) < 1e-4 && Math.abs(p[2] - q[2]) < 1e-4) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found, m.file + "_lod1: точки (" + p[0] + ", " + p[1] + ", " + p[2] + ") нет в полной сетке");
            }
            full += o.faces();
            coarse += c.faces();
        }
        assertTrue(coarse * 3 < full, "упрощённые копии — " + coarse + " граней из " + full);
    }

    @Test
    void tileHasFourTexelsPerScreenPixel() {
        double pixel = Math.toRadians(70) / 720;
        // модель на 10 пикселей экрана — плитка в 40 текселей
        assertEquals(40, FarModels.tileSize(5 * pixel, pixel));
        for (double px = 0.1; px < 400; px *= 1.07) {
            int side = FarModels.tileSize(px / 2 * pixel, pixel);
            assertEquals(0, side % 4, "сторона кратна 4: мип-уровень 2 не делит тексель между плитками");
            assertTrue(side >= FarModels.MIN_TILE && side <= FarModels.MAX_TILE);
            if (side > FarModels.MIN_TILE && side < FarModels.MAX_TILE) {
                assertTrue(side >= FarModels.SUPERSAMPLE * px && side < FarModels.SUPERSAMPLE * px + 5, px + " px: " + side);
            }
        }
        // кайма — целые мип-тексели, четыре самые крупные плитки с каймой входят в атлас
        assertEquals(0, FarModels.PAD % 4);
        assertTrue(2 * (FarModels.MAX_TILE + 2 * FarModels.PAD) <= FarModels.ATLAS);
    }
}
