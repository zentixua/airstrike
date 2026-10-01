#version 150

// Слой эффектов (client/fx/layer): дым, пламя, искры, вспышки и всё дальнее одним проходом после мира.
in vec3 Position;
in vec2 UV0;
// свет, уже умноженный на непрозрачность; альфа — сколько закрыто того, что за частицей
in vec4 Color;
// x: мягкость края у рельефа, блоков × 16; y: во сколько раз точка перенесена ближе (дальше дальней плоскости), × 32767
in ivec2 UV1;
// свет мира (карта освещения)
in ivec2 UV2;
// x: туман Minecraft 0..1; y: 1 — частица (ближайшим пикселем, как из атласа Minecraft); z: её непрозрачность без текстуры
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDistance;
out float softness;
out float fog;
out float nearest;
out float opacity;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord0 = UV0;
    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);
    vertexColor = vec4(Color.rgb * light.rgb, Color.a);
    // настоящее расстояние по оси взгляда: перенесённую ближе точку — обратно
    viewDistance = -view.z / max(float(UV1.y) / 32767.0, 1e-4);
    softness = float(UV1.x) / 16.0;
    fog = Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
}
