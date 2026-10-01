#version 150

#moj_import <light.glsl>

// Модели снарядов вдали в плитки атласа (client/render/FarModels). Сетка детали лежит в видеопамяти как есть:
// ModelViewMat ставит деталь в оси взгляда на модель, ProjMat — перспектива из глаза в плитку, NormalMat — поворот
// нормали детали в оси мира. Остальное — как у ванильной сущности rendertype_entity_cutout_no_cull, без тумана и
// вспышки урона: два направленных света по нормали в осях мира и свет неба по карте освещения; ColorModulator —
// прозрачность размытого диска винта.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec3 normal = normalize(NormalMat * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color) * texelFetch(Sampler2, UV2 / 16, 0) * ColorModulator;
    texCoord0 = UV0;
}
