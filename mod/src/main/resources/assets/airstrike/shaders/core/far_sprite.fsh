#version 150

// Снаряды и взрывы вдали (client/far/FarSprites). Цвет вершины — свет, уже умноженный на непрозрачность, альфа —
// сколько закрыто того, что за спрайтом (смешивание ONE, ONE_MINUS_SRC_ALPHA): дым закрывает и светит своим цветом,
// раскалённое тело закрывает и светит, ореол только светит (альфа 0). Текстура задаёт форму и множит и то и другое.
// В отличие от ванильного position_tex_color, бледное (альфа меньше 0,1) не отбрасывается: дальний дым и зарево —
// как раз бледные.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    fragColor = vec4(vertexColor.rgb * tex.rgb, vertexColor.a) * tex.a * ColorModulator;
}
