package com.flowcut.app.engine

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.flowcut.app.model.Clip
import com.flowcut.app.model.TransitionType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class DrawSpec(
    var type: TransitionType = TransitionType.NONE,
    var easedP: Float = 0f,
    var matA: FloatArray = FloatArray(16),
    var matB: FloatArray = FloatArray(16),
    var cropA: FloatArray = FloatArray(2),
    var cropB: FloatArray = FloatArray(2),
    var opacityA: Float = 1.0f,
    var opacityB: Float = 1.0f,
    var adjA0: FloatArray? = null,
    var adjA1: FloatArray? = null,
    var adjA2: FloatArray? = null,
    var adjB0: FloatArray? = null,
    var adjB1: FloatArray? = null,
    var adjB2: FloatArray? = null,
    var texelSize: FloatArray? = null,
    var fgNormMatA: FloatArray? = null,
    var fgNormMatB: FloatArray? = null,
    var bgMatA: FloatArray? = null,
    var bgMatB: FloatArray? = null,
    var bgModeA: Int = 0,
    var bgModeB: Int = 0,
    var bgParamsA: FloatArray? = null,
    var bgParamsB: FloatArray? = null
)

class TransitionRenderer {

    private var programId = 0
    private var vaoId = 0
    private var vboId = 0
    private var texAId = 0
    private var texBId = 0

    // Uniform locations
    private var uTexALoc = -1
    private var uTexBLoc = -1
    private var uMatALoc = -1
    private var uMatBLoc = -1
    private var uCropALoc = -1
    private var uCropBLoc = -1
    private var uPLoc = -1
    private var uTypeLoc = -1
    private var uAspectLoc = -1
    private var uAdjA0Loc = -1
    private var uAdjA1Loc = -1
    private var uAdjA2Loc = -1
    private var uAdjB0Loc = -1
    private var uAdjB1Loc = -1
    private var uAdjB2Loc = -1
    private var uTexelSizeLoc = -1
    private var uFgNormMatALoc = -1
    private var uFgNormMatBLoc = -1
    private var uBgMatALoc = -1
    private var uBgMatBLoc = -1
    private var uBgModeALoc = -1
    private var uBgModeBLoc = -1
    private var uBgParamsALoc = -1
    private var uBgParamsBLoc = -1
    private var uOpacityALoc = -1
    private var uOpacityBLoc = -1

    // Overlay compositor program and uniforms
    private var overlayProgramId = 0
    private var uOverlayMatLoc = -1
    private var uOverlayTexLoc = -1
    private var uOverlayOpacityLoc = -1
    private val overlayMvp = FloatArray(16)

    // Pre-allocated fallback arrays for zero allocations (Rule R10)
    private val defaultAdj = FloatArray(4)
    private val defaultTexelSize = floatArrayOf(1f / 720f, 1f / 1280f)
    private val defaultIdentityMat = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private val defaultBgParams = FloatArray(4)

    private val vertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPos;
        layout(location = 1) in vec2 aUV;
        out vec2 vUV;
        void main() {
            gl_Position = aPos;
            vUV = aUV;
        }
    """.trimIndent()

    private val fragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform samplerExternalOES uTexA;
        uniform samplerExternalOES uTexB;
        uniform mat4 uMatA;
        uniform mat4 uMatB;
        uniform vec2 uCropA;
        uniform vec2 uCropB;
        uniform float uP;
        uniform int uType;
        uniform float uAspect;

        uniform vec4 uAdjA0;
        uniform vec4 uAdjA1;
        uniform vec4 uAdjA2;
        uniform vec4 uAdjB0;
        uniform vec4 uAdjB1;
        uniform vec4 uAdjB2;
        uniform vec2 uTexelSize;

        uniform mat4 uFgNormMatA;
        uniform mat4 uFgNormMatB;
        uniform mat4 uBgMatA;
        uniform mat4 uBgMatB;
        uniform int uBgModeA;
        uniform int uBgModeB;
        uniform vec4 uBgParamsA;
        uniform vec4 uBgParamsB;
        uniform float uOpacityA;
        uniform float uOpacityB;

        in vec2 vUV;
        out vec4 outColor;

        vec4 applyColorAdjust(samplerExternalOES tex, vec2 uv, vec4 adj0, vec4 adj1, vec4 adj2, vec2 texelSize) {
            // Fast Path: If all adjustments for this slot are 0.0, return raw sampled texture color immediately
            if (dot(abs(adj0), vec4(1.0)) == 0.0 &&
                dot(abs(adj1), vec4(1.0)) == 0.0 &&
                dot(abs(adj2.xy), vec2(1.0)) == 0.0) {
                return texture(tex, uv);
            }

            float brightness = adj0.x;
            float contrast   = adj0.y;
            float saturation = adj0.z;
            float exposure   = adj0.w;

            float warmth     = adj1.x;
            float tint       = adj1.y;
            float highlights = adj1.z;
            float shadows    = adj1.w;

            float sharpness  = adj2.x;
            float vignette   = adj2.y;

            vec4 color = texture(tex, uv);

            // Sharpness (sharpness > 0.001): 5-tap unsharp mask
            if (sharpness > 0.001) {
                vec3 nLeft  = texture(tex, uv - vec2(texelSize.x, 0.0)).rgb;
                vec3 nRight = texture(tex, uv + vec2(texelSize.x, 0.0)).rgb;
                vec3 nDown  = texture(tex, uv - vec2(0.0, texelSize.y)).rgb;
                vec3 nUp    = texture(tex, uv + vec2(0.0, texelSize.y)).rgb;
                vec3 neighbors = nLeft + nRight + nDown + nUp;
                color.rgb += (color.rgb * 4.0 - neighbors) * (sharpness * 1.2);
            }

            // Exposure & Brightness
            color.rgb *= exp2(exposure * 1.2);
            color.rgb += brightness * 0.35;

            // Highlights & Shadows
            float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            float shadowWeight = (1.0 - clamp(luma, 0.0, 1.0)) * (1.0 - clamp(luma, 0.0, 1.0));
            float highlightWeight = clamp(luma, 0.0, 1.0) * clamp(luma, 0.0, 1.0);
            color.rgb += (shadows * shadowWeight + highlights * highlightWeight) * 0.35;

            // Contrast: Pivot around mid-gray 0.5
            color.rgb = (color.rgb - 0.5) * (1.0 + contrast * 0.85) + 0.5;

            // Saturation: Mix between vec3(luma) and color.rgb by (1.0 + saturation)
            float lumaAfter = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            color.rgb = mix(vec3(lumaAfter), color.rgb, max(0.0, 1.0 + saturation));

            // Warmth (Temp) & Tint
            color.r += warmth * 0.12;
            color.b -= warmth * 0.12;
            color.g -= tint * 0.10;
            color.r += tint * 0.05;
            color.b += tint * 0.05;

            // Vignette: radial distance from uv center (0.5, 0.5)
            float dist = length(uv - vec2(0.5, 0.5));
            float vigFactor = smoothstep(0.25, 0.85, dist);
            if (vignette < 0.0) {
                color.rgb *= (1.0 + vignette * vigFactor);
            } else if (vignette > 0.0) {
                color.rgb = mix(color.rgb, vec3(1.0), vignette * vigFactor * 0.8);
            }

            // Clamp final RGB cleanly to [0.0, 1.0]
            color.rgb = clamp(color.rgb, 0.0, 1.0);
            return color;
        }

        vec4 sampleBlur(samplerExternalOES tex, vec2 uv, float blurLevel, vec2 texelSize) {
            if (blurLevel <= 0.001) {
                return texture(tex, uv) * 0.88;
            }
            float radius = max(1.0, blurLevel * 20.0);
            vec2 stride = (texelSize * 4.0) * radius;

            // 9-tap kernel: center + 8-tap 2-ring diamond/rotated-grid (effective 1/4 res stride)
            vec4 sum = texture(tex, uv) * 0.20;

            // Ring 1 (inner diamond, radius 0.5 * stride) - 4 taps
            vec2 r1 = stride * 0.5;
            sum += texture(tex, clamp(uv + vec2( r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2(-r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,   r1.y), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,  -r1.y), 0.001, 0.999)) * 0.11;

            // Ring 2 (outer rotated grid at 45 deg, radius 1.0 * stride) - 4 taps
            vec2 r2 = stride * 0.7071;
            sum += texture(tex, clamp(uv + vec2( r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2( r2.x, -r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x, -r2.y), 0.001, 0.999)) * 0.09;

            // Subtle 0.88 brightness dim so the sharp foreground video pops!
            sum.rgb *= 0.88;
            sum.a = 1.0;
            return sum;
        }

        vec4 sampleClipA(vec2 uv) {
            vec2 cropUv = (uv - 0.5) * uCropA + 0.5;
            vec2 fgCoord = (uFgNormMatA * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityA, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatA * vec4(cropUv, 0.0, 1.0)).xy;
                return applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeA == 1) {
                    bg = vec4(uBgParamsA.rgb, 1.0);
                } else if (uBgModeA == 2) {
                    vec2 bgUv = (uBgMatA * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur(uTexA, bgUv, uBgParamsA.a, uTexelSize);
                }
            }

            if (fgAlpha <= 0.0) {
                return bg;
            }

            vec2 texUv = (uMatA * vec4(cropUv, 0.0, 1.0)).xy;
            vec4 fg = applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            return mix(bg, fg, fgAlpha);
        }

        vec4 sampleClipB(vec2 uv) {
            vec2 cropUv = (uv - 0.5) * uCropB + 0.5;
            vec2 fgCoord = (uFgNormMatB * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityB, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatB * vec4(cropUv, 0.0, 1.0)).xy;
                return applyColorAdjust(uTexB, texUv, uAdjB0, uAdjB1, uAdjB2, uTexelSize);
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeB == 1) {
                    bg = vec4(uBgParamsB.rgb, 1.0);
                } else if (uBgModeB == 2) {
                    vec2 bgUv = (uBgMatB * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur(uTexB, bgUv, uBgParamsB.a, uTexelSize);
                }
            }

            if (fgAlpha <= 0.0) {
                return bg;
            }

            vec2 texUv = (uMatB * vec4(cropUv, 0.0, 1.0)).xy;
            vec4 fg = applyColorAdjust(uTexB, texUv, uAdjB0, uAdjB1, uAdjB2, uTexelSize);
            return mix(bg, fg, fgAlpha);
        }

        vec4 sA(vec2 uv) { return sampleClipA(uv); }
        vec4 sB(vec2 uv) { return sampleClipB(uv); }

        void main() {
            vec4 c;
            if (uType == 0) {
                c = sA(vUV);
            } else if (uType == 1) {
                // SLIDE: B comes from right, A moves 30% left
                if (vUV.x > 1.0 - uP) {
                    c = sB(vec2(vUV.x - (1.0 - uP), vUV.y));
                } else {
                    c = sA(vec2(vUV.x + 0.3 * uP, vUV.y));
                }
            } else if (uType == 2) {
                // CIRCLE: circular reveal from center
                vec2 d = (vUV - 0.5) * vec2(uAspect, 1.0);
                float maxR = length(vec2(0.5 * uAspect, 0.5));
                float r = uP * (maxR + 0.01);
                float m = (uP <= 0.0) ? 0.0 : 1.0 - smoothstep(r - 0.004, r + 0.004, length(d));
                c = mix(sA(vUV), sB(vUV), m);
            } else if (uType == 3) {
                // ZOOM: A zooms in and fades out, B zooms out from 1.4x to 1.0x and fades in
                float sa = 1.0 + 0.8 * uP;
                float sb = 1.4 - 0.4 * uP;
                vec4 a = sA((vUV - 0.5) / sa + 0.5);
                vec4 b = sB((vUV - 0.5) / sb + 0.5);
                c = mix(a, b, uP);
            } else {
                // FADE
                c = mix(sA(vUV), sB(vUV), uP);
            }
            outColor = vec4(c.rgb, 1.0);
        }
    """.trimIndent()

    private val overlayVertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPos;
        layout(location = 1) in vec2 aUV;
        uniform mat4 uOverlayMat;
        out vec2 vUV;
        void main() {
            gl_Position = uOverlayMat * aPos;
            vUV = vec2(aUV.x, 1.0 - aUV.y);
        }
    """.trimIndent()

    private val overlayFragmentShaderSource = """
        #version 300 es
        precision highp float;
        uniform sampler2D uOverlayTex;
        uniform float uOverlayOpacity;
        in vec2 vUV;
        out vec4 outColor;
        void main() {
            vec4 texColor = texture(uOverlayTex, vUV);
            outColor = vec4(texColor.rgb, texColor.a * clamp(uOverlayOpacity, 0.0, 1.0));
        }
    """.trimIndent()

    // 4 vertices (Pos: x, y; UV: u, v)
    // Triangle strip: (-1,-1), (1,-1), (-1,1), (1,1)
    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    )

    fun init() {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderSource)
        programId = createProgram(vertexShader, fragmentShader)

        // Locations
        uTexALoc = GLES30.glGetUniformLocation(programId, "uTexA")
        uTexBLoc = GLES30.glGetUniformLocation(programId, "uTexB")
        uMatALoc = GLES30.glGetUniformLocation(programId, "uMatA")
        uMatBLoc = GLES30.glGetUniformLocation(programId, "uMatB")
        uCropALoc = GLES30.glGetUniformLocation(programId, "uCropA")
        uCropBLoc = GLES30.glGetUniformLocation(programId, "uCropB")
        uPLoc = GLES30.glGetUniformLocation(programId, "uP")
        uTypeLoc = GLES30.glGetUniformLocation(programId, "uType")
        uAspectLoc = GLES30.glGetUniformLocation(programId, "uAspect")
        uAdjA0Loc = GLES30.glGetUniformLocation(programId, "uAdjA0")
        uAdjA1Loc = GLES30.glGetUniformLocation(programId, "uAdjA1")
        uAdjA2Loc = GLES30.glGetUniformLocation(programId, "uAdjA2")
        uAdjB0Loc = GLES30.glGetUniformLocation(programId, "uAdjB0")
        uAdjB1Loc = GLES30.glGetUniformLocation(programId, "uAdjB1")
        uAdjB2Loc = GLES30.glGetUniformLocation(programId, "uAdjB2")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        uFgNormMatALoc = GLES30.glGetUniformLocation(programId, "uFgNormMatA")
        uFgNormMatBLoc = GLES30.glGetUniformLocation(programId, "uFgNormMatB")
        uBgMatALoc = GLES30.glGetUniformLocation(programId, "uBgMatA")
        uBgMatBLoc = GLES30.glGetUniformLocation(programId, "uBgMatB")
        uBgModeALoc = GLES30.glGetUniformLocation(programId, "uBgModeA")
        uBgModeBLoc = GLES30.glGetUniformLocation(programId, "uBgModeB")
        uBgParamsALoc = GLES30.glGetUniformLocation(programId, "uBgParamsA")
        uBgParamsBLoc = GLES30.glGetUniformLocation(programId, "uBgParamsB")
        uOpacityALoc = GLES30.glGetUniformLocation(programId, "uOpacityA")
        uOpacityBLoc = GLES30.glGetUniformLocation(programId, "uOpacityB")

        // Setup Overlay Compositor Program
        val overlayVs = compileShader(GLES30.GL_VERTEX_SHADER, overlayVertexShaderSource)
        val overlayFs = compileShader(GLES30.GL_FRAGMENT_SHADER, overlayFragmentShaderSource)
        overlayProgramId = createProgram(overlayVs, overlayFs)
        uOverlayMatLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayMat")
        uOverlayTexLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayTex")
        uOverlayOpacityLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayOpacity")

        // Setup VAO & VBO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        buffer.position(0)

        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quadVertices.size * 4,
            buffer,
            GLES30.GL_STATIC_DRAW
        )

        // aPos: location 0, 2 floats, stride = 16
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)

        // aUV: location 1, 2 floats, stride = 16, offset = 8
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Generate 2 external textures
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        texAId = textures[0]
        texBId = textures[1]

        setupExternalTexture(texAId)
        setupExternalTexture(texBId)
    }

    private fun setupExternalTexture(textureId: Int) {
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun textureIdA(): Int = texAId
    fun textureIdB(): Int = texBId

    fun draw(spec: DrawSpec) {
        GLES30.glUseProgram(programId)

        // Bind Texture A to Unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texAId)
        GLES30.glUniform1i(uTexALoc, 0)

        // Bind Texture B to Unit 1 (or dummy A if none)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        if (spec.type != TransitionType.NONE) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texBId)
        } else {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texAId)
        }
        GLES30.glUniform1i(uTexBLoc, 1)

        // Set uniforms
        GLES30.glUniformMatrix4fv(uMatALoc, 1, false, spec.matA, 0)
        GLES30.glUniformMatrix4fv(uMatBLoc, 1, false, spec.matB, 0)
        GLES30.glUniform2fv(uCropALoc, 1, spec.cropA, 0)
        GLES30.glUniform2fv(uCropBLoc, 1, spec.cropB, 0)
        GLES30.glUniform1f(uPLoc, spec.easedP)
        GLES30.glUniform1i(uTypeLoc, spec.type.glId)
        GLES30.glUniform1f(uAspectLoc, 9.0f / 16.0f)
        GLES30.glUniform4fv(uAdjA0Loc, 1, spec.adjA0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjA1Loc, 1, spec.adjA1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjA2Loc, 1, spec.adjA2 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB0Loc, 1, spec.adjB0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB1Loc, 1, spec.adjB1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB2Loc, 1, spec.adjB2 ?: defaultAdj, 0)
        GLES30.glUniform2fv(uTexelSizeLoc, 1, spec.texelSize ?: defaultTexelSize, 0)
        GLES30.glUniformMatrix4fv(uFgNormMatALoc, 1, false, spec.fgNormMatA ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(uFgNormMatBLoc, 1, false, spec.fgNormMatB ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(uBgMatALoc, 1, false, spec.bgMatA ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(uBgMatBLoc, 1, false, spec.bgMatB ?: defaultIdentityMat, 0)
        GLES30.glUniform1i(uBgModeALoc, spec.bgModeA)
        GLES30.glUniform1i(uBgModeBLoc, spec.bgModeB)
        GLES30.glUniform4fv(uBgParamsALoc, 1, spec.bgParamsA ?: defaultBgParams, 0)
        GLES30.glUniform4fv(uBgParamsBLoc, 1, spec.bgParamsB ?: defaultBgParams, 0)
        GLES30.glUniform1f(uOpacityALoc, spec.opacityA)
        GLES30.glUniform1f(uOpacityBLoc, spec.opacityB)

        // Draw quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
    }

    /**
     * Compositor pass for overlay items (text, sticker, image) over the base video layer.
     * Uses standard GL_TEXTURE_2D + straight alpha blending.
     * Automatically skips overlays with opacity <= 0.001f or off-screen bounds (Rule R10).
     */
    fun drawOverlayQuad(
        texId: Int,
        texWidth: Int,
        texHeight: Int,
        transform: ClipTransform,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920
    ) {
        if (texId <= 0) return
        val effectiveOpacity = (transform.opacity * opacity).coerceIn(0f, 1f)
        if (effectiveOpacity <= 0.001f) return

        if (isOverlayOffScreen(transform, texWidth, texHeight, displayWidth, displayHeight)) {
            return
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(overlayProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(uOverlayTexLoc, 0)
        GLES30.glUniform1f(uOverlayOpacityLoc, effectiveOpacity)

        computeOverlayMvp(overlayMvp, transform, texWidth, texHeight, displayWidth, displayHeight)
        GLES30.glUniformMatrix4fv(uOverlayMatLoc, 1, false, overlayMvp, 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun drawOverlayQuad(
        texId: Int,
        transform: ClipTransform,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920
    ) {
        drawOverlayQuad(texId, 1, 1, transform, opacity, displayWidth, displayHeight)
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        if (overlayProgramId != 0) {
            GLES30.glDeleteProgram(overlayProgramId)
            overlayProgramId = 0
        }
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (texAId != 0 || texBId != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(texAId, texBId), 0)
            texAId = 0
            texBId = 0
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Error compiling shader ($type): $log")
        }
        return shader
    }

    private fun createProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Error linking program: $log")
        }
        return program
    }

    companion object {
        fun calculateCrop(clip: Clip): FloatArray {
            val aspect = clip.displayWidth.toFloat() / clip.displayHeight.toFloat()
            val target = 9f / 16f
            return if (aspect > target) {
                floatArrayOf(target / aspect, 1.0f)
            } else {
                floatArrayOf(1.0f, aspect / target)
            }
        }

        fun isOverlayOffScreen(
            transform: ClipTransform,
            texWidth: Int = 1,
            texHeight: Int = 1,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920
        ): Boolean {
            val aScreen = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else (9.0f / 16.0f)
            val tw = if (texWidth > 0) texWidth.toFloat() else 1f
            val th = if (texHeight > 0) texHeight.toFloat() else 1f
            val aTex = (tw / th).coerceIn(0.01f, 100f)

            var hw = 0.6f
            var hh = hw / aTex
            if (hh * aScreen > 0.6f) {
                val factor = 0.6f / (hh * aScreen)
                hw *= factor
                hh *= factor
            }

            val scale = transform.scale.coerceIn(0.01f, 10.0f)
            val diag = kotlin.math.sqrt(hw * hw + hh * hh) * scale
            val rx = diag
            val ry = diag * aScreen

            val cx = transform.panX * 2.0f
            val cy = transform.panY * 2.0f

            val left = cx - rx
            val right = cx + rx
            val bottom = cy - ry
            val top = cy + ry

            return right < -1.0f || left > 1.0f || top < -1.0f || bottom > 1.0f
        }

        fun computeOverlayMvp(
            outMvp: FloatArray,
            transform: ClipTransform,
            texWidth: Int = 1,
            texHeight: Int = 1,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920
        ) {
            val aScreen = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else (9.0f / 16.0f)
            val tw = if (texWidth > 0) texWidth.toFloat() else 1f
            val th = if (texHeight > 0) texHeight.toFloat() else 1f
            val aTex = (tw / th).coerceIn(0.01f, 100f)

            var hw = 0.6f
            var hh = hw / aTex
            if (hh * aScreen > 0.6f) {
                val factor = 0.6f / (hh * aScreen)
                hw *= factor
                hh *= factor
            }

            val scale = transform.scale.coerceIn(0.01f, 10.0f)

            android.opengl.Matrix.setIdentityM(outMvp, 0)
            android.opengl.Matrix.translateM(outMvp, 0, transform.panX * 2.0f, transform.panY * 2.0f, 0f)
            android.opengl.Matrix.scaleM(outMvp, 0, 1.0f, aScreen, 1.0f)
            if (transform.rotationDeg != 0f) {
                android.opengl.Matrix.rotateM(outMvp, 0, -transform.rotationDeg, 0f, 0f, 1f)
            }
            if (transform.isFlippedH) {
                android.opengl.Matrix.scaleM(outMvp, 0, -1f, 1f, 1f)
            }
            android.opengl.Matrix.scaleM(outMvp, 0, hw * scale, hh * scale, 1f)
        }
    }
}

