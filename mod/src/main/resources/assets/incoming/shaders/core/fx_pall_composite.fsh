#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>

// Пелена — на кадр (client/fx/layer/PallPass): смешивание ONE, SRC_ALPHA — свет пелены плюс мир за ней, ослабленный
// и в тени пелены и грибов. Кадр пелены мельче полного: из четырёх ближних его пикселей — с весом по сходству расстояния
// до мира (у края дома пелена неба не ложится на стену и наоборот). Тень — здесь, в каждом пикселе: пелены — по полю без
// шума, грибов — по карте тени (mushroom.glsl, fx_mush_shadow); в кадре пелены альфа — только ослабление (его берут
// квадраты слоя, fx.fsh), а тень на земле — не их. Пламя горящего города на поверхностях (огненный шторм,
// client/nuclear/FireStorm) — тоже здесь: по расстоянию до мира за пикселем (и на LOD Distant Horizons), за пеленой —
// ослабленное ею. И свет огненных шаров на мире (днём — первые секунды, ночью — дольше): он делает мир ярче, а смешивание
// умеет только ослаблять, поэтому, пока шар светит, проход берёт копию кадра (Sampler9) и пишет пиксель мира целиком.

// накопленная пелена (fx_pall_resolve)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;
// таблица поля (PallField.row)
uniform sampler2D Sampler2;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
// направление на солнце (xyz) и сила солнца днём 0..1 (w); свет солнца и неба, линейный (rgb), как в fx_pall
uniform vec4 PallSun;
uniform vec3 PallSunLight;
uniform vec3 PallSkyLight;
// z: во сколько раз кадр пелены мельче полного
uniform vec4 PallFrame;
// огненный шторм: карты огня слотов, слоты огня (pall.glsl), шум для пятен и мерцания (свой блок текстуры — PallPass)
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
uniform sampler3D PallNoise;
// рельеф и вода под пеленой слотов (pall.glsl: pallFloor, pallWater), вода слотов — как в fx_pall
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform float PallFloorFrom;
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// грибы кадра (MushroomField) и их карта тени от солнца (fx_mush_shadow)
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
// x — 3.912 / дальность видимости, 1/блок; y, z — начало и конец тумана Minecraft; w — 1, если туман цилиндром (как в fx_water)
uniform vec4 Haze;
// огненные шары, что светят на мир (MushroomField.balls): столбец i — середина шапки от глаза и радиус (w; 0 — шара нет),
// свет у её поверхности на белой стене с привыканием глаза (rgb, линейный) и ослабление воздуха, 1/блок (w); копия кадра
// до этого прохода — пока хоть один светит; свет открытого неба на белом блоке, как его рисует Minecraft (на экране)
uniform mat4 PallBalls;
uniform mat4 PallBallLight;
uniform sampler2D Sampler9;
uniform vec3 PallOpenSky;

// как в fx_pall: яркость по каналам; ярче белого (по яркости) — эта доля превышения белым во все каналы
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;

out vec4 fragColor;

// Пелена над точкой p (от глаза) — как плоский слой: толща (x), альбедо (y) и асимметрия (z) по зелёному каналу, альбедо
// земли под ней (w: суша или вода). Вещество — по толще пыли (с дымом), мглы и водяной пыли
vec4 pallOver(vec3 p) {
    float tau = 0.0, w = 0.0, g = 0.0, water = 0.0;
    if (PallSlot0[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler5, PallSlot0, PallGround0, PallWater0, p, 0.0);
        tau += f.column.x + f.column.y + f.column.z;
        w += f.column.x * mix(PALL_DUST_W.g, PALL_SMOKE_W.g, f.smoke) + f.column.y * PALL_HAZE_W.g + f.column.z * PALL_MIST_W.g;
        g += (f.column.x + f.column.y) * PALL_DUST_G + f.column.z * PALL_MIST_G;
        water += (f.column.x + f.column.y) * f.wet + f.column.z;
    }
    if (PallSlot1[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler6, PallSlot1, PallGround1, PallWater1, p, 0.0);
        tau += f.column.x + f.column.y + f.column.z;
        w += f.column.x * mix(PALL_DUST_W.g, PALL_SMOKE_W.g, f.smoke) + f.column.y * PALL_HAZE_W.g + f.column.z * PALL_MIST_W.g;
        g += (f.column.x + f.column.y) * PALL_DUST_G + f.column.z * PALL_MIST_G;
        water += (f.column.x + f.column.y) * f.wet + f.column.z;
    }
    float n = max(tau, 1e-6);
    return vec4(tau, w / n, g / n, mix(PALL_LAND, PALL_SEA, clamp(water / n, 0.0, 1.0)));
}

// Тень пелены и грибов на освещённом солнцем мире в точке p (от глаза): во сколько раз они над ней меняют свет на земле
// против солнца и неба без них. Грибы — высоко над пеленой, свет проходит их первыми: карта тени даёт долю света солнца,
// что они пропускают к точке, — прямой пучок сквозь их толщу и рассеянный ими вперёд с той долей, с которой облако
// видно с земли (fx_mush_shadow; рассеянное вперёд приходит с неба у солнца — дальше оно идёт как пучок). Пелена — у
// низа слоя: толща над точкой — весь слой, прямое солнце сквозь столб над ней и рассеянный пеленой свет солнца и неба
// вниз (два потока, как в fx_pall: pallDiffuse, с отражением от земли и обратно от пелены). Небо, закрытое шапкой, не
// убавлено: даже под шапкой она закрывает меньше десятой доли неба по его свету на землю (у 15 кт R²/(R² + H²) ≈ 0,07).
// Один канал (зелёный): тень — множитель альфы. Пелена над точкой — over (pallOver). Свет линейный, мир кадра — значения
// экрана: на экране тень — её степень 1/γ (pallDisplay; мир в тени в k раз темнее — на экране в k^(1/γ)).
float shade(vec3 p, vec4 over) {
    if (PallSun.w <= 0.0) return 1.0;
    float mu = max(PallSun.y, 0.12), sun = PallSunLight.g, sky = PallSkyLight.g;
    float beam = sun * mushShadow(Sampler7, Sampler8, mushShadowSun(PallSun.xyz), p), skyIn = sky;
    float down = mu * beam + skyIn;
    if (over.x > 1e-5) {
        down = mu * beam * exp(-over.x / mu) + pallDiffuse(over.y, over.z, over.x, over.x, over.w, mu, beam, 1.0, 0.0, skyIn, 0.0, -0.6666667);
    }
    return mix(1.0, clamp(down / max(mu * sun + sky, 1e-4), 0.0, 1.0), PallSun.w);
}

// Свет огненных шаров на мире в точке p (от глаза) с нормалью n, линейный, на белой стене (как PallSunLight): ламбертов шар
// (MushroomModel.light) светит на грань к нему как L·R²/max(d², R²) по косинусу к ней, гаснет в воздухе по пути; сквозь
// пелену над точкой (over) — прямой пучок e^(−τ/μ) и рассеянный ею вниз (два потока, как солнце в shade), на грань — по
// доле неба, что она видит, (1 + n.y)/2. Теней рельефа и построек нет: за холмом и домом шар светит так же (тёмен только
// склон, отвёрнутый от него).
vec3 ballLight(vec3 p, vec3 n, vec4 over) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 b = PallBalls[i];
        if (b.w <= 0.0) continue;
        vec3 l = b.xyz - p;
        float d2 = dot(l, l), d = sqrt(d2);
        vec3 v = l / max(d, 1e-3);
        float e = mushBallFalloff(d2, b.w * b.w) * exp(-PallBallLight[i].w * d);
        float face = max(dot(n, v), 0.0), mu = max(v.y, 0.12);
        if (over.x > 1e-5) {
            face = face * exp(-over.x / mu) + max(pallDiffuse(over.y, over.z, over.x, over.x, over.w, mu, 1.0, 1.0, 0.0, 0.0, 0.0, -0.6666667), 0.0)
                    * 0.5 * (1.0 + n.y);
        }
        sum += PallBallLight[i].rgb * (e * face);
    }
    return sum;
}

// Место мира за пикселем at (от глаза): Sampler1 — расстояние вдоль взгляда
vec3 sceneAt(ivec2 at, ivec2 size) {
    ivec2 c = clamp(at, ivec2(0), size - 1);
    vec2 ndc = (vec2(c) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    return dir * (pallScene(texelFetch(Sampler1, c, 0).r) / max(dot(dir, PallForward), 1e-3));
}

// Нормаль поверхности мира в p (пиксель at) по соседним пикселям, к глазу: на каждой оси экрана — сосед, что ближе по
// месту (на краю предмета другой — фон за ним или другой предмет)
vec3 sceneNormal(ivec2 at, ivec2 size, vec3 p) {
    vec3 r = sceneAt(at + ivec2(1, 0), size) - p, l = p - sceneAt(at - ivec2(1, 0), size);
    vec3 u = sceneAt(at + ivec2(0, 1), size) - p, d = p - sceneAt(at - ivec2(0, 1), size);
    vec3 n = cross(mix(r, l, step(dot(l, l), dot(r, r))), mix(u, d, step(dot(d, d), dot(u, u))));
    n *= inversesqrt(max(dot(n, n), 1e-20));
    return mix(n, -n, step(0.0, dot(n, p)));
}

// Один канал пикселя мира под светом шаров (MushroomField.relit): мир на экране world, доля его прежнего света на экране
// kept (тень), свет открытого неба на белом блоке на экране open, свет шаров на белой стене added (линейный). Цвет блока —
// пиксель, делённый на свет открытого неба (в пещере и доме свет Minecraft слабее — и свет шара на нём меньше: туда он и
// не доходит), не белее белого. Линейный итог
float relit(float world, float kept, float open, float added) {
    float lin = pallLinear(world);
    return lin * pallLinear(kept) + min(lin / max(pallLinear(open), 1e-4), 1.0) * added;
}

// Пламя горящего города на поверхности в точке p (от глаза), dist — до неё, pixel — ширина пикселя там (блоки): где
// горит — пятна по шуму (дома и дворы) с силой клетки карты, мерцает; мельче пикселя — средняя доля пятен (вдали не
// рябит). Ближе FLAME_NEAR (F[2].w) нет, до полутора — проступает: там пятно краски на блоках видно, а горит настоящий
// огонь ванили. И ровный отсвет пожаров на всём вокруг (ночью горящий город рыжий).
vec3 flames(sampler2D map, mat4 S, mat4 F, vec3 p, float dist, float pixel) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    // облака в расстоянии до мира тоже есть: на них не горит
    if (h < -80.0 || h > 200.0 || p.y > F[3].w - 6.0) return vec3(0.0);
    vec2 uv = fireUv(S, F, p.xz);
    float r = textureLod(map, uv, 0.0).r, around = textureLod(map, uv, 2.5).r;
    if (r + around <= 1e-3) return vec3(0.0);
    vec2 rel = p.xz - S[0].xy;
    float t = F[2].z;
    float spots = smoothstep(0.52, 0.72, texture(PallNoise, vec3(rel.x / 70.0, p.y / 90.0, rel.y / 70.0)).r);
    spots = mix(spots, 0.3, smoothstep(4.0, 16.0, pixel));
    float flicker = 0.55 + 0.9 * texture(PallNoise, vec3(rel.x / 25.0, t * 0.4, rel.y / 25.0)).g;
    float near = smoothstep(F[2].w, 1.5 * F[2].w, dist);
    float e = F[2].x * min(r, 1.5) * spots * flicker;
    vec3 hot = mix(F[3].rgb, vec3(1.0, 0.72, 0.36), clamp(e - 0.6, 0.0, 0.5));
    return near * (hot * e + F[3].rgb * (0.12 * F[1].w * around));
}

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    float depth = pallScene(texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r);
    vec2 at = gl_FragCoord.xy / PallFrame.z - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            ivec2 tf = clamp(ivec2((vec2(t) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
            float d = pallScene(texelFetch(Sampler1, tf, 0).r);
            float rel = abs(d - depth) / max(min(d, depth), 0.1);
            // билинейный вес без «?:» внутри выражения: Veil теряет скобки вокруг него (ShaderSourceTest)
            float w = mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j));
            w *= 1.0 / (1.0 + 400.0 * rel * rel) + 1e-4;
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    vec4 pall = sum / total;
    if (PallFire0[0].x > 0.0 || PallFire1[0].x > 0.0) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0), nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 / float(fullSize.y)), 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        float dist = depth / max(dot(dir, PallForward), 1e-3);
        if (depth >= 1e8) {
            // мира за пикселем нет (дальше прорисовки без Distant Horizons, за краем его LOD): луч кончается на рельефе
            // под пеленой, как в fx_pall, — горящий город за краем прорисовки виден огнями на нём
            dist = min(pallFloor(Sampler5, PallSlot0, PallGround0, dir, PallFloorFrom, 1e6, 16, 0.5),
                    pallFloor(Sampler6, PallSlot1, PallGround1, dir, PallFloorFrom, 1e6, 16, 0.5));
            if (dist >= 1e8) dist = -1.0;
        }
        if (dist > 0.0) {
            float pixel = dist * length(normalize(nextPoint.xyz / nextPoint.w) - dir);
            vec3 p = dir * dist;
            // пламя светит само: за пеленой — ослабленное ею, тень пелены его не трогает
            pall.rgb += (flames(Sampler3, PallSlot0, PallFire0, p, dist, pixel) + flames(Sampler4, PallSlot1, PallFire1, p, dist, pixel)) * pall.a;
        }
    }
    // только на мире: пустоту под горизонтом тень красила бы пятнами
    bool balls = PallBalls[0].w > 0.0;
    if (depth < 1e8 && (PallSun.w > 0.0 || balls) && pall.a > 0.005) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        vec3 p = dir * (depth / max(dot(dir, PallForward), 1e-3));
        // тень и свет шаров — на свете самого мира, а дымка воздуха и туман Minecraft перед ним — свет воздуха на всём пути
        // до края, не в тени: тень тает с ними (как наклейка на воду, fx_water), иначе у края прорисовки туман в тени —
        // серая полоса
        float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
        float seen = exp(-Haze.x * length(p)) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
        vec4 over = pallOver(p);
        float kept = mix(1.0, pallDisplay(shade(p, over)), seen);
        vec3 added = vec3(0.0);
        if (balls) added = ballLight(p, sceneNormal(full, fullSize, p), over) * seen;
        if (dot(added, LUMA) > 1e-6) {
            // мир ярче: пиксель целиком — пелена и мир за ней в свете шаров (альфа 0 — смешивание кадр не берёт); ярче
            // белого — к белому (CROSS, как свет вещества в fx_pall), не ярче 1 с тем же оттенком
            vec3 world = texelFetch(Sampler9, full, 0).rgb;
            vec3 lit = vec3(relit(world.r, kept, PallOpenSky.r, added.r), relit(world.g, kept, PallOpenSky.g, added.g),
                    relit(world.b, kept, PallOpenSky.b, added.b));
            lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
            vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
            shown /= max(max(max(shown.r, shown.g), shown.b), 1.0);
            pall = vec4(pall.rgb + pall.a * shown, 0.0);
        } else {
            pall.a *= kept;
        }
    }
    // накопленная пелена годна всегда (fx_pall_resolve), но мир кадра после неё не должен пострадать ни при каком входе
    fragColor = pallValid(pall) ? pall : vec4(0.0, 0.0, 0.0, 1.0);
}
