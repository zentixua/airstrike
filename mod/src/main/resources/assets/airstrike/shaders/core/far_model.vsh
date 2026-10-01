#version 150

#moj_import <light.glsl>

// Модели снарядов вдали в плитки атласа (client/render/FarModels). Вершина уже на месте в плитке (перспективу из глаза
// считает FarModels), остальное — как у ванильной сущности rendertype_entity_cutout_no_cull, без тумана и вспышки урона:
// два направленных света по нормали в осях мира и свет неба по карте освещения.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = vec4(Position, 1.0);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}
