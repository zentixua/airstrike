// Пелена ядерного удара перед точкой — её среднее поле без шума (pall.glsl: импортировать до этого файла): квадратам слоя
// (fx.vsh: квадрат за пеленой) и пробе источников (fx_probe: блик и вуаль за пылью) — одним кодом и одними числами, их
// задаёт PallPass.bindFront. Грибы кадра (mushroom.glsl) — своим полем, отдельно: у пробы — без своей шапки.

// пелена (pall.glsl, PallPass): таблица поля, слоты подрывов, рамка, её средний свет (w — 1, если пелена на кадре)
uniform sampler2D Sampler5;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
uniform vec3 PallMin;
uniform vec3 PallMax;
uniform vec4 PallAmbient;
// рельеф и вода под пеленой слотов, их числа и вода слотов: из чего пелена перед точкой
uniform sampler2D Sampler9;
uniform sampler2D Sampler10;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// стена пыли слотов (client/nuclear/BlastWall): голова-вал и облако Вильсона
uniform mat4 PallWall0;
uniform mat4 PallWall1;
uniform vec4 PallWilson0;
uniform vec4 PallWilson1;
// грибы кадра (mushroom.glsl, MushroomField): что гриб закрывает перед точкой
uniform sampler2D Sampler11;

// Голова стены слота перед точкой на [t0, t1]: доля, которую она пропускает, — по каждому её отрезку на луче
// (pallWallSpans), в дымке воздуха у его середины
float pallWallThrough(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 dir, float t0, float t1, float air) {
    vec4 spans = pallWallSpans(S, V, G, dir, t0, t1);
    float through = 1.0;
    for (int k = 0; k < 2; k++) {
        vec2 sp = k == 0 ? spans.xy : spans.zw;
        if (sp.y <= sp.x) continue;
        float tau = pallWallTau(ground, S, V, G, W, dir, sp.x, sp.y, 4);
        through *= 1.0 - exp(-air * 0.5 * (sp.x + sp.y)) * (1.0 - exp(-tau));
    }
    return through;
}

// Облако Вильсона слота перед точкой (до end): доля, которую пропускают оба прохода луча, каждый в дымке у своего места
float pallWilsonThrough(mat4 S, vec4 L, vec3 dir, float end, float air) {
    vec4 w = pallWilson(S, L, dir, end);
    return (1.0 - exp(-air * w.x) * (1.0 - exp(-w.y))) * (1.0 - exp(-air * w.z) * (1.0 - exp(-w.w)));
}

// Сколько пропускает пелена между глазом и точкой dir·len (без грибов): шесть шагов по полю без шума (среднее по шуму —
// оно), голова стены и облако Вильсона — по своим отрезкам луча. Пелена вдали тает в дымке воздуха, как гриб (mushAir;
// слот: [3].w): та доля её непрозрачности, что съела дымка, точку не закрывает — как в проходе пелены (fx_pall: passMatter).
float pallThrough(vec3 dir, float len) {
    if (PallAmbient.w < 0.5) return 1.0;
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    float air = PallSlot0[1].w > 0.5 ? PallSlot0[3].w : PallSlot1[3].w;
    float through = 1.0, ds = (t1 - t0) / 6.0;
    for (int i = 0; i < 6; i++) {
        float t = t0 + (float(i) + 0.5) * ds, tau = 0.0;
        vec3 q = dir * t;
        // по полной толще всего вещества — как луч объёма (водяная пыль тоже гасит то, что за ней)
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler9, PallSlot0, PallGround0, PallWater0, q, 0.0);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler10, PallSlot1, PallGround1, PallWater1, q, 0.0);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
        through *= 1.0 - exp(-air * t) * (1.0 - exp(-tau));
    }
    return through * pallWallThrough(Sampler9, PallSlot0, PallWall0, PallGround0, PallWater0, dir, t0, t1, air)
            * pallWallThrough(Sampler10, PallSlot1, PallWall1, PallGround1, PallWater1, dir, t0, t1, air)
            * pallWilsonThrough(PallSlot0, PallWilson0, dir, t1, air) * pallWilsonThrough(PallSlot1, PallWilson1, dir, t1, air);
}
