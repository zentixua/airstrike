// Пелена после ударной волны (client/nuclear/PallField — законы, client/fx/layer/PallPass — проходы): поле одного
// подрыва по таблице радиуса. Общее у объёма (fx_pall) и у квадратов слоя эффектов (fx.vsh: что пелена закрывает
// перед частицей). Числа и законы — копии PallField и VolumeNoise.shape (PallGlslTest считает эти функции и сверяет с Java).
//
// Слот подрыва — mat4 по столбцам:
//   [0] = (эпицентр x, z от глаза; земля под эпицентром от глаза по y; докуда таблица, блоки)
//   [1] = (снос пыли ветром x, z, блоки; длина шлейфа мглы, блоки; 1 — слот есть)
//   [2] = (ветер x, z — единичный; v строки пыли в таблице; v строки мглы)
//   [3] = (ослабление самой густой пыли, 1/блок; плитка шума, блоки; секунды после подрыва; 0)
// Таблица: по ширине — радиус 0..reach, строки — пыль (ослабление у земли, высота, доля дыма) и мгла (ослабление,
// высота, свет пожаров снизу).

// доля густоты у земли на высоте x пелены (PallField.profile)
float pallProfile(float x) {
    if (x <= 0.0) return smoothstep(-1.0, -0.4, x);
    return (1.0 - smoothstep(0.45, 1.0, x)) * (0.55 + 0.45 * exp(-2.5 * x));
}

// интеграл профиля от x до верха, в долях высоты (PallField.column)
float pallColumn(float x) {
    float xa = clamp(x, 0.0, 1.0);
    float above = 0.548 * pow(1.0 - xa, 2.45) * (1.0 + 0.83 * xa);
    if (x >= 0.0) return above;
    float f = clamp((-x - 0.4) / 0.6, 0.0, 1.0);
    return above + min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
}

// край таблицы: к нему поле тает (PallField.edge)
float pallEdge(float u) {
    return 1.0 - smoothstep(0.96, 1.0, u);
}

// Поле слота S в точке p (от глаза), без шума (среднее по шуму — оно): x — ослабление пыли, y — мглы (1/блок),
// z — доля дыма в пыли, w — свет пожаров снизу; column — столбы над точкой (пыль, мгла; ослабление × блоки),
// height — высота точки в долях пелены (пыль, мгла), dense — доля самой густой пыли.
vec4 pallField(sampler2D table, mat4 S, vec3 p, out vec2 column, out vec2 height, out float dense) {
    vec2 q = p.xz - S[0].xy;
    float above = p.y - S[0].z, reach = S[0].w;
    float ud = length(q - S[1].xy) / reach;
    vec4 dust = texture(table, vec2(min(ud, 1.0), S[2].z));
    float topD = max(dust.g, 1.0), sd = dust.r * pallEdge(ud);
    float len = S[1].z, along = clamp(dot(q, S[2].xy), 0.0, len);
    float uh = length(q - S[2].xy * along) / reach;
    vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));
    float topH = max(haze.g, 1.0), sh = haze.r * (1.0 - 0.5 * along / max(len, 1.0)) * pallEdge(uh);
    height = vec2(above / topD, above / topH);
    column = vec2(sd * topD * pallColumn(height.x), sh * topH * pallColumn(height.y));
    dense = sd / max(S[3].x, 1e-6);
    return vec4(sd * pallProfile(height.x), sh * pallProfile(height.y), dust.b, haze.b * exp(-3.0 * max(height.y, 0.0)));
}

// Форма клубов по шуму (VolumeNoise.shape): n — каналы плитки (клубы, мелкие края), fill — насколько пелена сплошная;
// среднее по плитке — 1.
float pallShape(vec2 n, float fill) {
    float c = 0.55 - 0.45 * fill;
    return smoothstep(c - 0.15, c + 0.25, n.x - 0.3 * (1.0 - n.y)) / (0.25 + 0.45 * fill);
}

// Отрезок луча (из глаза, dir — единичный) в рамке [lo, hi]: (вход, выход); вход > выхода — мимо.
vec2 pallBox(vec3 dir, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / (dir + vec3(equal(dir, vec3(0.0))) * 1e-6);
    vec3 a = lo * inv, b = hi * inv;
    vec3 t0 = min(a, b), t1 = max(a, b);
    return vec2(max(max(t0.x, t0.y), max(t0.z, 0.0)), min(min(t1.x, t1.y), t1.z));
}

// Расстояние до мира за пикселем (SceneDepth), годное для пелены: неба, а ещё NaN, бесконечность и «за глазом» (чужая
// глубина LOD, дальняя плоскость) — 1e9, мира за пикселем нет. Иначе луч шёл бы сквозь километры пыли или NaN
// попадал бы в накопленную пелену — чёрный кадр.
float pallScene(float d) {
    if (d < 0.0) return 1e9;
    if (d < 1e8) return d;
    return 1e9;
}

// Пиксель пелены годен: свет конечен (и в половинной точности кадра), пропускание 0..1; NaN не годен.
bool pallValid(vec4 v) {
    return all(lessThan(abs(v.rgb), vec3(6.0e4))) && v.a >= 0.0 && v.a <= 1.0;
}

// Отсвет на пыли, как у стены пыли (CloudPuffs.lit и CloudPuffs.burn — её свет; fx_pall берёт его, чтобы пелена за
// фронтом продолжала цвет стены): r — по земле от эпицентра, up — доля высоты пыли 0..1 (низ темнее: земля и обломки).
// r и радиусы — в одних единицах (блоки).
// Свет шара, потом раскалённой шапки сверху: overhead 0..1, lightR — шесть радиусов шара (там отсвет вдвое слабее).
float pallLit(float r, float up, float overhead, float lightR) {
    float x = r / max(lightR, 1e-3);
    return (0.3 + 0.4 * up) * overhead / (1.0 + x * x);
}

// Пожары под пылью: glow — накал гриба 0..1 (стена берёт его же), fireR — край зоны поджига (мягкий, от 0,92 до 1,04).
float pallBurn(float r, float up, float glow, float fireR) {
    float e = max(fireR, 1e-3);
    return (1.0 - smoothstep(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
}

// ---------------------------------------------------------------- огненный шторм (client/nuclear/FireStorm)
// Законы — копии FireStorm (PallGlslTest сверяет). Слот огня F — mat4 по столбцам, у каждого слота подрыва S свой:
//   [0] = (половина стороны карты огня, блоки (0 — шторма нет); клетка карты, блоки; верх столбов над землёй под
//          эпицентром, блоки; высота, на которой снос столба вдвое больше прямого, блоки)
//   [1] = (снос столба по ветру x, z на блок подъёма — ветер против подъёма дыма; подъём дыма, блоков/с;
//          зарево снизу — сила с ночью и доверием)
//   [2] = (пламя на поверхностях — сила с днём и доверием; ослабление в столбе на единицу подачи дыма, 1/блок;
//          секунды после подрыва; 0)
//   [3] = (свет пожаров на экране rgb; облака Minecraft от глаза по y — пламя на поверхностях только ниже них)
// Карта огня: R — пламя, G — подача дыма в столбы, B — G × доля подъёма столба; уменьшенные копии — скопления
// источников (свет и дым на высоте h — с уровня, где клетка карты — ширина столба или зарева там).

// снос столба на высоте h над землёй, блоки на единицу отношения ветра к подъёму (FireStorm.bend)
float fireBend(float h, float hb) {
    float x = max(h, 0.0);
    return x * (1.0 + x / hb);
}

// полуширина столба на высоте h, блоки (FireStorm.width): у земли уже клетки, выше растёт
float fireWidth(float h, float cell) {
    return 0.3 * cell + 0.22 * max(h, 0.0);
}

// уровень карты огня, где клетка — ширина w (FireStorm.lod)
float fireLod(float w, float cell) {
    return log2(max(2.0 * w / cell, 1.0));
}

// уровень карты для зарева на высоте h: свет с площади шириной ~2h (FireStorm.glowLod)
float fireGlowLod(float h, float cell) {
    return log2(max(2.0 * max(h, 6.0) / cell, 1.0));
}

// столб по высоте: от низин до верха, где он растекается в мглу (FireStorm.column)
float fireColumn(float h, float top) {
    return smoothstep(-40.0, 0.0, h) * (1.0 - smoothstep(0.65 * top, top, h));
}

// место на карте огня слота под точкой xz (от глаза)
vec2 fireUv(mat4 S, mat4 F, vec2 xz) {
    return (xz - S[0].xy) / (2.0 * F[0].x) + 0.5;
}
